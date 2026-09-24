package sipreview.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

class Database(val path: Path) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        createStatement().execute("PRAGMA journal_mode=WAL")
        createStatement().execute("PRAGMA foreign_keys=ON")
        autoCommit = false
    }

    init { Schema.create(this) }

    fun <T> tx(block: (Connection) -> T): T {
        var ok = false
        try {
            val r = block(conn)
            conn.commit(); ok = true; return r
        } finally {
            if (!ok) conn.rollback()
        }
    }

    fun stmt(sql: String): Statement = conn.createStatement()

    fun close() { conn.close() }

    companion object {
        fun open(dir: Path, name: String = "sipreview.db"): Database {
            Files.createDirectories(dir)
            return Database(dir.resolve(name))
        }
    }
}
