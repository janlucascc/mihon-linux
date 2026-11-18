package mihon.data.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.data.database.tables.BookmarkTable
import mihon.data.database.tables.ChapterTable
import mihon.data.database.tables.HistoryTable
import mihon.data.database.tables.MangaTable
import mihon.data.database.tables.SourceTable
import mihon.data.database.tables.TrackerAuthTable
import mihon.data.database.tables.TrackerProgressTable
import mihon.data.database.tables.TrackerSyncLogTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.time.Instant

class DatabaseManager(val dbPath: File) {
    val writeMutex = Mutex()

    private var _dataSource: SQLiteDataSource? = null
    private var _database: Database? = null

    val dataSource: SQLiteDataSource
        get() {
            if (_dataSource == null) {
                _dataSource = createDataSource()
            }
            return _dataSource!!
        }

    val database: Database
        get() {
            if (_database == null) {
                _database = Database.connect(dataSource)
            }
            return _database!!
        }

    private fun createDataSource(): SQLiteDataSource {
        dbPath.parentFile?.mkdirs()
        val config = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(5000)
            enforceForeignKeys(true)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        }
        return SQLiteDataSource(config).apply {
            url = "jdbc:sqlite:${dbPath.absolutePath}"
        }
    }

    fun resetConnection() {
        _database = null
        _dataSource?.let { it.url = "jdbc:sqlite:" } // Force disconnect
        _dataSource = null
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

                // Legacy schema check: missing BOTH 'source' and 'source_id' column
                if (!columns.contains("source") && !columns.contains("source_id")) {
                    migrateLegacySchema()
                }

                // Ensure SourceTable exists BEFORE migrations that reference it
                SchemaUtils.create(SourceTable)

                // Check updated columns after potential legacy migration
                val currentColumns = exec("PRAGMA table_info(manga)") { rs ->
                    val list = mutableListOf<String>()
                    while (rs.next()) {
                        list.add(rs.getString("name"))
                    }
                    list
                } ?: emptyList()

                // Migration: add or rename to source_id FK column if missing
                if (!currentColumns.contains("source_id")) {
                    migrateAddSourceIdFk()
                }

                // Deduplicate existing rows BEFORE creating unique indexes
                // to avoid UNIQUE constraint violations
                deduplicateMangaRows()
            }

            // Create or verify remaining modern tables and indexes (includes unique indexes)
            SchemaUtils.create(
                SourceTable,
                MangaTable,
                ChapterTable,
                HistoryTable,
                BookmarkTable,
                TrackerAuthTable,
                TrackerProgressTable,
                TrackerSyncLogTable,
            )
        }
    }

    /**
     * Merges any duplicate `(source, url)` manga rows created in earlier versions,
     * preserving the lowest `id` and any read/partially-read chapter progress.
     * Handles both legacy 'source' column and modern 'source_id' column.
     */
    private fun Transaction.deduplicateMangaRows() {
        val rawConn = TransactionManager.current().connection.connection as Connection
        
        // Check which column exists: 'source' (legacy) or 'source_id' (modern)
        val sourceColumn = exec("PRAGMA table_info(manga)") { rs ->
            val columns = mutableSetOf<String>()
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
            if (columns.contains("source_id")) "source_id" else "source"
        } ?: "source"
        
        rawConn.createStatement().use { stmt ->
            // Reassign chapters from duplicates to the preserved manga row (lowest ID)
            stmt.execute(
                """
                UPDATE chapter
                SET manga_id = (
                    SELECT MIN(keeper.id)
                    FROM manga keeper
                    JOIN manga dup ON dup.$sourceColumn = keeper.$sourceColumn AND dup.url = keeper.url
                    WHERE dup.id = chapter.manga_id
                )
                WHERE manga_id IN (
                    SELECT id FROM manga
                    WHERE id NOT IN (SELECT MIN(id) FROM manga GROUP BY $sourceColumn, url)
                );
                """.trimIndent(),
            )
            // Delete duplicate manga rows now that children have been safely reparented
            stmt.execute(
                """
                DELETE FROM manga
                WHERE id NOT IN (
                    SELECT MIN(id) FROM manga GROUP BY $sourceColumn, url
                );
                """.trimIndent(),
            )
        }
    }

    private fun Transaction.migrateLegacySchema() {
        val backupPath = createBackupBeforeMigration()
        
        try {
            transaction {
                performLegacyMigration()
            }
        } catch (e: Exception) {
            restoreBackup(backupPath)
            throw RuntimeException("Legacy schema migration failed, backup restored from $backupPath", e)
        }
    }

    private fun createBackupBeforeMigration(): File {
        val timestamp = Instant.now().toEpochMilli()
        val backupFile = File("${dbPath.absolutePath}.backup.$timestamp")
        Files.copy(dbPath.toPath(), backupFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        return backupFile
    }

    private fun restoreBackup(backupPath: File) {
        try {
            // Close existing connections first
            resetConnection()
            Files.copy(backupPath.toPath(), dbPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
            // Connections will be lazily reinitialized on next access
        } catch (e: Exception) {
            throw RuntimeException("Failed to restore backup from ${backupPath.absolutePath}", e)
        }
    }

    private fun Transaction.performLegacyMigration() {
        val rawConn = TransactionManager.current().connection.connection as Connection

        // Ensure SourceTable exists and default legacy source 0 exists
        SchemaUtils.create(SourceTable)
        rawConn.createStatement().use { stmt ->
            stmt.execute("""
                INSERT OR IGNORE INTO source (id, name, baseUrl, type, configJson, version, enabled, lastSync, errorCount, lastError)
                VALUES (0, 'Unknown (Legacy)', '', 'BUILTIN', '{}', '1.0', 0, 0, 0, NULL);
            """)
        }

        val rowCount = exec("SELECT count(*) FROM manga") { rs ->
            if (rs.next()) rs.getInt(1) else 0
        } ?: 0

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

                // Create modern tables directly with source_id
                stmt.execute("""
                    CREATE TABLE manga (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_id INTEGER NOT NULL DEFAULT 0 REFERENCES source(id) ON DELETE SET DEFAULT,
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

                // Migrate existing rows: map legacy columns to modern source_id directly
                stmt.execute("""
                    INSERT INTO manga (id, title, url, thumbnail_url, author, artist, description, status, last_update, source_id, favorite, update_strategy, initialized)
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

    /**
     * Migration: Add source_id column to manga table with FK to source(id).
     * The old 'source' column (integer) is renamed to 'source_id' without dropping tables or cascading deletions.
     */
    private fun Transaction.migrateAddSourceIdFk() {
        val rawConn = TransactionManager.current().connection.connection as Connection
        rawConn.createStatement().use { stmt ->
            // Ensure a default source entry exists (id=0 for legacy data)
            stmt.execute("""
                INSERT OR IGNORE INTO source (id, name, baseUrl, type, configJson, version, enabled, lastSync, errorCount, lastError)
                VALUES (0, 'Unknown (Legacy)', '', 'BUILTIN', '{}', '1.0', 0, 0, 0, NULL);
            """)

            val hasSource = exec("PRAGMA table_info(manga)") { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("name") == "source") found = true
                }
                found
            } ?: false

            if (hasSource) {
                stmt.execute("ALTER TABLE manga RENAME COLUMN source TO source_id;")
            } else {
                stmt.execute("ALTER TABLE manga ADD COLUMN source_id INTEGER NOT NULL DEFAULT 0;")
            }

            // Recreate indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS manga_source_id_url_idx ON manga(source_id, url);")
            stmt.execute("CREATE INDEX IF NOT EXISTS manga_favorite_idx ON manga(favorite);")
        }
    }
}
