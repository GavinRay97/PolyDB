package io.github.gavinray97.polydb

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.quarkiverse.mcp.server.*
import io.quarkus.cache.CacheInvalidateAll
import io.quarkus.cache.CacheKeyGenerator
import io.quarkus.cache.CacheResult
import io.quarkus.runtime.Startup
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.apache.calcite.adapter.jdbc.JdbcSchema
import org.apache.calcite.jdbc.CalciteConnection
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.schema.Schema
import org.apache.calcite.schema.impl.AbstractSchema
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.logging.Logger
import org.jooq.Table
import org.jooq.impl.DSL
import java.lang.reflect.Method
import java.sql.Connection
import java.sql.DriverManager
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.time.measureTimedValue

// ==================== DATA MODELS ====================

enum class DatasourceStatus {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN,
}

data class DatasourceInfo(
    val name: String,
    val connectionStatus: DatasourceStatus,
    val type: String,
    val url: String? = null,
)

data class SchemaInfo(
    val fullyQualifiedName: List<String>,
    val datasource: String,
    val schemaName: String,
    val tableCount: Int,
)

data class ColumnInfo(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val defaultValue: String? = null,
    val isPrimaryKey: Boolean = false,
    val isAutoIncrement: Boolean = false,
    val constraints: List<String> = emptyList(),
)

data class TableInfo(
    val fullyQualifiedName: List<String>,
    val schema: String,
    val tableName: String,
    val columns: List<ColumnInfo> = emptyList(),
)

data class QueryResult(
    val columns: List<String>,
    val rows: List<Map<String, Any>>,
    val executionTimeMs: Long,
)

data class AddDatasourceRequest(
    val name: String,
    val jdbcUrl: String,
    val username: String? = null,
    val password: String? = null,
    val properties: Map<String, String> = emptyMap(),
)

// ==================== EXCEPTIONS ====================

class DatabaseIntrospectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class DatasourceNotFoundException(
    message: String,
) : Exception(message)

// ==================== SERVICE INTERFACE ====================

interface DatabaseService {
    fun listDatasources(): List<DatasourceInfo>

    fun listSchemas(
        regexPattern: String? = null,
    ): List<SchemaInfo>

    fun listTables(
        regexPattern: String? = null,
    ): List<TableInfo>

    fun executeQuery(
        query: String,
    ): QueryResult

    fun addDatasource(
        request: AddDatasourceRequest,
    ): DatasourceInfo

    fun debugCalciteSchema(): String
}

// ==================== SERVICE IMPLEMENTATION ====================

fun Schema.print(indentSpaces: Int = 0): String =
    buildString {
        if (tableNames.isNotEmpty()) {
            append(" ".repeat(indentSpaces)).append("Tables:\n")
            tableNames.forEach { table ->
                append(" ".repeat(indentSpaces)).append("  - ").append(table).append("\n")
                getTable(table)?.getRowType(JavaTypeFactoryImpl())?.fieldList?.forEach { field ->
                    append(" ".repeat(indentSpaces + 4))
                        .append("${field.name} (${field.type})")
                        .append("\n")
                }
            }
        }

        subSchemaNames.forEach { subSchemaName ->
            if (subSchemaName == "metadata") return@forEach // Skip metadata schema
            getSubSchema(subSchemaName)?.let { subSchema ->
                append(" ".repeat(indentSpaces + 4)).append("Schema: ").append(subSchemaName).append("\n")
                append(subSchema.print(indentSpaces + 4)).append("\n")
            }
        }
    }

// Util method to print a Calcite schema in a human-readable format optimized for LLM usage
// This method recursively traverses the schema and its sub-schemas, listing tables and their columns.
// Table names are fully-qualified to aid in LLM SQL generation tasks.
fun Schema.printForLLM(
    indentSpaces: Int = 0,
    parentSchemaName: String? = null,
): String =
    buildString {
        val schemaName = parentSchemaName?.let { "$it." } ?: ""
        if (tableNames.isNotEmpty()) {
            append(" ".repeat(indentSpaces)).append("Tables:\n")
            tableNames.forEach { table ->
                append(" ".repeat(indentSpaces)).append("  - ").append("$schemaName$table").append("\n")
                getTable(table)?.getRowType(JavaTypeFactoryImpl())?.fieldList?.forEach { field ->
                    append(" ".repeat(indentSpaces + 4))
                        .append("${field.name} (${field.type})")
                        .append("\n")
                }
            }
        }

        subSchemaNames.forEach { subSchemaName ->
            if (subSchemaName == "metadata") return@forEach // Skip metadata schema
            getSubSchema(subSchemaName)?.let { subSchema ->
                append(" ".repeat(indentSpaces + 4)).append("Schema: ").append("$schemaName$subSchemaName").append("\n")
                append(subSchema.printForLLM(indentSpaces + 4, "$schemaName$subSchemaName")).append("\n")
            }
        }
    }

