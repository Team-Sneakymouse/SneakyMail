package com.danidipp.sneakymail

import com.sneakydb.SneakyDBApi
import com.sneakydb.SneakyDBTransaction
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.function.Function

/**
 * A SneakyDBApi test double over plain JDBC with SneakyDB's row shape (one connection per operation, column label to
 * getObject; transactions with autocommit off, rolled back when the work throws). Like SneakyDB, failed futures carry
 * the original exception.
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

    override fun query(sql: String, parameters: List<*>): CompletableFuture<List<Map<String, Any?>>> =
        submit { rows(it, sql, parameters) }

    override fun execute(sql: String, parameters: List<*>): CompletableFuture<Int> = submit { update(it, sql, parameters) }

    override fun <T> transaction(work: Function<SneakyDBTransaction, T>): CompletableFuture<T> = submit { connection ->
        connection.autoCommit = false
        try {
            val value = work.apply(object : SneakyDBTransaction {
                override fun query(sql: String, parameters: List<*>) = rows(connection, sql, parameters)
                override fun execute(sql: String, parameters: List<*>) = update(connection, sql, parameters)
            })
            connection.commit()
            value
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    override fun whenReady(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    override fun isAvailable(): Boolean = true

    override fun close() {
        workers.shutdownNow()
    }

    private fun rows(connection: Connection, sql: String, parameters: List<*>): List<Map<String, Any?>> =
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                val meta = result.metaData
                buildList {
                    while (result.next()) add((1..meta.columnCount).associate { meta.getColumnLabel(it) to result.getObject(it) })
                }
            }
        }

    private fun update(connection: Connection, sql: String, parameters: List<*>): Int =
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
}
