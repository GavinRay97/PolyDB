// package io.github.gavinray97.polydb
//
// import com.fasterxml.jackson.databind.ObjectMapper
// import io.quarkiverse.mcp.server.ToolResponse
// import io.quarkiverse.mcp.server.test.McpAssured
// import io.quarkiverse.mcp.server.test.McpAssured.McpSseTestClient
// import io.quarkus.test.common.http.TestHTTPResource
// import io.quarkus.test.junit.QuarkusTest
// import jakarta.inject.Inject
// import org.junit.jupiter.api.*
// import org.junit.jupiter.api.Assertions.*
// import org.junit.jupiter.params.ParameterizedTest
// import org.junit.jupiter.params.provider.Arguments
// import org.junit.jupiter.params.provider.MethodSource
// import java.net.URI
// import java.util.stream.Stream
//
// // Using Lifecycle.PER_CLASS is more efficient if you have expensive setup code (like establishing a database connection) that can be shared across all tests.
// // It also allows you to use @BeforeAll and @AfterAll on non-static methods.
// @QuarkusTest
// @TestInstance(TestInstance.Lifecycle.PER_CLASS)
// @TestMethodOrder(MethodOrderer.OrderAnnotation::class)
// class McpSseClientTest {
//    @TestHTTPResource
//    lateinit var endpoint: URI
//
//    @Inject
//    lateinit var mapper: ObjectMapper
//    lateinit var client: McpSseTestClient
//
//    companion object {
//        @JvmStatic
//        fun datasourceProvider(): Stream<Arguments> =
//            Stream.of(
//                Arguments.of("postgres_users", "jdbc:postgresql://localhost:5432/user_db?user=user&password=password", "PostgreSQL"),
//                Arguments.of("mysql_orders", "jdbc:mysql://localhost:3306/order_db?user=user&password=password", "MySQL"),
//                Arguments.of("mongo_reviews", "jdbc:documentdb://root:password@localhost:27017/review_db?defaultAuthDb=admin&tls=false", null),
//            )
//    }
//
//    @BeforeEach
//    fun setUp() {
//        client =
//            McpAssured
//                .newSseClient()
//                .setBaseUri(endpoint)
//                .build()
//                .connect()
//    }
//
//    private fun getMcpSingleResponseAsMap(
//        response: ToolResponse,
//    ): Map<*, *> {
//        val content = response.content()[0] ?: error("Response content is empty")
//        val text = content.asText().text() ?: error("Response content is not text")
//        val map = mapper.readValue(text, Map::class.java)
//        return map
//    }
//
//    private fun getMcpReponseAsListOfMaps(
//        response: ToolResponse,
//    ): List<Map<*, *>> =
//        response.content().map { content ->
//            val text = content.asText().text() ?: error("Response content is not text")
//            mapper.readValue(text, Map::class.java)
//        }
//
//    @Test
//    @Order(1)
//    fun `can list MCP tools`() {
//        client
//            .`when`()
//            .toolsList()
//            .withAssert { page ->
//                assertEquals(7, page.size())
//            }.send()
//            .thenAssertResults()
//    }
//
//    @Test
//    @Order(2)
//    fun `can call listDatasources tool when empty`() {
//        client
//            .`when`()
//            .toolsCall("listDatasources")
//            .withAssert { response ->
//                assertFalse(response.isError())
//                assertTrue(response.content().isEmpty())
//            }.send()
//            .thenAssertResults()
//    }
//
//    @Order(3)
//    @ParameterizedTest
//    @MethodSource("datasourceProvider")
//    fun `can add datasources`(
//        name: String,
//        jdbcUrl: String,
//        expectedType: String?,
//    ) {
//        client
//            .`when`()
//            .toolsCall("addDatasource")
//            .withArguments(mapOf("name" to name, "jdbcUrl" to jdbcUrl))
//            .withAssert { response ->
//                assertFalse(response.isError(), "Response should not be an error")
//                assertEquals(1, response.content().size)
//                val datasource = getMcpSingleResponseAsMap(response)
//                assertEquals(name, datasource["name"])
//                assertEquals("CONNECTED", datasource["connectionStatus"])
//                if (expectedType != null) {
//                    assertTrue(datasource["type"].toString().contains(expectedType, ignoreCase = true))
//                }
//            }.send()
//            .thenAssertResults()
//    }
//
//    @Test
//    @Order(4)
//    fun `can list all datasources after adding multiple`() {
//        client
//            .`when`()
//            .toolsCall("listDatasources")
//            .withAssert { response ->
//                assertFalse(response.isError())
//                assertEquals(3, response.content().size)
//                val datasources = getMcpReponseAsListOfMaps(response)
//                val names = datasources.map { it["name"] }
//                assertTrue(names.contains("postgres-users"))
//                assertTrue(names.contains("mysql-orders"))
//                assertTrue(names.contains("mongo-reviews"))
//            }.send()
//            .thenAssertResults()
//    }
//
//    @Test
//    @Order(5)
//    fun `can list schemas across multiple datasources`() {
//        client
//            .`when`()
//            .toolsCall("listAllSchemas")
//            .withAssert { response ->
//                assertFalse(response.isError())
//                assertTrue(response.content().size > 0)
//                val schemas = getMcpReponseAsListOfMaps(response)
//                val datasourceNames = schemas.map { (it["fullyQualifiedName"] as List<*>)[0] }.toSet()
//                // Should have schemas from multiple datasources
//                assertTrue(datasourceNames.size > 1)
//            }.send()
//            .thenAssertResults()
//    }
//
//    @Test
//    @Order(6)
//    fun `can filter schemas by regex pattern`() {
//        client
//            .`when`()
//            .toolsCall("listSchemasByRegex")
//            .withArguments(
//                mapOf(
//                    "regexPattern" to "postgres-users\\..*",
//                ),
//            ).withAssert { response ->
//                assertFalse(response.isError())
//                assertTrue(response.content().isNotEmpty())
//                val schemas = getMcpReponseAsListOfMaps(response)
//                schemas.forEach { schema ->
//                    val fullyQualifiedName = (schema["fullyQualifiedName"] as List<*>).joinToString(".")
//                    assertTrue(fullyQualifiedName.startsWith("postgres-users."))
//                }
//            }.send()
//            .thenAssertResults()
//    }
//
//    @Test
//    @Order(7)
//    fun `can list tables across multiple datasources`() {
//        client
//            .`when`()
//            .toolsCall("listAllTables")
//            .withAssert { response ->
//                assertFalse(response.isError())
//                val tables = getMcpReponseAsListOfMaps(response)
//                if (tables.isNotEmpty()) {
//                    val datasourceNames = tables.map { (it["fullyQualifiedName"] as List<*>)[0] }.toSet()
//                    assertTrue(datasourceNames.isNotEmpty())
//                }
//            }.send()
//            .thenAssertResults()
//    }
//
//    @Test
//    @Order(8)
//    fun `can filter tables by regex pattern`() {
//        client
//            .`when`()
//            .toolsCall("listTablesByRegex")
//            .withArguments(
//                mapOf(
//                    "regexPattern" to "mysql-orders\\..*",
//                ),
//            ).withAssert { response ->
//                assertFalse(response.isError())
//                val tables = getMcpReponseAsListOfMaps(response)
//                tables.forEach { table ->
//                    val fullyQualifiedName = (table["fullyQualifiedName"] as List<*>).joinToString(".")
//                    assertTrue(fullyQualifiedName.startsWith("mysql-orders."))
//                }
//            }.send()
//            .thenAssertResults()
//    }
//
//    @Test
//    @Order(9)
//    fun `can execute simple query on PostgreSQL`() {
//        client
//            .`when`()
//            .toolsCall("executeQuery")
//            .withArguments(
//                mapOf(
//                    "query" to "SELECT 1 as test_value",
//                ),
//            ).withAssert { response ->
//                assertFalse(response.isError())
//                val queryResult = getMcpSingleResponseAsMap(response)
//                assertTrue(queryResult.containsKey("columns"))
//                assertTrue(queryResult.containsKey("rows"))
//            }.send()
//            .thenAssertResults()
//    }
//
//    @Test
//    @Order(10)
//    fun `can execute information schema query`() {
//        client
//            .`when`()
//            .toolsCall("executeQuery")
//            .withArguments(
//                mapOf(
//                    "query" to "SELECT table_schema, table_name FROM information_schema.tables LIMIT 5",
//                ),
//            ).withAssert { response ->
//                assertFalse(response.isError())
//                val queryResult = getMcpSingleResponseAsMap(response)
//                val columns = queryResult["columns"] as List<*>
//                assertTrue(columns.contains("table_schema") || columns.contains("TABLE_SCHEMA"))
//                assertTrue(columns.contains("table_name") || columns.contains("TABLE_NAME"))
//            }.send()
//            .thenAssertResults()
//    }
//
//    @Test
//    @Order(11)
//    fun `can execute cross-datasource query using Calcite`() {
//        client
//            .`when`()
//            .toolsCall("executeQuery")
//            .withArguments(
//                mapOf(
//                    "query" to
//                        """
//                        SELECT
//                            p.table_name as postgres_table,
//                            m.table_name as mysql_table
//                        FROM
//                            "postgres-users".information_schema.tables p
//                        CROSS JOIN
//                            "mysql-orders".information_schema.tables m
//                        LIMIT 3
//                        """.trimIndent(),
//                ),
//            ).withAssert { response ->
//                val queryResult = getMcpSingleResponseAsMap(response)
//                val columns = queryResult["columns"] as List<*>
//                assertTrue(columns.contains("postgres_table") || columns.contains("POSTGRES_TABLE"))
//                assertTrue(columns.contains("mysql_table") || columns.contains("MYSQL_TABLE"))
//            }.send()
//            .thenAssertResults()
//    }
// }
