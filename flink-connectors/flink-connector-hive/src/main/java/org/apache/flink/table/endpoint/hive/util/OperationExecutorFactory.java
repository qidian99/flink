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

package org.apache.flink.table.endpoint.hive.util;

import org.apache.flink.table.catalog.CatalogBaseTable.TableKind;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.gateway.api.SqlGatewayService;
import org.apache.flink.table.gateway.api.results.ResultSet;
import org.apache.flink.table.gateway.api.results.TableInfo;
import org.apache.flink.table.gateway.api.session.SessionHandle;

import org.apache.hadoop.hive.serde2.thrift.Type;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.flink.table.endpoint.hive.HiveServer2Schemas.GET_CATALOGS_SCHEMA;
import static org.apache.flink.table.endpoint.hive.HiveServer2Schemas.GET_SCHEMAS_SCHEMA;
import static org.apache.flink.table.endpoint.hive.HiveServer2Schemas.GET_TABLES_SCHEMA;
import static org.apache.flink.table.endpoint.hive.HiveServer2Schemas.GET_TYPE_INFO_SCHEMA;
import static org.apache.flink.table.gateway.api.results.ResultSet.ResultType.EOS;
import static org.apache.hadoop.hive.serde2.thrift.Type.ARRAY_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.BIGINT_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.BINARY_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.BOOLEAN_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.CHAR_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.DATE_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.DECIMAL_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.DOUBLE_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.FLOAT_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.INTERVAL_DAY_TIME_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.INTERVAL_YEAR_MONTH_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.INT_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.MAP_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.NULL_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.SMALLINT_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.STRING_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.STRUCT_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.TIMESTAMP_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.TINYINT_TYPE;
import static org.apache.hadoop.hive.serde2.thrift.Type.VARCHAR_TYPE;

/** Factory to create the operation executor. */
public class OperationExecutorFactory {

    public static Callable<ResultSet> createGetCatalogsExecutor(
            SqlGatewayService service, SessionHandle sessionHandle) {
        return () -> executeGetCatalogs(service, sessionHandle);
    }

    public static Callable<ResultSet> createGetSchemasExecutor(
            SqlGatewayService service,
            SessionHandle sessionHandle,
            @Nullable String catalogName,
            @Nullable String schemaName) {
        return () -> executeGetSchemas(service, sessionHandle, catalogName, schemaName);
    }

    public static Callable<ResultSet> createGetTablesExecutor(
            SqlGatewayService service,
            SessionHandle sessionHandle,
            @Nullable String catalogName,
            @Nullable String schemaName,
            @Nullable String tableName,
            Set<TableKind> tableKinds) {
        return () ->
                executeGetTables(
                        service, sessionHandle, catalogName, schemaName, tableName, tableKinds);
    }

    public static Callable<ResultSet> createGetTableInfoExecutor() {
        return () ->
                new ResultSet(
                        EOS,
                        null,
                        GET_TYPE_INFO_SCHEMA,
                        getSupportedHiveType().stream()
                                .map(
                                        type ->
                                                wrap(
                                                        type.getName(), // TYPE_NAME
                                                        type.toJavaSQLType(), // DATA_TYPE
                                                        type.getMaxPrecision(), // PRECISION
                                                        type.getLiteralPrefix(), // LITERAL_PREFIX
                                                        type.getLiteralSuffix(), // LITERAL_SUFFIX
                                                        type.getCreateParams(), // CREATE_PARAMS
                                                        type.getNullable(), // NULLABLE
                                                        type.isCaseSensitive(), // CASE_SENSITIVE
                                                        type.getSearchable(), // SEARCHABLE
                                                        type
                                                                .isUnsignedAttribute(), // UNSIGNED_ATTRIBUTE
                                                        type.isFixedPrecScale(), // FIXED_PREC_SCALE
                                                        type.isAutoIncrement(), // AUTO_INCREMENT
                                                        type.getLocalizedName(), // LOCAL_TYPE_NAME
                                                        type.getMinimumScale(), // MINIMUM_SCALE
                                                        type.getMaximumScale(), // MAXIMUM_SCALE
                                                        null, // SQL_DATA_TYPE, unused
                                                        null, // SQL_DATETIME_SUB, unused
                                                        type.getNumPrecRadix() // NUM_PREC_RADIX
                                                        ))
                                .collect(Collectors.toList()));
    }

    // --------------------------------------------------------------------------------------------
    // Executors
    // --------------------------------------------------------------------------------------------

    private static ResultSet executeGetCatalogs(
            SqlGatewayService service, SessionHandle sessionHandle) {
        Set<String> catalogNames = service.listCatalogs(sessionHandle);
        return new ResultSet(
                EOS,
                null,
                GET_CATALOGS_SCHEMA,
                catalogNames.stream()
                        .map(OperationExecutorFactory::wrap)
                        .collect(Collectors.toList()));
    }

