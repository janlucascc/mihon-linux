-- Migration V2: Add unique indexes and migration safety
-- This migration adds unique constraints to prevent duplicate manga/chapter entries
-- and ensures safe schema migrations with automatic backup/restore.

-- 1. Create unique index on manga (source_id, url) to prevent duplicate manga entries
-- Note: In SQLite, the column is named 'source' in the DB but 'sourceId' in Kotlin
CREATE UNIQUE INDEX IF NOT EXISTS idx_manga_source_url ON manga(source, url);

-- 2. Create unique index on chapter (manga_id, url) to prevent duplicate chapter entries
CREATE UNIQUE INDEX IF NOT EXISTS idx_chapter_manga_url ON chapter(manga_id, url);

-- 3. Verify no existing duplicates before applying unique indexes
-- If duplicates exist, this will fail and you must run deduplication first
-- The Kotlin code handles deduplication in deduplicateMangaRows()

-- 4. Migration safety: Backup is handled in Kotlin code (DatabaseManager.createBackupBeforeMigration)
--    before any schema migration runs. If migration fails, backup is automatically restored.

-- 5. ExtensionSource.id stability: HttpScraperSource now uses UUID v3 (MD5-based)
--    generated from a fixed namespace + baseUrl + language, ensuring stable IDs
--    across app restarts. No database schema change needed for this.

-- Verification queries (run after migration):
-- SELECT COUNT(*) FROM manga;
-- SELECT COUNT(*) FROM (SELECT source, url FROM manga GROUP BY source, url HAVING COUNT(*) > 1);
-- Should return 0 duplicates.

-- SELECT COUNT(*) FROM chapter;
-- SELECT COUNT(*) FROM (SELECT manga_id, url FROM chapter GROUP BY manga_id, url HAVING COUNT(*) > 1);
-- Should return 0 duplicates.