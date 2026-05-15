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
package org.geowebcache.diskquota.jdbc.tests.container;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.geowebcache.diskquota.jdbc.OracleDialect;
import org.geowebcache.diskquota.jdbc.SQLDialect;
import org.geowebcache.testcontainers.jdbc.OracleXEContainer;
import org.junit.ClassRule;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Runs the full {@code JDBCQuotaStoreTest} suite against a real Oracle Express Edition via Testcontainers.
 *
 * <p>If Docker is unavailable the class is skipped cleanly through {@link OracleXEContainer#disabledWithoutDocker()}.
 *
 * <p>Oracle XE's SERIALIZABLE snapshot machinery aborts with <a
 * href="https://docs.oracle.com/error-help/db/ora-08176/">ORA-08176</a> when a transaction's first read crosses
 * recently created DDL (the quota store creates indexes on TILEPAGE at startup). The
 * {@code JDBCQuotaStore.executeWithRetry(...)} bounded retry resolves it on a re-attempt with a fresh SCN, which is the
 * Oracle-recommended remedy.
 */
public class OracleQuotaStoreIT extends AbstractJDBCQuotaStoreIT {

    @ClassRule
    public static final OracleXEContainer ORACLE = OracleXEContainer.latest().disabledWithoutDocker();

    @Override
    protected SQLDialect getDialect() {
        return new OracleDialect();
    }

    @Override
    protected String getFixtureId() {
        return "oracle-testcontainer";
    }

    @Override
    protected JdbcDatabaseContainer<?> getContainer() {
        return ORACLE;
    }

    /**
     * Oracle requires {@code CASCADE CONSTRAINTS}, not just {@code CASCADE}, to drop tables with dependents; the base
     * cleanup uses the standard SQL form which silently fails on Oracle.
     */
    @Override
    protected void cleanupDatabase(DataSource dataSource) throws SQLException {
        try (Connection cx = dataSource.getConnection();
                Statement st = cx.createStatement()) {
            try {
                st.execute("DROP TABLE TILEPAGE CASCADE CONSTRAINTS");
            } catch (Exception e) {
                // fine, table may not exist
            }
            try {
                st.execute("DROP TABLE TILESET CASCADE CONSTRAINTS");
            } catch (Exception e) {
                // fine too
            }
        }
    }
}
