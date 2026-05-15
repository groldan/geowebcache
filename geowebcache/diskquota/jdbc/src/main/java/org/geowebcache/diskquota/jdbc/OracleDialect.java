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
 * @author Andrea Aime - GeoSolutions Copyright 2012
 */
package org.geowebcache.diskquota.jdbc;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Oracle dialect for the quota store, compared to the basic one creates index organized tables and uses the Oracle
 * specific syntax to get the pages with the oldest access time and lowest access frequency
 *
 * @author Andrea Aime - GeoSolutions
 */
public class OracleDialect extends SQLDialect {

    // Oracle's Number type has a maximum precision of 38
    static final int MAX_NUMBER_PRECISION = 38;

    static int numberPrecision(int n) {
        return Math.min(n, MAX_NUMBER_PRECISION);
    }

    public OracleDialect() {
        TABLE_CREATION_MAP.put(
                "TILESET",
                List.of( //
                        """
                        CREATE TABLE ${schema}TILESET (
                          KEY VARCHAR(%d) PRIMARY KEY,
                          LAYER_NAME VARCHAR(%d),
                          GRIDSET_ID VARCHAR(%d),
                          BLOB_FORMAT VARCHAR(%d),
                          PARAMETERS_ID VARCHAR(%d),
                          BYTES NUMBER(%d) DEFAULT 0 NOT NULL
                        ) ORGANIZATION INDEX
                        """
                                .formatted(
                                        TILESET_KEY_SIZE,
                                        LAYER_NAME_SIZE,
                                        GRIDSET_ID_SIZE,
                                        BLOB_FORMAT_SIZE,
                                        PARAMETERS_ID_SIZE,
                                        numberPrecision(BYTES_SIZE)), //
                        "CREATE INDEX TILESET_LAYER ON TILESET(LAYER_NAME)" //
                        ));

        TABLE_CREATION_MAP.put(
                "TILEPAGE",
                List.of(
                        """
                        CREATE TABLE ${schema}TILEPAGE (
                         KEY VARCHAR(%d) PRIMARY KEY,
                         TILESET_ID VARCHAR(%d) REFERENCES ${schema}TILESET(KEY) ON DELETE CASCADE
                           DEFERRABLE INITIALLY DEFERRED,
                         PAGE_Z SMALLINT,
                         PAGE_X INTEGER,
                         PAGE_Y INTEGER,
                         CREATION_TIME_MINUTES INTEGER,
                         FREQUENCY_OF_USE FLOAT,
                         LAST_ACCESS_TIME_MINUTES INTEGER,
                         FILL_FACTOR FLOAT,
                         NUM_HITS NUMBER(%d)
                        ) ORGANIZATION INDEX
                        """
                                .formatted(TILEPAGE_KEY_SIZE, TILESET_KEY_SIZE, numberPrecision(NUM_HITS_SIZE)), //
                        "CREATE INDEX TILEPAGE_TILESET ON TILEPAGE(TILESET_ID)",
                        "CREATE INDEX TILEPAGE_FILL_FACTOR ON TILEPAGE(FILL_FACTOR)",
                        "CREATE INDEX TILEPAGE_FREQUENCY ON TILEPAGE(FREQUENCY_OF_USE DESC)",
                        "CREATE INDEX TILEPAGE_LAST_ACCESS ON TILEPAGE(LAST_ACCESS_TIME_MINUTES DESC)"));
    }

    @Override
    protected void addEmtpyTableReference(StringBuilder sb) {
        sb.append("FROM DUAL");
    }

    /**
     * Oracle does not support {@code ON UPDATE CASCADE} on foreign keys, so its TILEPAGE -> TILESET FK is migrated to
     * {@code DEFERRABLE INITIALLY DEFERRED} instead. Deferring the FK check to commit time both lets
     * {@link #getRenameLayerStatement(String, String, String) renaming a layer} rewrite TILESET.KEY (and its dependent
     * TILEPAGE.TILESET_ID rows) atomically, and removes the snapshot read against TILESET that fires on every plain
     * INSERT INTO TILEPAGE - the primary trigger for ORA-08176 under SERIALIZABLE.
     */
    @Override
    protected boolean tilepageFkIsMigrated(ResultSet rs) throws SQLException {
        return rs.getShort("DEFERRABILITY") == DatabaseMetaData.importedKeyInitiallyDeferred;
    }