    private static ResultSet executeGetSchemas(
            SqlGatewayService service,
            SessionHandle sessionHandle,
            @Nullable String catalogName,
            @Nullable String schemaName) {
        String specifiedCatalogName =
                isNullOrEmpty(catalogName) ? service.getCurrentCatalog(sessionHandle) : catalogName;
        Set<String> databaseNames =
                filter(service.listDatabases(sessionHandle, specifiedCatalogName), schemaName);
        return new ResultSet(
                EOS,
                null,
                GET_SCHEMAS_SCHEMA,
                databaseNames.stream()
                        .map(name -> wrap(name, specifiedCatalogName))
                        .collect(Collectors.toList()));
    }

    private static ResultSet executeGetTables(
            SqlGatewayService service,
            SessionHandle sessionHandle,
            @Nullable String catalogName,
            @Nullable String schemaName,
            @Nullable String tableName,
            Set<TableKind> tableKinds) {
        Set<TableInfo> tableInfos = new HashSet<>();
        String specifiedCatalogName =
                isNullOrEmpty(catalogName) ? service.getCurrentCatalog(sessionHandle) : catalogName;
        for (String schema :
                filter(service.listDatabases(sessionHandle, specifiedCatalogName), schemaName)) {
            tableInfos.addAll(
                    filter(
                            service.listTables(
                                    sessionHandle, specifiedCatalogName, schema, tableKinds),
                            candidate -> candidate.getIdentifier().getObjectName(),
                            tableName));
        }
        return new ResultSet(
                EOS,
                null,
                GET_TABLES_SCHEMA,
                tableInfos.stream()
                        .map(
                                info ->
                                        wrap(
                                                info.getIdentifier().getCatalogName(),
                                                info.getIdentifier().getDatabaseName(),
                                                info.getIdentifier().getObjectName(),
                                                info.getTableKind().name(),
                                                // It requires to load the CatalogFunction from the
                                                // remote server, which is time wasted.
                                                "",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                        .collect(Collectors.toList()));
    }

    // --------------------------------------------------------------------------------------------
    // Utilities
    // --------------------------------------------------------------------------------------------

    private static boolean isNullOrEmpty(@Nullable String input) {
        return input == null || input.isEmpty();
    }

    private static Set<String> filter(Set<String> candidates, @Nullable String pattern) {
        return filter(candidates, Function.identity(), pattern);
    }

    private static <T> Set<T> filter(
            Set<T> candidates, Function<T, String> featureGetter, @Nullable String pattern) {
        Pattern compiledPattern = convertNamePattern(pattern);
        return candidates.stream()
                .filter(
                        candidate ->
                                compiledPattern.matcher(featureGetter.apply(candidate)).matches())
                .collect(Collectors.toSet());
    }

    /**
     * Covert SQL 'like' pattern to a Java regular expression. Underscores (_) are converted to '.'
     * and percent signs (%) are converted to '.*'. Note: escape characters are removed.
     *
     * @param pattern the SQL pattern to convert.
     * @return the equivalent Java regular expression of the pattern.
     */
    private static Pattern convertNamePattern(@Nullable String pattern) {
        if ((pattern == null) || pattern.isEmpty()) {
            pattern = "%";
        }
        String wStr = ".*";
        return Pattern.compile(
                pattern.replaceAll("([^\\\\])%", "$1" + wStr)
                        .replaceAll("\\\\%", "%")
                        .replaceAll("^%", wStr)
                        .replaceAll("([^\\\\])_", "$1.")
                        .replaceAll("\\\\_", "_")
                        .replaceAll("^_", "."));
    }

    private static GenericRowData wrap(Object... elements) {
        Object[] pack = new Object[elements.length];
        for (int i = 0; i < elements.length; i++) {
            Object element = elements[i];
            if (element != null) {
                if (element instanceof String) {
                    pack[i] = StringData.fromString((String) element);
                } else if (element instanceof Integer) {
                    pack[i] = element;
                } else if (element instanceof Short) {
                    pack[i] = element;
                } else if (element instanceof Boolean) {
                    pack[i] = element;
                } else {
                    throw new UnsupportedOperationException(
                            String.format(
                                    "Can not wrap the element %s at index %s into RowData.",
                                    element, i));
                }
            }
        }
        return GenericRowData.of(pack);
    }

    private static List<Type> getSupportedHiveType() {
        return Collections.unmodifiableList(
                Arrays.asList(
                        NULL_TYPE,
                        BOOLEAN_TYPE,
                        STRING_TYPE,
                        BINARY_TYPE,
                        TINYINT_TYPE,
                        SMALLINT_TYPE,
                        INT_TYPE,
                        BIGINT_TYPE,
                        FLOAT_TYPE,
                        DOUBLE_TYPE,
                        DECIMAL_TYPE,
                        DATE_TYPE,
                        TIMESTAMP_TYPE,
                        ARRAY_TYPE,
                        MAP_TYPE,
                        STRUCT_TYPE,
                        CHAR_TYPE,
                        VARCHAR_TYPE,
                        INTERVAL_YEAR_MONTH_TYPE,
                        INTERVAL_DAY_TIME_TYPE));
    }
}
