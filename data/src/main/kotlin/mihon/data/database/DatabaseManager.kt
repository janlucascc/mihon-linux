package mihon.data.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.data.database.tables.ChapterTable
import mihon.data.database.tables.HistoryTable
import mihon.data.database.tables.MangaTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File
import java.sql.Connection

class DatabaseManager(val dbPath: File) {
    val writeMutex = Mutex()

    val dataSource: SQLiteDataSource by lazy {
        dbPath.parentFile?.mkdirs()
        val config = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(5000)
            enforceForeignKeys(true)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        }
        SQLiteDataSource(config).apply {
            url = "jdbc:sqlite:${dbPath.absolutePath}"
        }
    }

    val database: Database by lazy {
        Database.connect(dataSource)
    }

    fun getConnection(): Connection = dataSource.connection

    /** Checkpoints and truncates WAL journal, ensuring clean disk state. */
    fun checkpoint() {
        runCatching {
            transaction(database) {
                exec("PRAGMA wal_checkpoint(TRUNCATE);")
            }
        }
    }

    /** Optimizes the SQLite database storage by truncating WAL and running VACUUM. */
    fun vacuum() {
        checkpoint()
        runCatching {
            val rawConn = java.sql.DriverManager.getConnection("jdbc:sqlite:${dbPath.absolutePath}")
            rawConn.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("VACUUM;")
                }
            }
        }
    }

    /**
     * Executes a write transaction under a centralized coroutine Mutex lock.
     *
     * SQLite WAL mode allows concurrent readers, but permits strictly one active writer.
     * Serializing write transactions via [writeMutex] prevents SQLITE_BUSY_SNAPSHOT (code 517)
     * during concurrent read-then-write or cross-table mutations while allowing concurrent
     * readers to query the database in parallel with zero blocking.
     */
    suspend fun <T> withWriteTransaction(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: Transaction.() -> T,
    ): T = writeMutex.withLock {
        withContext(dispatcher) {
            transaction(database) {
                block()
            }
        }
    }

    fun initialize() {
        transaction(database) {
            val mangaExists = exec("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='manga'") { rs ->
                if (rs.next()) rs.getInt(1) > 0 else false
            } ?: false

            if (mangaExists) {
                val columns = exec("PRAGMA table_info(manga)") { rs ->
                    val list = mutableListOf<String>()
                    while (rs.next()) {
                        list.add(rs.getString("name"))
                    }
                    list
                } ?: emptyList()

                // Legacy schema check: missing 'source' column
                if (!columns.contains("source")) {
                    migrateLegacySchema()
                }
            }

            // Create or verify modern tables and indexes
            SchemaUtils.create(MangaTable, ChapterTable, HistoryTable)
            deduplicateMangaRows()
        }
    }

    /**
     * Merges any duplicate `(source, url)` manga rows created in earlier versions,
     * preserving the lowest `id` and any read/partially-read chapter progress.
     */
    private fun Transaction.deduplicateMangaRows() {
        val rawConn = TransactionManager.current().connection.connection as Connection
        rawConn.createStatement().use { stmt ->
            // Reassign chapters from duplicates to the preserved manga row (lowest ID)
            stmt.execute(
                """
                UPDATE chapter
                SET manga_id = (
                    SELECT MIN(keeper.id)
                    FROM manga keeper
                    JOIN manga dup ON dup.source = keeper.source AND dup.url = keeper.url
                    WHERE dup.id = chapter.manga_id
                )
                WHERE manga_id IN (
                    SELECT id FROM manga
                    WHERE id NOT IN (SELECT MIN(id) FROM manga GROUP BY source, url)
                );
                """.trimIndent(),
            )
            // Delete duplicate manga rows now that children have been safely reparented
            stmt.execute(
                """
                DELETE FROM manga
                WHERE id NOT IN (
                    SELECT MIN(id) FROM manga GROUP BY source, url
                );
                """.trimIndent(),
            )
        }
    }

    private fun Transaction.migrateLegacySchema() {
        val rowCount = exec("SELECT count(*) FROM manga") { rs ->
            if (rs.next()) rs.getInt(1) else 0
        } ?: 0

        val rawConn = TransactionManager.current().connection.connection as Connection

        if (rowCount == 0) {
            rawConn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS chapter;")
                stmt.execute("DROP TABLE IF EXISTS manga;")
            }
        } else {
            rawConn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys = OFF;")
                stmt.execute("ALTER TABLE chapter RENAME TO _legacy_chapter;")
                stmt.execute("ALTER TABLE manga RENAME TO _legacy_manga;")

                // Create modern tables
                stmt.execute("""
                    CREATE TABLE manga (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        source INTEGER NOT NULL DEFAULT 0,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT,
                        author TEXT,
                        description TEXT,
                        genre TEXT,
                        status INTEGER NOT NULL DEFAULT 0,
                        thumbnail_url TEXT,
                        update_strategy VARCHAR(32) NOT NULL DEFAULT 'ALWAYS_UPDATE',
                        initialized INTEGER NOT NULL DEFAULT 0,
                        favorite INTEGER NOT NULL DEFAULT 0,
                        last_update INTEGER NOT NULL DEFAULT 0,
                        next_update INTEGER NOT NULL DEFAULT 0,
                        fetch_interval INTEGER NOT NULL DEFAULT 0,
                        date_added INTEGER NOT NULL DEFAULT 0,
                        viewer_flags INTEGER NOT NULL DEFAULT 0,
                        chapter_flags INTEGER NOT NULL DEFAULT 0,
                        cover_last_modified INTEGER NOT NULL DEFAULT 0,
                        notes TEXT,
                        memo TEXT,
                        favorite_at INTEGER
                    );
                """)
                stmt.execute("""
                    CREATE TABLE chapter (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        manga_id INTEGER NOT NULL REFERENCES manga(id) ON DELETE CASCADE,
                        url TEXT NOT NULL,
                        name TEXT NOT NULL,
                        scanlator TEXT,
                        read INTEGER NOT NULL DEFAULT 0,
                        bookmark INTEGER NOT NULL DEFAULT 0,
                        last_page_read INTEGER NOT NULL DEFAULT 0,
                        chapter_number REAL NOT NULL DEFAULT -1.0,
                        source_order INTEGER NOT NULL DEFAULT 0,
                        date_fetch INTEGER NOT NULL DEFAULT 0,
                        date_upload INTEGER,
                        memo TEXT
                    );
                """)
                stmt.execute("""
                    CREATE TABLE history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        chapter_id INTEGER NOT NULL REFERENCES chapter(id) ON DELETE CASCADE,
                        read_at INTEGER,
                        read_duration INTEGER NOT NULL DEFAULT 0
                    );
                """)

                // Migrate existing rows
                stmt.execute("""
                    INSERT INTO manga (id, title, url, thumbnail_url, author, artist, description, status, last_update, source, favorite, update_strategy, initialized)
                    SELECT id, title, url, cover_url, author, artist, description, status, last_updated, 0, 0, 'ALWAYS_UPDATE', 0 FROM _legacy_manga;
                """)
                stmt.execute("""
                    INSERT INTO chapter (id, manga_id, name, url, chapter_number, read, bookmark, last_page_read, source_order, date_fetch)
                    SELECT id, manga_id, title, url, CAST(chapter AS REAL), 0, 0, 0, 0, 0 FROM _legacy_chapter;
                """)
                stmt.execute("DROP TABLE _legacy_chapter;")
                stmt.execute("DROP TABLE _legacy_manga;")
                stmt.execute("PRAGMA foreign_keys = ON;")
            }
        }
    }
}
