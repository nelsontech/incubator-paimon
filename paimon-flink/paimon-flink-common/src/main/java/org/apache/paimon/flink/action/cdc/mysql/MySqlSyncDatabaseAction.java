/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action.cdc.mysql;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.flink.FlinkConnectorOptions;
import org.apache.paimon.flink.action.Action;
import org.apache.paimon.flink.sink.cdc.EventParser;
import org.apache.paimon.flink.sink.cdc.FlinkCdcSyncDatabaseSinkBuilder;
import org.apache.paimon.options.CatalogOptions;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.utils.Preconditions;

import com.ververica.cdc.connectors.mysql.source.MySqlSource;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceOptions;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.utils.MultipleParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An {@link Action} which synchronize the whole MySQL database into one Paimon database.
 *
 * <p>You should specify MySQL source database in {@code mySqlConfig}. See <a
 * href="https://ververica.github.io/flink-cdc-connectors/master/content/connectors/mysql-cdc.html#connector-options">document
 * of flink-cdc-connectors</a> for detailed keys and values.
 *
 * <p>For each MySQL table to be synchronized, if the corresponding Paimon table does not exist,
 * this action will automatically create the table. Its schema will be derived from all specified
 * MySQL tables. If the Paimon table already exists, its schema will be compared against the schema
 * of all specified MySQL tables.
 *
 * <p>This action supports a limited number of schema changes. Unsupported schema changes will be
 * ignored. Currently supported schema changes includes:
 *
 * <ul>
 *   <li>Adding columns.
 *   <li>Altering column types. More specifically,
 *       <ul>
 *         <li>altering from a string type (char, varchar, text) to another string type with longer
 *             length,
 *         <li>altering from a binary type (binary, varbinary, blob) to another binary type with
 *             longer length,
 *         <li>altering from an integer type (tinyint, smallint, int, bigint) to another integer
 *             type with wider range,
 *         <li>altering from a floating-point type (float, double) to another floating-point type
 *             with wider range,
 *       </ul>
 *       are supported. Other type changes will cause exceptions.
 * </ul>
 *
 * <p>This action creates a Paimon table sink for each Paimon table to be written, so this action is
 * not very efficient in resource saving. We may optimize this action by merging all sinks into one
 * instance in the future.
 */
