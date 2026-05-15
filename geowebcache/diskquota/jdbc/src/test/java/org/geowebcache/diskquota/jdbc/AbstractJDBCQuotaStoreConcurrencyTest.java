/**
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * <p>You should have received a copy of the GNU Lesser General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * <p>Copyright 2026
 */
package org.geowebcache.diskquota.jdbc;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.commons.dbcp.BasicDataSource;
import org.geowebcache.diskquota.storage.PageStatsPayload;
import org.geowebcache.diskquota.storage.Quota;
import org.geowebcache.diskquota.storage.TilePage;
import org.geowebcache.diskquota.storage.TilePageCalculator;
import org.geowebcache.diskquota.storage.TileSet;
import org.geowebcache.storage.DefaultStorageFinder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Concurrency suite for {@link JDBCQuotaStore} that runs the same scenarios against any dialect supplied by a subclass.
 *
 * <p>Each scenario spawns several writer threads contending on a shared {@code TILESET} / {@code TILEPAGE} row under
 * the store's {@code SERIALIZABLE} isolation level. Two invariants are verified end-to-end:
 *
 * <ul>
 *   <li>No {@link org.springframework.dao.DataAccessException DataAccessException} escapes to the caller - the bounded
 *       retry layer in {@code JDBCQuotaStore} must keep transaction aborts internal.
 *   <li>The resulting ledger total is exact ({@code threads * iterations * delta}) - aborted transactions must be
 *       replayed, not silently dropped.
 * </ul>
 *
 * <p>Without the retry layer the suite fails on engines that surface serialization aborts (Postgres SSI, Oracle
 * ORA-08176): at least one thread observes the abort and the {@code TILESET.BYTES} total drifts below the expected
 * value. Engines that serialize on row locks instead (HSQL, H2 in non-MVCC mode) still exercise the test scaffolding
 * deterministically.
 *
 * <p>Subclasses supply a {@link #newDataSource()} and {@link #newDialect()}; both are invoked from a single
 * {@link #setUpStore()} so subclass-specific resources (in-memory DB URLs, container-driven pools) stay local to the
 * subclass.
 */
public abstract class AbstractJDBCQuotaStoreConcurrencyTest {

    protected static final int THREAD_COUNT = 4;
    protected static final int ITERATIONS_PER_THREAD = 200;
    protected static final long BYTES_PER_ITERATION = 1024L;

    /** Per-test data source. Replaced fresh in every {@link #setUpStore()}; closed via {@code store.close()}. */
    protected DataSource dataSource;

    protected JDBCQuotaStore store;
    protected TileSet tileSet;
    protected TilePage tilePage;

    /** Subclass-provided factory for the test's data source; called once per test method. */
    protected abstract DataSource newDataSource() throws Exception;

    /** Subclass-provided dialect under test. */
    protected abstract SQLDialect newDialect();

    /**
     * Drops the schema tables to give every test method a clean state. Default uses standard {@code CASCADE}; Oracle
     * needs {@code CASCADE CONSTRAINTS} and overrides.
     */
    protected void cleanupDatabase(DataSource ds) throws SQLException {
        try (Connection cx = ds.getConnection();
                Statement st = cx.createStatement()) {
            try {
                st.execute("DROP TABLE TILEPAGE CASCADE");
            } catch (SQLException ignored) {
                // table may not exist on first run
            }
            try {
                st.execute("DROP TABLE TILESET CASCADE");
            } catch (SQLException ignored) {
                // table may not exist on first run
            }
        }
    }

    /**
     * Builds a {@link BasicDataSource} suitable for the concurrent suite: pool sized for {@link #THREAD_COUNT} writers
     * plus a small headroom, with a short max-wait so a deadlocked test fails fast instead of hanging.
     */
    protected static BasicDataSource newPooledDataSource(String driver, String url, String user, String password) {
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName(driver);
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setPoolPreparedStatements(true);
        ds.setAccessToUnderlyingConnectionAllowed(true);
        ds.setMinIdle(1);
        ds.setMaxActive(THREAD_COUNT + 2);
        ds.setMaxWait(5000);
        return ds;
    }

    @Before
    public final void setUpStore() throws Exception {
        dataSource = newDataSource();
        cleanupDatabase(dataSource);

        DefaultStorageFinder finder = mock(DefaultStorageFinder.class);
        TilePageCalculator calculator = mock(TilePageCalculator.class);
        when(calculator.getLayerNames()).thenReturn(Collections.emptySet());
        when(calculator.getTilesPerPage(any(TileSet.class), anyInt())).thenReturn(BigInteger.valueOf(1_000_000));

        store = new JDBCQuotaStore(finder, calculator);
        store.setDataSource(dataSource);
        store.setDialect(newDialect());
        store.initialize();

        tileSet = new TileSet("layer", "EPSG:4326", "image/png", null);
        tilePage = new TilePage(tileSet.getId(), 0, 0, 0);

        // Pre-create both rows so the contention is row-update, not row-insert.
        store.addToQuotaAndTileCounts(tileSet, new Quota(BigInteger.ZERO), Collections.singletonList(payload(1)));
    }

    @After
    public final void tearDownStore() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    /**
     * N threads concurrently {@link JDBCQuotaStore#addToQuotaAndTileCounts add} a fixed byte delta and a tile-count
     * delta to the same {@code TILESET} / {@code TILEPAGE} row. The ledger total must equal the sum of all deltas
     * exactly; any drift indicates an aborted transaction that was never replayed.
     */
    @Test(timeout = 120_000)
    public void concurrentAddToQuota_doesNotDriftUnderSerializable() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < THREAD_COUNT; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                Quota delta = new Quota(BigInteger.valueOf(BYTES_PER_ITERATION));
                for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                    store.addToQuotaAndTileCounts(tileSet, delta, Collections.singletonList(payload(1)));
                }
                return null;
            }));
        }
        start.countDown();
        try {
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        BigInteger expected = BigInteger.valueOf((long) THREAD_COUNT * ITERATIONS_PER_THREAD * BYTES_PER_ITERATION);
        assertEquals(
                "TILESET.BYTES drifted: aborted transactions were not replayed",
                expected,
                store.getUsedQuotaByTileSetId(tileSet.getId()).getBytes());
        assertEquals(
                "Global TILESET.BYTES drifted: aborted transactions were not replayed",
                expected,
                store.getGloballyUsedQuota().getBytes());
    }

    /**
     * Two threads concurrently {@link JDBCQuotaStore#setTruncated truncate} the same {@code TILEPAGE} row. The work is
     * idempotent (fillFactor is repeatedly set to 0), so the assertion is the negative one: no abort exception escapes
     * to the caller.
     */
    @Test(timeout = 120_000)
    public void concurrentSetTruncated_doesNotThrowUnderSerializable() throws Exception {
        // Bring the page row into a state where setTruncated has work to do (fillFactor > 0).
        store.addToQuotaAndTileCounts(tileSet, new Quota(BigInteger.ZERO), Collections.singletonList(payload(100)));

        int truncators = 2;
        int iterations = 100;
        ExecutorService pool = Executors.newFixedThreadPool(truncators);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < truncators; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    store.setTruncated(tilePage);
                }
                return null;
            }));
        }
        start.countDown();
        try {
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    protected PageStatsPayload payload(int numTiles) {
        PageStatsPayload p = new PageStatsPayload(tilePage);
        p.setNumTiles(numTiles);
        return p;
    }
}
