/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.plugin.jdbc;

import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SchemaTablePrefix;
import com.facebook.presto.spi.TableNotFoundException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.facebook.airlift.concurrent.Threads.daemonThreadsNamed;
import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.VarcharType.VARCHAR;
import static com.facebook.presto.common.type.VarcharType.createVarcharType;
import static com.facebook.presto.plugin.jdbc.TestingDatabase.CONNECTOR_ID;
import static com.facebook.presto.plugin.jdbc.TestingJdbcTypeHandle.JDBC_BIGINT;
import static com.facebook.presto.plugin.jdbc.TestingJdbcTypeHandle.JDBC_VARCHAR;
import static com.facebook.presto.spi.StandardErrorCode.NOT_FOUND;
import static com.facebook.presto.spi.StandardErrorCode.PERMISSION_DENIED;
import static com.facebook.presto.testing.TestingConnectorSession.SESSION;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded = true)
public class TestJdbcMetadata
{
    private TestingDatabase database;
    private JdbcMetadata metadata;
    private JdbcMetadataCache jdbcMetadataCache;
    private JdbcTableHandle tableHandle;

    @BeforeMethod
    public void setUp()
            throws Exception
    {
        database = new TestingDatabase();
        ListeningExecutorService executor = listeningDecorator(newCachedThreadPool(daemonThreadsNamed("test-%s")));
        jdbcMetadataCache = new JdbcMetadataCache(executor, database.getJdbcClient(), new JdbcMetadataCacheStats(), OptionalLong.of(0), OptionalLong.of(0), 100);

        BaseJdbcConfig baseConfig = new BaseJdbcConfig();
        baseConfig.setConnectionUrl("jdbc:h2:mem:test");

        metadata = new JdbcMetadata(jdbcMetadataCache, database.getJdbcClient(), false, new DefaultTableLocationProvider(baseConfig));
        tableHandle = metadata.getTableHandle(SESSION, new SchemaTableName("example", "numbers"));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        database.close();
    }

    @Test
    public void testListSchemaNames()
    {
        assertTrue(metadata.listSchemaNames(SESSION).containsAll(ImmutableSet.of("example", "tpch")));
    }