@ApplicationScoped
class NullSafeKeyGenerator : CacheKeyGenerator {
    private val NULL_SENTINEL = Any()

    override fun generate(
        method: Method,
        vararg methodParams: Any?,
    ): Any {
        if (methodParams.size != 1) {
            throw IllegalStateException("This key generator only supports methods with exactly one parameter")
        }
        return methodParams[0] ?: NULL_SENTINEL
    }
}

@ApplicationScoped
class DatabaseServiceImpl : DatabaseService {
    private val logger = Logger.getLogger(DatabaseServiceImpl::class.java)
    private val datasources = ConcurrentHashMap<String, DataSource>()
    private val calciteConnection: CalciteConnection = createEmptyCalciteConnection()

    // ------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------

    @CacheResult(cacheName = "datasource-list")
    override fun listDatasources(): List<DatasourceInfo> =
        runBlocking {
            parallelDatasource { dsName, connection ->
                val md = connection.metaData
                DatasourceInfo(dsName, DatasourceStatus.CONNECTED, md.databaseProductName, md.url)
            }
        }

    @CacheResult(cacheName = "schema-list", keyGenerator = NullSafeKeyGenerator::class)
    override fun listSchemas(regexPattern: String?): List<SchemaInfo> =
        runBlocking {
            val filter = regexPattern?.let(::Regex)
            parallelDatasource { dsName, connection ->
                DSL
                    .using(connection)
                    .meta()
                    .schemas
                    .filter { filter?.matches("$dsName.${it.name}") ?: true }
                    .map { s -> SchemaInfo(listOf(dsName, s.name), dsName, s.name, s.tables.size) }
            }.flatten()
        }

    @CacheResult(cacheName = "table-list", keyGenerator = NullSafeKeyGenerator::class)
    override fun listTables(regexPattern: String?): List<TableInfo> =
        runBlocking {
            val filter = regexPattern?.let(::Regex)
            parallelDatasource { dsName, connection ->
                DSL
                    .using(connection)
                    .meta()
                    .tables
                    .filter { t ->
                        val schema = t.schema?.name ?: ""
                        filter?.matches("$dsName.$schema.${t.name}") ?: true
                    }.map { t ->
                        TableInfo(
                            fullyQualifiedName = listOf(dsName, t.schema?.name ?: "", t.name),
                            schema = dsName,
                            tableName = t.name,
                            columns = extractColumnInfo(t),
                        )
                    }
            }.flatten()
        }

    override fun executeQuery(query: String): QueryResult {
        val ctx = DSL.using(calciteConnection)
        if (query.lowercase().startsWith("explain")) {
            // jOOQ won't parse EXPLAIN queries, so we pass it directly to Calcite
            val result = ctx.fetchOne(query) ?: error("Failed to execute EXPLAIN query: $query")
            return QueryResult(
                columns = result.fields().map { it.name },
                rows = listOf(result.intoMap()),
                executionTimeMs = 0L, // EXPLAIN queries don't have execution time
            )
        }
        val parsedQuery = ctx.parser().parseResultQuery(query) ?: error("Failed to parse query: $query")
        val cols = parsedQuery.fields().map { it.name }
        val (rows, elapsed) = measureTimedValue { ctx.fetch(parsedQuery).intoMaps() }
        return QueryResult(cols, rows, elapsed.inWholeMilliseconds)
    }

    @CacheInvalidateAll(cacheName = "datasource-list")
    @CacheInvalidateAll(cacheName = "schema-list")
    @CacheInvalidateAll(cacheName = "table-list")
    override fun addDatasource(request: AddDatasourceRequest): DatasourceInfo {
        require(!datasources.containsKey(request.name)) { "Datasource '${request.name}' already exists" }

        val ds = createDataSource(request).also { validate(it) }
        datasources[request.name] = ds
        updateCalciteSchemaForNewDatasource(request.name, ds)

        val type = runCatching { ds.connection.use { it.metaData.databaseProductName } }.getOrDefault("UNKNOWN")
        return DatasourceInfo(request.name, DatasourceStatus.CONNECTED, type, request.jdbcUrl)
    }

    override fun debugCalciteSchema(): String = calciteConnection.rootSchema.printForLLM()

    // ------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------