public class MySqlSyncDatabaseAction implements Action {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlSyncDatabaseAction.class);

    private final Configuration mySqlConfig;
    private final String warehouse;
    private final String database;
    private final boolean ignoreIncompatible;
    private final Map<String, String> catalogConfig;
    private final Map<String, String> tableConfig;

    MySqlSyncDatabaseAction(
            Map<String, String> mySqlConfig,
            String warehouse,
            String database,
            boolean ignoreIncompatible,
            Map<String, String> catalogConfig,
            Map<String, String> tableConfig) {
        this.mySqlConfig = Configuration.fromMap(mySqlConfig);
        this.warehouse = warehouse;
        this.database = database;
        this.ignoreIncompatible = ignoreIncompatible;
        this.catalogConfig = catalogConfig;
        this.tableConfig = tableConfig;
    }

    public void build(StreamExecutionEnvironment env) throws Exception {
        Preconditions.checkArgument(
                !mySqlConfig.contains(MySqlSourceOptions.TABLE_NAME),
                MySqlSourceOptions.TABLE_NAME.key()
                        + " cannot be set for mysql-sync-database. "
                        + "If you want to sync several MySQL tables into one Paimon table, "
                        + "use mysql-sync-table instead.");
        Catalog catalog =
                CatalogFactory.createCatalog(
                        CatalogContext.create(
                                new Options(catalogConfig)
                                        .set(CatalogOptions.WAREHOUSE, warehouse)));
        boolean caseSensitive = catalog.caseSensitive();

        if (!caseSensitive) {
            Preconditions.checkArgument(
                    database.equals(database.toLowerCase()),
                    String.format(
                            "Database name [%s] cannot contain upper case in case-insensitive catalog.",
                            database));
        }

        List<MySqlSchema> mySqlSchemas = getMySqlSchemaList(caseSensitive);
        Preconditions.checkArgument(
                mySqlSchemas.size() > 0,
                "No tables found in MySQL database "
                        + mySqlConfig.get(MySqlSourceOptions.DATABASE_NAME)
                        + ", or MySQL database does not exist.");

        catalog.createDatabase(database, true);

        List<FileStoreTable> fileStoreTables = new ArrayList<>();
        List<String> monitoredTables = new ArrayList<>();
        for (MySqlSchema mySqlSchema : mySqlSchemas) {
            Identifier identifier = new Identifier(database, mySqlSchema.tableName());
            FileStoreTable table;
            try {
                table = (FileStoreTable) catalog.getTable(identifier);
                if (shouldMonitorTable(table.schema(), mySqlSchema, identifier)) {
                    monitoredTables.add(mySqlSchema.originalTableName());
                }
            } catch (Catalog.TableNotExistException e) {
                Schema schema =
                        MySqlActionUtils.buildPaimonSchema(
                                mySqlSchema,
                                Collections.emptyList(),
                                Collections.emptyList(),
                                tableConfig);
                catalog.createTable(identifier, schema, false);
                table = (FileStoreTable) catalog.getTable(identifier);
                monitoredTables.add(mySqlSchema.originalTableName());
            }
            fileStoreTables.add(table);
        }

        Preconditions.checkState(
                !monitoredTables.isEmpty(),
                "No tables to be synchronized. Possible cause is the schemas of all tables in specified "
                        + "MySQL database are not compatible with those of existed Paimon tables. Please check the log.");

        mySqlConfig.set(
                MySqlSourceOptions.TABLE_NAME, "(" + String.join("|", monitoredTables) + ")");
        MySqlSource<String> source = MySqlActionUtils.buildMySqlSource(mySqlConfig);

        String serverTimeZone = mySqlConfig.get(MySqlSourceOptions.SERVER_TIME_ZONE);
        ZoneId zoneId = serverTimeZone == null ? ZoneId.systemDefault() : ZoneId.of(serverTimeZone);
        EventParser.Factory<String> parserFactory =
                () -> new MySqlDebeziumJsonEventParser(zoneId, caseSensitive);

        FlinkCdcSyncDatabaseSinkBuilder<String> sinkBuilder =
                new FlinkCdcSyncDatabaseSinkBuilder<String>()
                        .withInput(
                                env.fromSource(
                                        source, WatermarkStrategy.noWatermarks(), "MySQL Source"))
                        .withParserFactory(parserFactory)
                        .withTables(fileStoreTables);
        String sinkParallelism = tableConfig.get(FlinkConnectorOptions.SINK_PARALLELISM.key());
        if (sinkParallelism != null) {
            sinkBuilder.withParallelism(Integer.parseInt(sinkParallelism));
        }
        sinkBuilder.build();
    }

    private List<MySqlSchema> getMySqlSchemaList(boolean caseSensitive) throws Exception {
        String databaseName = mySqlConfig.get(MySqlSourceOptions.DATABASE_NAME);
        List<MySqlSchema> mySqlSchemaList = new ArrayList<>();
        try (Connection conn = MySqlActionUtils.getConnection(mySqlConfig)) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet tables =
                    metaData.getTables(databaseName, null, "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    MySqlSchema mySqlSchema =
                            new MySqlSchema(metaData, databaseName, tableName, caseSensitive);
                    if (mySqlSchema.primaryKeys().size() > 0) {
                        // only tables with primary keys will be considered
                        mySqlSchemaList.add(mySqlSchema);
                    }
                }
            }
        }
        return mySqlSchemaList;
    }

    private boolean shouldMonitorTable(
            TableSchema tableSchema, MySqlSchema mySqlSchema, Identifier identifier) {
        if (MySqlActionUtils.schemaCompatible(tableSchema, mySqlSchema)) {
            return true;
        } else if (ignoreIncompatible) {
            LOG.warn(
                    "Incompatible schema found. This table will be ignored.\n"
                            + "Paimon table is: {}, fields are: {}.\n"
                            + "MySQL table is: {}.{}, fields are: {}.",
                    identifier.getFullName(),
                    tableSchema.fields(),
                    mySqlSchema.databaseName(),
                    mySqlSchema.originalTableName(),
                    mySqlSchema.fields());
            return false;
        } else {
            throw new IllegalArgumentException(
                    String.format(
                            "Incompatible schema found.\n"
                                    + "Paimon table is: %s, fields are: %s.\n"
                                    + "MySQL table is: %s.%s, fields are: %s.\n"
                                    + "If you want to ignore the incompatible tables, "
                                    + "please specify --ignore-incompatible to true.",
                            identifier.getFullName(),
                            tableSchema.fields(),
                            mySqlSchema.databaseName(),
                            mySqlSchema.originalTableName(),
                            mySqlSchema.fields()));
        }
    }

    // ------------------------------------------------------------------------
    //  Flink run methods
    // ------------------------------------------------------------------------

    public static Optional<Action> create(String[] args) {
        MultipleParameterTool params = MultipleParameterTool.fromArgs(args);

        if (params.has("help")) {
            printHelp();
            return Optional.empty();
        }

        String warehouse = params.get("warehouse");
        String database = params.get("database");
        boolean ignoreIncompatible = Boolean.parseBoolean(params.get("ignore-incompatible"));

        Map<String, String> mySqlConfig = getConfigMap(params, "mysql-conf");
        Map<String, String> catalogConfig = getConfigMap(params, "catalog-conf");
        Map<String, String> tableConfig = getConfigMap(params, "table-conf");
        if (mySqlConfig == null) {
            return Optional.empty();
        }

        return Optional.of(
                new MySqlSyncDatabaseAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        ignoreIncompatible,
                        catalogConfig == null ? Collections.emptyMap() : catalogConfig,
                        tableConfig == null ? Collections.emptyMap() : tableConfig));
    }

    private static Map<String, String> getConfigMap(MultipleParameterTool params, String key) {
        if (!params.has(key)) {
            return null;
        }

        Map<String, String> map = new HashMap<>();
        for (String param : params.getMultiParameter(key)) {
            String[] kv = param.split("=");
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
                continue;
            }

            System.err.println(
                    "Invalid " + key + " " + param + ".\nRun mysql-sync-database --help for help.");
            return null;
        }
        return map;
    }

    private static void printHelp() {
        System.out.println(
                "Action \"mysql-sync-database\" creates a streaming job "
                        + "with a Flink MySQL CDC source and multiple Paimon table sinks "
                        + "to synchronize a whole MySQL database into one Paimon database.\n"
                        + "Only MySQL tables with primary keys will be considered. "
                        + "Newly created MySQL tables after the job starts will not be included.");
        System.out.println();

        System.out.println("Syntax:");
        System.out.println(
                "  mysql-sync-database --warehouse <warehouse-path> --database <database-name> "
                        + "[--ignore-incompatible <true/false>]"
                        + "[--mysql-conf <mysql-cdc-source-conf> [--mysql-conf <mysql-cdc-source-conf> ...]] "
                        + "[--catalog-conf <paimon-catalog-conf> [--catalog-conf <paimon-catalog-conf> ...]] "
                        + "[--table-conf <paimon-table-sink-conf> [--table-conf <paimon-table-sink-conf> ...]]");
        System.out.println();

        System.out.println(
                "--ignore-incompatible is default false, in this case, if MySQL table name exists in Paimon "
                        + "and their schema is incompatible, an exception will be thrown. "
                        + "You can specify it to true explicitly to ignore the incompatible tables and exception.");

        System.out.println("MySQL CDC source conf syntax:");
        System.out.println("  key=value");
        System.out.println(
                "'hostname', 'username', 'password' and 'database-name' "
                        + "are required configurations, others are optional. "
                        + "Note that 'database-name' should be the exact name "
                        + "of the MySQL databse you want to synchronize. "
                        + "It can't be a regular expression.");
        System.out.println(
                "For a complete list of supported configurations, "
                        + "see https://ververica.github.io/flink-cdc-connectors/master/content/connectors/mysql-cdc.html#connector-options");
        System.out.println();

        System.out.println("Paimon catalog and table sink conf syntax:");
        System.out.println("  key=value");
        System.out.println("All Paimon sink table will be applied the same set of configurations.");
        System.out.println(
                "For a complete list of supported configurations, "
                        + "see https://paimon.apache.org/docs/master/maintenance/configurations/");
        System.out.println();

        System.out.println("Examples:");
        System.out.println(
                "  mysql-sync-database \\\n"
                        + "    --warehouse hdfs:///path/to/warehouse \\\n"
                        + "    --database test_db \\\n"
                        + "    --mysql-conf hostname=127.0.0.1 \\\n"
                        + "    --mysql-conf username=root \\\n"
                        + "    --mysql-conf password=123456 \\\n"
                        + "    --mysql-conf database-name=source_db \\\n"
                        + "    --catalog-conf metastore=hive \\\n"
                        + "    --catalog-conf uri=thrift://hive-metastore:9083 \\\n"
                        + "    --table-conf bucket=4 \\\n"
                        + "    --table-conf changelog-producer=input \\\n"
                        + "    --table-conf sink.parallelism=4");
    }

    @Override
    public void run() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        build(env);
        env.execute(String.format("MySQL-Paimon Database Sync: %s", database));
    }
}
