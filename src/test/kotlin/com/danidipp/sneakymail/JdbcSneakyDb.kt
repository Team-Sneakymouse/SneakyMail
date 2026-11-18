package com.danidipp.sneakymail

import com.sneakydb.SneakyDBApi
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * A SneakyDBApi test double over plain JDBC with SneakyDB's row shape (one autocommit connection per operation,
 * column label to getObject). Like SneakyDB, failed futures carry the original exception.
 */
class JdbcSneakyDb(private val url: String) : SneakyDBApi, AutoCloseable {
    private val workers = Executors.newFixedThreadPool(4)

    private fun <T> submit(work: (Connection) -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        workers.execute {
            try {
                future.complete(DriverManager.getConnection(url).use(work))
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return future
    }

    override fun query(sql: String, parameters: List<*>): CompletableFuture<List<Map<String, Any?>>> = submit { connection ->
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                val meta = result.metaData
                buildList {
                    while (result.next()) add((1..meta.columnCount).associate { meta.getColumnLabel(it) to result.getObject(it) })
                }
            }
        }
    }

    override fun execute(sql: String, parameters: List<*>): CompletableFuture<Int> = submit { connection ->
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    override fun whenReady(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    override fun isAvailable(): Boolean = true

    override fun close() {
        workers.shutdownNow()
    }
}