    private suspend fun <T> parallelDatasource(
        block: suspend (String, Connection) -> T,
    ): List<T> =
        coroutineScope {
            datasources
                .map { (name, ds) ->
                    async(Dispatchers.IO) {
                        ds.connection.use { conn ->
                            runCatching { block(name, conn) }
                                .onFailure { logger.warn("Datasource '$name' error", it) }
                                .getOrNull()
                        }
                    }
                }.awaitAll()
                .filterNotNull()
        }

    private fun createDataSource(r: AddDatasourceRequest): DataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = r.jdbcUrl
                username = r.username
                password = r.password
                maximumPoolSize = 5
                minimumIdle = 1
                connectionTimeout = 30_000
                isReadOnly = true
                r.properties.forEach { (k, v) -> addDataSourceProperty(k, v) }
            },
        )

    private fun validate(ds: DataSource) = ds.connection.use { check(it.isValid(5)) { "Failed validation" } }

    private fun addCalciteDatabase(
        name: String,
        ds: DataSource,
    ) {
        val root = calciteConnection.rootSchema
        check(root.getSubSchema(name) == null) { "Database $name already exists in Calcite schema" }

        val systemSchemas = setOf("information_schema", "sys", "pg_catalog", "metadata")
        val schemas =
            ds.connection.use { conn ->
                conn.metaData.schemas.use { rs ->
                    generateSequence { if (rs.next()) rs.getString("TABLE_SCHEM") else null }.toList()
                }
            }

        if (schemas.isEmpty()) {
            root.add(name, JdbcSchema.create(root, name, ds, null, null))
        } else {
            val dbSchema =
                root.add(
                    name,
                    object : AbstractSchema() {
                        override fun getSubSchemaMap() = emptyMap<String, Schema>()
                    },
                )
            schemas.filterNot { it.lowercase() in systemSchemas }.forEach { s ->
                dbSchema.add(s, JdbcSchema.create(dbSchema, s, ds, null, s))
            }
        }
    }

    private fun createEmptyCalciteConnection(): CalciteConnection =
        DriverManager
            .getConnection("jdbc:calcite:", Properties().apply { setProperty("lex", "MYSQL") })
            .unwrap(CalciteConnection::class.java)

    private fun updateCalciteSchemaForNewDatasource(
        name: String,
        ds: DataSource,
    ) = runCatching { addCalciteDatabase(name, ds) }
        .onSuccess { logger.info("Datasource '$name' added to Calcite schema") }
        .onFailure { logger.warn("Failed to add datasource '$name'", it) }

    private fun extractColumnInfo(t: Table<*>): List<ColumnInfo> =
        runCatching {
            val pk = t.primaryKey?.fields?.map { it.name } ?: emptyList()
            t.fields().map { f ->
                val dt = f.dataType
                ColumnInfo(
                    name = f.name,
                    type = dt.typeName,
                    nullable = dt.nullable(),
                    defaultValue = dt.defaultValue()?.toString(),
                    isPrimaryKey = f.name in pk,
                    isAutoIncrement = dt.identity(),
                    constraints = emptyList(),
                )
            }
        }.getOrElse {
            logger.warn("Failed to read columns for ${t.name}", it)
            emptyList()
        }

    // ------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------

    @PreDestroy
    fun cleanup() {
        runCatching { calciteConnection.close() }.onFailure { logger.warn("Failed to close Calcite", it) }
        datasources.values.filterIsInstance<HikariDataSource>().forEach { ds ->
            runCatching { ds.close() }.onFailure { logger.warn("Failed to close datasource ${ds.jdbcUrl}", it) }
        }
        datasources.clear()
    }
}
// ==================== MCP TOOLS ====================

@ApplicationScoped
class DatabaseMcpTools {
    @Inject
    private lateinit var databaseService: DatabaseService

    private val logger = Logger.getLogger(DatabaseMcpTools::class.java)

    @Tool(description = "List all connected datasources")
    fun listDatasources(): List<DatasourceInfo> {
        logger.info("MCP Tool: listDatasources called")
        return databaseService.listDatasources()
    }

    @Tool(description = "List tables with columns, optionally filtering by a fully-qualified regex pattern.")
    fun listTables(
        @ToolArg(
            description = "Regex pattern for fully-qualified table names (e.g., 'my_db.public.users').",
            defaultValue = "",
        ) regexPattern: String?,
    ): String {
        logger.info("MCP Tool: listTables called with pattern: $regexPattern")
        return databaseService.debugCalciteSchema()
    }

    @Tool(description = "Execute a SQL query against the connected datasources")
    fun executeQuery(
        @ToolArg(description = "SQL query to execute") query: String,
    ): QueryResult {
        logger.info("MCP Tool: executeQuery called")
        return databaseService.executeQuery(query)
    }

