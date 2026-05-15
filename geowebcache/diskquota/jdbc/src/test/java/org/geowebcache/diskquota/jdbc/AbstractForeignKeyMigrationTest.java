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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.Test;

/**
 * Verifies the legacy-to-current path in {@link SQLDialect#migrateForeignKeys}: the dialect's current {@code TILEPAGE
 * -> TILESET} foreign key shape is established by dropping the legacy form and re-adding it. The exact "current shape"
 * varies per dialect - PG/H2/HSQL re-add it as {@code ON UPDATE CASCADE ON DELETE CASCADE}, Oracle as {@code ON DELETE
 * CASCADE DEFERRABLE INITIALLY DEFERRED} - so subclasses describe their target via the
 * {@link #expectedMigratedFkState()} / {@link #readFkState(ResultSet)} / {@link #legacyDdl(String)} hooks.
 *
 * <p>The {@code JDBCQuotaStoreTest} suite always starts from fresh-DDL tables, so it only exercises the no-op
 * idempotent branch. This class fills in the upgrade path.
 *
 * <p>Each test starts from a "legacy" schema built by stripping the dialect-specific migrated clause from the dialect's
 * own table-creation SQL. Subclasses provide the dialect and a DataSource pointed at the database under test.
 */
public abstract class AbstractForeignKeyMigrationTest {

    /** Dialect under test. */
    protected abstract SQLDialect dialect();

    /** Data source pointed at a usable database where the legacy schema can be (re)created. */
    protected abstract DataSource dataSource();

    /**
     * Recreates the legacy schema. Subclasses call this from their {@code @Before} after wiring the data source; not
     * annotated so the dialect/dataSource setup ordering is always explicit.
     */
    protected void recreateLegacySchema() throws SQLException {
        try (Connection cx = dataSource().getConnection();
                Statement st = cx.createStatement()) {
            dropIfExists(st, "TILEPAGE");
            dropIfExists(st, "TILESET");
            for (String table : dialect().TABLE_CREATION_MAP.keySet()) {
                for (String ddl : dialect().TABLE_CREATION_MAP.get(table)) {
                    st.execute(legacyDdl(ddl));
                }
            }
        }
    }

    /**
     * Hook: returns the dialect's current DDL with its migrated FK clause stripped, plus the schema placeholder
     * substituted with the empty prefix.
     *
     * <p>Default strips {@code " ON UPDATE CASCADE"} (the PG/H2/HSQL target). Oracle overrides to strip
     * {@code DEFERRABLE INITIALLY DEFERRED} instead.
     */
    protected String legacyDdl(String ddl) {
        return ddl.replace("${schema}", "").replace(" ON UPDATE CASCADE", "");
    }

    /**
     * Hook: returns the {@link DatabaseMetaData#getImportedKeys getImportedKeys} value the dialect's FK is expected to
     * settle on after a successful migration.
     *
     * <p>Default {@link DatabaseMetaData#importedKeyCascade} (the {@code UPDATE_RULE} value used by PG/H2/HSQL). Oracle
     * overrides to {@link DatabaseMetaData#importedKeyInitiallyDeferred} (a {@code DEFERRABILITY} value).
     */
    protected short expectedMigratedFkState() {
        return (short) DatabaseMetaData.importedKeyCascade;
    }

    /**
     * Hook: extracts the dialect's relevant FK metadata column from the current {@code getImportedKeys} row.
     *
     * <p>Default reads {@code UPDATE_RULE}. Oracle overrides to read {@code DEFERRABILITY}.
     */
    protected short readFkState(ResultSet rs) throws SQLException {
        return rs.getShort("UPDATE_RULE");
    }

    /**
     * Hook: returns the {@code DROP TABLE} statement that succeeds in dropping a table with FK dependents.
     *
     * <p>Default is the standard {@code DROP TABLE x CASCADE}. Oracle overrides to {@code DROP TABLE x CASCADE
     * CONSTRAINTS}.
     */
    protected String dropTableSql(String table) {
        return "DROP TABLE " + table + " CASCADE";
    }

    private void dropIfExists(Statement st, String table) {
        try {
            st.execute(dropTableSql(table));
        } catch (SQLException ignored) {
            // table may not exist on the first run; the legacy CREATEs below recreate it
        }
    }

    @Test
    public void migrateRewritesTilepageForeignKey() throws SQLException {
        short before = requireTilepageFkState();
        assertNotEquals(
                "Legacy TILEPAGE FK should not yet be in its migrated state", expectedMigratedFkState(), before);

        dialect().migrateForeignKeys(null, new SimpleJdbcTemplate(dataSource()));

        short after = requireTilepageFkState();
        assertEquals(
                "Migration should rewrite the TILEPAGE FK to its current dialect shape",
                expectedMigratedFkState(),
                after);
    }

    /**
     * Simulates multiple JVMs starting at the same time against a shared database with the legacy FK still in place.
     * All threads call {@code migrateForeignKeys} concurrently; the migration must remain idempotent end-to-end - no
     * thread should propagate an exception, and the final FK state must be the migrated one.
     */
    @Test
    public void migrateIsConcurrentStartupSafe() throws Exception {
        int threads = 4;
        CyclicBarrier startGate = new CyclicBarrier(threads);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            Callable<Void> migrator = () -> {
                startGate.await();
                dialect().migrateForeignKeys(null, new SimpleJdbcTemplate(dataSource()));
                return null;
            };
            List<Future<Void>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(exec.submit(migrator));
            }
            List<Throwable> failures = new ArrayList<>();
            for (Future<Void> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            if (!failures.isEmpty()) {
                AssertionError ae = new AssertionError("Concurrent migrateForeignKeys threw on " + failures.size() + "/"
                        + threads + " threads; " + "see suppressed");
                failures.forEach(ae::addSuppressed);
                throw ae;
            }
        } finally {
            exec.shutdownNow();
            exec.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals(
                "After concurrent migration the FK should be in its migrated state",
                expectedMigratedFkState(),
                requireTilepageFkState());
    }

    @Test
    public void migrateIsIdempotent() throws SQLException {
        SimpleJdbcTemplate template = new SimpleJdbcTemplate(dataSource());
        dialect().migrateForeignKeys(null, template);
        assertEquals(expectedMigratedFkState(), requireTilepageFkState());

        // Second invocation must be a no-op (FK already in its migrated state).
        dialect().migrateForeignKeys(null, template);
        assertEquals(expectedMigratedFkState(), requireTilepageFkState());
    }

    private short requireTilepageFkState() throws SQLException {
        Short state = lookupTilepageFkState();
        assertNotNull("TILEPAGE -> TILESET foreign key not found in metadata", state);
        return state;
    }

    private Short lookupTilepageFkState() throws SQLException {
        try (Connection cx = dataSource().getConnection()) {
            DatabaseMetaData dbmd = cx.getMetaData();
            Short state = findTilesetFkState(dbmd, "tilepage");
            return state != null ? state : findTilesetFkState(dbmd, "TILEPAGE");
        }
    }

    private Short findTilesetFkState(DatabaseMetaData dbmd, String tableName) throws SQLException {
        try (ResultSet rs = dbmd.getImportedKeys(null, null, tableName)) {
            while (rs.next()) {
                String pkTable = rs.getString("PKTABLE_NAME");
                String fkColumn = rs.getString("FKCOLUMN_NAME");
                if ("TILESET".equalsIgnoreCase(pkTable) && "TILESET_ID".equalsIgnoreCase(fkColumn)) {
                    return readFkState(rs);
                }
            }
        }
        return null;
    }
}