    @Test
    public void testGetTableHandle()
    {
        JdbcTableHandle tableHandle = metadata.getTableHandle(SESSION, new SchemaTableName("example", "numbers"));
        assertEquals(metadata.getTableHandle(SESSION, new SchemaTableName("example", "numbers")), tableHandle);
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("example", "unknown")));
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("unknown", "numbers")));
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName("unknown", "unknown")));
    }

    @Test
    public void testGetColumnHandles()
    {
        // known table
        assertEquals(metadata.getColumnHandles(SESSION, tableHandle), ImmutableMap.of(
                "text", new JdbcColumnHandle(CONNECTOR_ID, "TEXT", JDBC_VARCHAR, VARCHAR, true, Optional.empty()),
                "text_short", new JdbcColumnHandle(CONNECTOR_ID, "TEXT_SHORT", JDBC_VARCHAR, createVarcharType(32), true, Optional.empty()),
                "value", new JdbcColumnHandle(CONNECTOR_ID, "VALUE", JDBC_BIGINT, BIGINT, true, Optional.empty())));

        // unknown table
        unknownTableColumnHandle(new JdbcTableHandle(CONNECTOR_ID, new SchemaTableName("unknown", "unknown"), "unknown", "unknown", "unknown"));
        unknownTableColumnHandle(new JdbcTableHandle(CONNECTOR_ID, new SchemaTableName("example", "numbers"), null, "example", "unknown"));
    }

    private void unknownTableColumnHandle(JdbcTableHandle tableHandle)
    {
        try {
            metadata.getColumnHandles(SESSION, tableHandle);
            fail("Expected getColumnHandle of unknown table to throw a TableNotFoundException");
        }
        catch (TableNotFoundException ignored) {
        }
    }

    @Test
    public void getTableMetadata()
    {
        // known table
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(SESSION, tableHandle);
        assertEquals(tableMetadata.getTable(), new SchemaTableName("example", "numbers"));
        assertEquals(tableMetadata.getColumns(), ImmutableList.of(
                ColumnMetadata.builder().setName("text").setType(VARCHAR).setNullable(false).build(),
                ColumnMetadata.builder().setName("text_short").setType(createVarcharType(32)).build(),
                ColumnMetadata.builder().setName("value").setType(BIGINT).build()));

        // escaping name patterns
        JdbcTableHandle specialTableHandle = metadata.getTableHandle(SESSION, new SchemaTableName("exa_ple", "num_ers"));
        ConnectorTableMetadata specialTableMetadata = metadata.getTableMetadata(SESSION, specialTableHandle);
        assertEquals(specialTableMetadata.getTable(), new SchemaTableName("exa_ple", "num_ers"));
        assertEquals(specialTableMetadata.getColumns(), ImmutableList.of(
                ColumnMetadata.builder().setName("te_t").setType(VARCHAR).setNullable(false).build(),
                ColumnMetadata.builder().setName("va%ue").setType(BIGINT).build()));

        // unknown tables should produce null
        unknownTableMetadata(new JdbcTableHandle(CONNECTOR_ID, new SchemaTableName("u", "numbers"), null, "unknown", "unknown"));
        unknownTableMetadata(new JdbcTableHandle(CONNECTOR_ID, new SchemaTableName("example", "numbers"), null, "example", "unknown"));
        unknownTableMetadata(new JdbcTableHandle(CONNECTOR_ID, new SchemaTableName("example", "numbers"), null, "unknown", "numbers"));
    }

    @Test
    public void testListTableColumns()
    {
        SchemaTableName tpchOrders = new SchemaTableName("tpch", "orders");
        ImmutableList<ColumnMetadata> tpchOrdersColumnMetadata = ImmutableList.of(
                ColumnMetadata.builder().setName("orderkey").setType(BIGINT).setNullable(false).build(),
                ColumnMetadata.builder().setName("custkey").setType(BIGINT).setNullable(true).build());

        SchemaTableName tpchLineItem = new SchemaTableName("tpch", "lineitem");
        ImmutableList<ColumnMetadata> tpchLineItemColumnMetadata = ImmutableList.of(
                ColumnMetadata.builder().setName("orderkey").setType(BIGINT).setNullable(false).build(),
                ColumnMetadata.builder().setName("partkey").setType(BIGINT).setNullable(true).build());

        //List columns for a given schema and table
        Map<SchemaTableName, List<ColumnMetadata>> tpchOrdersColumns = metadata.listTableColumns(SESSION, new SchemaTablePrefix("tpch", "orders"));
        assertThat(tpchOrdersColumns)
                .containsOnly(
                        entry(tpchOrders, tpchOrdersColumnMetadata));

        //List columns for a given schema
        Map<SchemaTableName, List<ColumnMetadata>> tpchColumns = metadata.listTableColumns(SESSION, new SchemaTablePrefix("tpch"));
        assertThat(tpchColumns)
                .containsOnly(
                        entry(tpchOrders, tpchOrdersColumnMetadata),
                        entry(tpchLineItem, tpchLineItemColumnMetadata));
    }

    private void unknownTableMetadata(JdbcTableHandle tableHandle)
    {
        try {
            metadata.getTableMetadata(SESSION, tableHandle);
            fail("Expected getTableMetadata of unknown table to throw a TableNotFoundException");
        }
        catch (TableNotFoundException ignored) {
        }
    }

    @Test
    public void testListTables()
    {
        // all schemas
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.empty())), ImmutableSet.of(
                new SchemaTableName("example", "numbers"),
                new SchemaTableName("example", "view_source"),
                new SchemaTableName("example", "view"),
                new SchemaTableName("tpch", "orders"),
                new SchemaTableName("tpch", "lineitem"),
                new SchemaTableName("exa_ple", "table_with_float_col"),
                new SchemaTableName("exa_ple", "num_ers")));

        // specific schema
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("example"))), ImmutableSet.of(
                new SchemaTableName("example", "numbers"),
                new SchemaTableName("example", "view_source"),
                new SchemaTableName("example", "view")));
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("tpch"))), ImmutableSet.of(
                new SchemaTableName("tpch", "orders"),
                new SchemaTableName("tpch", "lineitem")));
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("exa_ple"))), ImmutableSet.of(
                new SchemaTableName("exa_ple", "num_ers"),
                new SchemaTableName("exa_ple", "table_with_float_col")));

        // unknown schema
        assertEquals(ImmutableSet.copyOf(metadata.listTables(SESSION, Optional.of("unknown"))), ImmutableSet.of());
    }

    @Test
    public void getColumnMetadata()
    {
        assertEquals(
                metadata.getColumnMetadata(SESSION, tableHandle, new JdbcColumnHandle(CONNECTOR_ID, "text", JDBC_VARCHAR, VARCHAR, true, Optional.empty())),
                ColumnMetadata.builder().setName("text").setType(VARCHAR).build());
    }

    @Test
    public void testCreateAndAlterTable()
    {
        SchemaTableName table = new SchemaTableName("example", "foo");
        metadata.createTable(SESSION, new ConnectorTableMetadata(table, ImmutableList.of(ColumnMetadata.builder().setName("text").setType(VARCHAR).build())), false);

        JdbcTableHandle handle = metadata.getTableHandle(SESSION, table);

        ConnectorTableMetadata layout = metadata.getTableMetadata(SESSION, handle);
        assertEquals(layout.getTable(), table);
        assertEquals(layout.getColumns().size(), 1);
        assertEquals(layout.getColumns().get(0), ColumnMetadata.builder().setName("text").setType(VARCHAR).build());

        metadata.addColumn(SESSION, handle, ColumnMetadata.builder().setName("x").setType(VARCHAR).build());
        layout = metadata.getTableMetadata(SESSION, handle);
        assertEquals(layout.getColumns().size(), 2);
        assertEquals(layout.getColumns().get(0), ColumnMetadata.builder().setName("text").setType(VARCHAR).build());
        assertEquals(layout.getColumns().get(1), ColumnMetadata.builder().setName("x").setType(VARCHAR).build());

        JdbcColumnHandle columnHandle = new JdbcColumnHandle(CONNECTOR_ID, "x", JDBC_VARCHAR, VARCHAR, true, Optional.empty());
        metadata.dropColumn(SESSION, handle, columnHandle);
        layout = metadata.getTableMetadata(SESSION, handle);
        assertEquals(layout.getColumns().size(), 1);
        assertEquals(layout.getColumns().get(0), ColumnMetadata.builder().setName("text").setType(VARCHAR).build());

        SchemaTableName newTableName = new SchemaTableName("example", "bar");
        metadata.renameTable(SESSION, handle, newTableName);
        handle = metadata.getTableHandle(SESSION, newTableName);
        layout = metadata.getTableMetadata(SESSION, handle);
        assertEquals(layout.getTable(), newTableName);
        assertEquals(layout.getColumns().size(), 1);
        assertEquals(layout.getColumns().get(0), ColumnMetadata.builder().setName("text").setType(VARCHAR).build());
    }

    @Test
    public void testDropTableTable()
    {
        try {
            metadata.dropTable(SESSION, tableHandle);
            fail("expected exception");
        }
        catch (PrestoException e) {
            assertEquals(e.getErrorCode(), PERMISSION_DENIED.toErrorCode());
        }

        // Create BaseJdbcConfig with connection URL for drop table test
        BaseJdbcConfig dropConfig = new BaseJdbcConfig();
        dropConfig.setConnectionUrl("jdbc:h2:mem:test");
        metadata = new JdbcMetadata(jdbcMetadataCache, database.getJdbcClient(), true, new DefaultTableLocationProvider(dropConfig));
        metadata.dropTable(SESSION, tableHandle);

        try {
            metadata.getTableMetadata(SESSION, tableHandle);
            fail("expected exception");
        }
        catch (PrestoException e) {
            assertEquals(e.getErrorCode(), NOT_FOUND.toErrorCode());
        }
    }

    @Test
    public void testCustomTableLocationProvider()
    {
        // Create a custom TableLocationProvider that returns a specific location
        TableLocationProvider customProvider = new TableLocationProvider()
        {
            @Override
            public String getTableLocation()
            {
                return "custom://test-location:8080/database";
            }
        };

        // Create JdbcMetadata with the custom provider
        JdbcMetadata customMetadata = new JdbcMetadata(jdbcMetadataCache, database.getJdbcClient(), false, customProvider);

        // Verify that the metadata can be created and basic operations work
        JdbcTableHandle customTableHandle = customMetadata.getTableHandle(SESSION, new SchemaTableName("example", "numbers"));
        assertEquals(customTableHandle, tableHandle);

        // Verify table metadata can be retrieved
        ConnectorTableMetadata tableMetadata = customMetadata.getTableMetadata(SESSION, customTableHandle);
        assertEquals(tableMetadata.getTable(), new SchemaTableName("example", "numbers"));
        assertEquals(tableMetadata.getColumns().size(), 3);

        // Verify column handles work
        Map<String, ColumnHandle> columnHandles = customMetadata.getColumnHandles(SESSION, customTableHandle);
        assertEquals(columnHandles.size(), 3);
        assertTrue(columnHandles.containsKey("text"));
        assertTrue(columnHandles.containsKey("text_short"));
        assertTrue(columnHandles.containsKey("value"));

        // Verify schema listing works
        assertTrue(customMetadata.listSchemaNames(SESSION).containsAll(ImmutableSet.of("example", "tpch")));

        // Verify table listing works
        assertEquals(ImmutableSet.copyOf(customMetadata.listTables(SESSION, Optional.of("example"))), ImmutableSet.of(
                new SchemaTableName("example", "numbers"),
                new SchemaTableName("example", "view_source"),
                new SchemaTableName("example", "view")));
    }
}
