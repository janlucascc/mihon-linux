package mihon.data

import mihon.data.database.DatabaseManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File

abstract class DatabaseTestBase {

    protected lateinit var tempDbFile: File
    protected lateinit var databaseManager: DatabaseManager

    @BeforeEach
    fun setUp() {
        tempDbFile = File.createTempFile("mihon-persistence-test-", ".db")
        databaseManager = DatabaseManager(tempDbFile).apply {
            initialize()
        }
    }

    @AfterEach
    fun tearDown() {
        if (tempDbFile.exists()) {
            tempDbFile.delete()
        }
        File(tempDbFile.absolutePath + "-wal").delete()
        File(tempDbFile.absolutePath + "-shm").delete()
    }
}