    @Tool(description = "Add a new Calcite datasource")
    fun addDatasource(
        @ToolArg(description = "Datasource name") name: String,
        @ToolArg(description = "JDBC URL") jdbcUrl: String,
        @ToolArg(description = "Database username", defaultValue = "") username: String?,
        @ToolArg(description = "Database password", defaultValue = "") password: String?,
    ): DatasourceInfo {
        logger.info("MCP Tool: addDatasource called with name $name")
        val request =
            AddDatasourceRequest(
                name = name,
                jdbcUrl = jdbcUrl,
                username = username?.takeIf { it.isNotBlank() },
                password = password?.takeIf { it.isNotBlank() },
            )
        return databaseService.addDatasource(request)
    }
}

// ==================== REST ENDPOINTS ====================

@Path("/api/v1")
@Tag(name = "Database Introspection", description = "Database schema introspection and query execution")
class DatabaseRestResource {
    @Inject
    lateinit var databaseService: DatabaseService

    private val logger = Logger.getLogger(DatabaseRestResource::class.java)

    @GET
    @Path("/datasources")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List all datasources", description = "Get all connected datasources")
    fun listDatasources(): List<DatasourceInfo> {
        logger.info("REST API: listDatasources called")
        return databaseService.listDatasources()
    }

    @GET
    @Path("/schemas")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List schemas", description = "Get all schemas, optionally filtered by regex")
    fun listSchemas(
        @QueryParam("regex") regexPattern: String?,
    ): List<SchemaInfo> {
        logger.info("REST API: listSchemas called")
        return databaseService.listSchemas(regexPattern)
    }

    @GET
    @Path("/tables")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "List tables",
        description = "Get all tables with column metadata, optionally filtered by regex",
    )
    fun listTables(
        @QueryParam("regex") regexPattern: String?,
    ): List<TableInfo> {
        logger.info("REST API: listTables called")
        return databaseService.listTables(regexPattern)
    }

    @POST
    @Path("/query")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Execute query", description = "Execute a SQL query against connected datasources")
    fun executeQuery(
        query: String,
    ): QueryResult {
        logger.info("REST API: executeQuery called")
        return databaseService.executeQuery(query)
    }

    @POST
    @Path("/datasources")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add datasource", description = "Add a new datasource")
    fun addDatasource(
        request: AddDatasourceRequest,
    ): DatasourceInfo {
        logger.info("REST API: addDatasource called with request: $request")
        return databaseService.addDatasource(request)
    }

    // Dev endpoint to print out the Calcite rootschema recursively
    @GET
    @Path("/debug/calcite-schema")
    @Produces(MediaType.TEXT_PLAIN)
    fun debugCalciteSchema(): String {
        logger.info("REST API: debugCalciteSchema called")
        return databaseService.debugCalciteSchema()
    }
}

// ==================== GLOBAL EXCEPTION HANDLER ====================

@Provider
class GlobalExceptionHandler : ExceptionMapper<Exception> {
    override fun toResponse(exception: Exception): Response {
        throw exception
        val status =
            when (exception) {
                is DatabaseIntrospectionException -> Response.Status.BAD_REQUEST
                is IllegalArgumentException -> Response.Status.BAD_REQUEST
                else -> Response.Status.INTERNAL_SERVER_ERROR
            }

        val entity = mapOf("error" to (exception.message ?: "An unexpected error occurred."))
        return Response.status(status).entity(entity).build()
    }
}

// ==================== STARTUP CONFIGURATION ====================

@ApplicationScoped
class ApplicationStartup {
    @Inject
    lateinit var databaseService: DatabaseService

    private val logger = Logger.getLogger(ApplicationStartup::class.java)

    @Startup
    fun init() {
        logger.info("MCP Server starting up...")

        // Add Postgres + MySQL + Mongo datasources for testing
        val initialDatasources =
            listOf(
                AddDatasourceRequest("postgres_users", "jdbc:postgresql://localhost:5432/user_db?user=user&password=password"),
                AddDatasourceRequest("mysql_orders", "jdbc:mysql://localhost:3306/order_db?user=user&password=password"),
                AddDatasourceRequest("mongo_reviews", "jdbc:documentdb://root:password@localhost:27017/review_db?defaultAuthDb=admin&tls=false"),
            )

        initialDatasources.forEach { request ->
            try {
                databaseService.addDatasource(request)
                logger.info("Added initial datasource: ${request.name}")
            } catch (e: Exception) {
                logger.error("Failed to add initial datasource: ${request.name}", e)
            }
        }
    }
}
