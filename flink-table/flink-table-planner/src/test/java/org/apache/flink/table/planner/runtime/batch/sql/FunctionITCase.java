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

package org.apache.flink.table.planner.runtime.batch.sql;

import org.apache.flink.core.fs.Path;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.planner.factories.utils.TestCollectionTableFactory;
import org.apache.flink.table.planner.runtime.utils.BatchTestBase;
import org.apache.flink.types.Row;
import org.apache.flink.util.CollectionUtil;
import org.apache.flink.util.UserClassLoaderJarTestUtils;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.apache.flink.table.utils.UserDefinedFunctions.GENERATED_LOWER_UDF_CLASS;
import static org.apache.flink.table.utils.UserDefinedFunctions.GENERATED_LOWER_UDF_CODE;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for catalog and system functions in a table environment. */
public class FunctionITCase extends BatchTestBase {

    private static final Random random = new Random();
    private String udfClassName;
    private String jarPath;

    @Before
    @Override
    public void before() throws Exception {
        super.before();
        udfClassName = GENERATED_LOWER_UDF_CLASS + random.nextInt(50);
        jarPath =
                UserClassLoaderJarTestUtils.createJarFile(
                                TEMPORARY_FOLDER.newFolder(
                                        String.format("test-jar-%s", UUID.randomUUID())),
                                "test-classloader-udf.jar",
                                udfClassName,
                                String.format(GENERATED_LOWER_UDF_CODE, udfClassName))
                        .toURI()
                        .toString();
    }

    @Test
    public void testCreateTemporarySystemFunctionByUsingJar() {
        String ddl1 =
                String.format(
                        "CREATE TEMPORARY SYSTEM FUNCTION f10 AS '%s' USING JAR '%s'",
                        udfClassName, jarPath);
        String ddl2 =
                String.format(
                        "CREATE TEMPORARY SYSTEM FUNCTION f11 AS '%s' USING JAR '%s'",
                        udfClassName, jarPath);
        tEnv().executeSql(ddl1);
        tEnv().executeSql(ddl2);

        List<String> functions = Arrays.asList(tEnv().listFunctions());
        assertThat(functions).contains("f10");
        assertThat(functions).contains("f11");

        tEnv().executeSql("DROP TEMPORARY SYSTEM FUNCTION f10");
        tEnv().executeSql("DROP TEMPORARY SYSTEM FUNCTION f11");

        functions = Arrays.asList(tEnv().listFunctions());
        assertThat(functions).doesNotContain("f10");
        assertThat(functions).doesNotContain("f11");
    }

    @Test
    public void testCreateCatalogFunctionByUsingJar() {
        String ddl =
                String.format(
                        "CREATE FUNCTION default_database.f11 AS '%s' USING JAR '%s'",
                        udfClassName, jarPath);
        tEnv().executeSql(ddl);
        assertThat(Arrays.asList(tEnv().listFunctions())).contains("f11");

        tEnv().executeSql("DROP FUNCTION default_database.f11");
        assertThat(Arrays.asList(tEnv().listFunctions())).doesNotContain("f11");
    }

    @Test
    public void testCreateTemporaryCatalogFunctionByUsingJar() {
        String ddl =
                String.format(
                        "CREATE TEMPORARY FUNCTION default_database.f12 AS '%s' USING JAR '%s'",
                        udfClassName, jarPath);
        tEnv().executeSql(ddl);
        assertThat(Arrays.asList(tEnv().listFunctions())).contains("f12");

        tEnv().executeSql("DROP TEMPORARY FUNCTION default_database.f12");
        assertThat(Arrays.asList(tEnv().listFunctions())).doesNotContain("f12");
    }

    @Test
    public void testUserDefinedTemporarySystemFunctionByUsingJar() throws Exception {
        String functionDDL =
                String.format(
                        "create temporary system function lowerUdf as '%s' using jar '%s'",
                        udfClassName, jarPath);

        String dropFunctionDDL = "drop temporary system function lowerUdf";
        testUserDefinedFunctionByUsingJar(functionDDL, dropFunctionDDL);
    }

    @Test
    public void testUserDefinedRegularCatalogFunctionByUsingJar() throws Exception {
        String functionDDL =
                String.format(
                        "create function lowerUdf as '%s' using jar '%s'", udfClassName, jarPath);

        String dropFunctionDDL = "drop function lowerUdf";
        testUserDefinedFunctionByUsingJar(functionDDL, dropFunctionDDL);
    }

    @Test
    public void testUserDefinedTemporaryCatalogFunctionByUsingJar() throws Exception {
        String functionDDL =
                String.format(
                        "create temporary function lowerUdf as '%s' using jar '%s'",
                        udfClassName, jarPath);

        String dropFunctionDDL = "drop temporary function lowerUdf";
        testUserDefinedFunctionByUsingJar(functionDDL, dropFunctionDDL);
    }

    @Test
    public void testUsingAddJar() throws Exception {
        tEnv().executeSql(String.format("ADD JAR '%s'", jarPath));

        TableResult tableResult = tEnv().executeSql("SHOW JARS");
        assertThat(CollectionUtil.iteratorToList(tableResult.collect()))
                .isEqualTo(Collections.singletonList(Row.of(new Path(jarPath).getPath())));

        testUserDefinedFunctionByUsingJar(
                String.format("create function lowerUdf as '%s' LANGUAGE JAVA", udfClassName),
                "drop function lowerUdf");
    }

    private void testUserDefinedFunctionByUsingJar(String createFunctionDDL, String dropFunctionDDL)
            throws Exception {
        List<Row> sourceData =
                Arrays.asList(
                        Row.of(1, "JARK"),
                        Row.of(2, "RON"),
                        Row.of(3, "LeoNard"),
                        Row.of(1, "FLINK"),
                        Row.of(2, "CDC"));

        TestCollectionTableFactory.reset();
        TestCollectionTableFactory.initData(sourceData);

        String sourceDDL = "create table t1(a int, b varchar) with ('connector' = 'COLLECTION')";
        String sinkDDL = "create table t2(a int, b varchar) with ('connector' = 'COLLECTION')";

        String query = "select a, lowerUdf(b) from t1";

        tEnv().executeSql(sourceDDL);
        tEnv().executeSql(sinkDDL);
        tEnv().executeSql(createFunctionDDL);
        Table t2 = tEnv().sqlQuery(query);
        t2.executeInsert("t2").await();

        List<Row> result = TestCollectionTableFactory.RESULT();
        List<Row> expected =
                Arrays.asList(
                        Row.of(1, "jark"),
                        Row.of(2, "ron"),
                        Row.of(3, "leonard"),
                        Row.of(1, "flink"),
                        Row.of(2, "cdc"));
        assertThat(result).isEqualTo(expected);

        tEnv().executeSql("drop table t1");
        tEnv().executeSql("drop table t2");

        // delete the function
        tEnv().executeSql(dropFunctionDDL);
    }
}