    @Override
    protected String tilepageFkAddSql(String prefixedTilepageName, String prefix) {
        return """
                ALTER TABLE %s ADD FOREIGN KEY (TILESET_ID)
                REFERENCES %sTILESET(KEY)
                ON DELETE CASCADE
                DEFERRABLE INITIALLY DEFERRED
                """
                .formatted(prefixedTilepageName, prefix);
    }

    /**
     * Oracle does not support {@code ON UPDATE CASCADE} on foreign keys, so the rename cannot rely on the FK to
     * propagate the new {@code TILESET.KEY} prefix to {@code TILEPAGE.TILESET_ID}. Instead, this returns a PL/SQL
     * anonymous block that rewrites both columns within a single transaction; the {@code TILEPAGE -> TILESET} FK is
     * declared {@code DEFERRABLE INITIALLY DEFERRED} so the constraint is verified once at commit, with both updates
     * already in place.
     *
     * <p>The block uses Oracle's {@code SUBSTR}/{@code INSTR}/{@code ||} idiom rather than SQL-standard
     * {@code SUBSTRING ... FROM POSITION(...)} which Oracle does not provide.
     */
    @Override
    public String getRenameLayerStatement(String schema, String oldLayerName, String newLayerName) {
        String prefix = schema == null ? "" : schema + ".";
        return """
                BEGIN
                  UPDATE %sTILESET
                    SET KEY = :%s || SUBSTR(KEY, INSTR(KEY, '#')),
                        LAYER_NAME = :%s
                    WHERE LAYER_NAME = :%s;
                  UPDATE %sTILEPAGE
                    SET TILESET_ID = :%s || SUBSTR(TILESET_ID, INSTR(TILESET_ID, '#'))
                    WHERE TILESET_ID LIKE :%s || '#%%';
                END;
                """
                .formatted(prefix, newLayerName, newLayerName, oldLayerName, prefix, newLayerName, oldLayerName);
    }

    @Override
    public String getLeastFrequentlyUsedPage(String schema, List<String> layerParamNames) {
        StringBuilder sb = new StringBuilder("SELECT * FROM (");
        sb.append("SELECT TILESET_ID, PAGE_X, PAGE_Y, PAGE_Z, CREATION_TIME_MINUTES FROM ");
        if (schema != null) {
            sb.append(schema).append(".");
        }
        sb.append("TILEPAGE WHERE FILL_FACTOR > 0 ");
        sb.append("AND TILESET_ID IN (");
        sb.append("SELECT KEY FROM ");
        if (schema != null) {
            sb.append(schema).append(".");
        }
        sb.append("TILESET WHERE LAYER_NAME IN (");
        for (int i = 0; i < layerParamNames.size(); i++) {
            sb.append(":" + layerParamNames.get(i));
            if (i < layerParamNames.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(")) ORDER BY FREQUENCY_OF_USE ASC");
        sb.append(") WHERE ROWNUM <= 1");

        return sb.toString();
    }

    @Override
    public String getLeastRecentlyUsedPage(String schema, List<String> layerParamNames) {
        StringBuilder sb = new StringBuilder("SELECT * FROM (");
        sb.append("SELECT TILESET_ID, PAGE_X, PAGE_Y, PAGE_Z, CREATION_TIME_MINUTES FROM ");
        if (schema != null) {
            sb.append(schema).append(".");
        }
        sb.append("TILEPAGE WHERE FILL_FACTOR > 0 ");
        sb.append("AND TILESET_ID IN (");
        sb.append("SELECT KEY FROM ");
        if (schema != null) {
            sb.append(schema).append(".");
        }
        sb.append("TILESET WHERE LAYER_NAME IN (");
        for (int i = 0; i < layerParamNames.size(); i++) {
            sb.append(":" + layerParamNames.get(i));
            if (i < layerParamNames.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(")) ORDER BY LAST_ACCESS_TIME_MINUTES ASC");
        sb.append(") WHERE ROWNUM <= 1");

        return sb.toString();
    }
}
