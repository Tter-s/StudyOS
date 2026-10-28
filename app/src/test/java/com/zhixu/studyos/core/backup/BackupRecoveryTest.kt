package com.zhixu.studyos.core.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRecoveryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recover_beforeFileSwap_completesSwapAndKeepsNewDocuments() {
        val filesDir = temporaryFolder.newFolder("files")
        val live = File(filesDir, BackupTables.DOCUMENTS_ROOT).apply { mkdirs() }
        File(live, "old.pdf").writeText("old")
        val staging = File(filesDir, ".restore_staging_abc").apply { mkdirs() }
        val stagedDocuments = File(staging, BackupTables.DOCUMENTS_ROOT).apply { mkdirs() }
        File(stagedDocuments, "new.pdf").writeText("new")
        val backup = File(filesDir, ".restore_backup_abc")
        BackupRecovery.write(
            filesDir,
            BackupRecovery.Journal(stagingName = staging.name, backupName = backup.name),
        )

        BackupRecovery.recover(filesDir)

        assertEquals("new", File(live, "new.pdf").readText())
        assertFalse(File(live, "old.pdf").exists())
        assertFalse(staging.exists())
        assertFalse(backup.exists())
        assertFalse(File(filesDir, ".restore_journal").exists())
    }

    @Test
    fun recover_whenDatabaseTransactionWasNotCommitted_keepsOldDocuments() {
        val filesDir = temporaryFolder.newFolder("files")
        val live = File(filesDir, BackupTables.DOCUMENTS_ROOT).apply { mkdirs() }
        File(live, "old.pdf").writeText("old")
        val staging = File(filesDir, ".restore_staging_abc").apply { mkdirs() }
        val stagedDocuments = File(staging, BackupTables.DOCUMENTS_ROOT).apply { mkdirs() }
        File(stagedDocuments, "new.pdf").writeText("new")
        val backup = File(filesDir, ".restore_backup_abc")
        BackupRecovery.write(
            filesDir,
            BackupRecovery.Journal(
                stagingName = staging.name,
                backupName = backup.name,
                token = "restore-token",
            ),
        )

        BackupRecovery.recover(
            filesDir = filesDir,
            isDatabaseCommitted = { false },
        )

        assertEquals("old", File(live, "old.pdf").readText())
        assertFalse(File(live, "new.pdf").exists())
        assertFalse(staging.exists())
        assertFalse(File(filesDir, ".restore_journal").exists())
    }

    @Test
    fun recover_afterSwap_deletesOldBackup() {
        val filesDir = temporaryFolder.newFolder("files")
        val live = File(filesDir, BackupTables.DOCUMENTS_ROOT).apply { mkdirs() }
        File(live, "new.pdf").writeText("new")
        val backup = File(filesDir, ".restore_backup_abc").apply { mkdirs() }
        File(backup, "old.pdf").writeText("old")
        BackupRecovery.write(
            filesDir,
            BackupRecovery.Journal(
                stagingName = ".restore_staging_abc",
                backupName = backup.name,
            ),
        )

        BackupRecovery.recover(filesDir)

        assertEquals("new", File(live, "new.pdf").readText())
        assertFalse(backup.exists())
        assertFalse(File(filesDir, ".restore_journal").exists())
    }

    @Test
    fun recover_restoresOldDocumentsWhenLiveDirectoryIsMissing() {
        val filesDir = temporaryFolder.newFolder("files")
        val backup = File(filesDir, ".restore_backup_abc").apply { mkdirs() }
        File(backup, "old.pdf").writeText("old")
        BackupRecovery.write(
            filesDir,
            BackupRecovery.Journal(
                stagingName = ".restore_staging_abc",
                backupName = backup.name,
            ),
        )

        BackupRecovery.recover(filesDir)

        assertEquals(
            "old",
            File(File(filesDir, BackupTables.DOCUMENTS_ROOT), "old.pdf").readText(),
        )
        assertFalse(File(filesDir, ".restore_journal").exists())
    }

    @Test
    fun unsafeJournalNames_areRejectedAndJournalCleared() {
        val filesDir = temporaryFolder.newFolder("files")
        File(filesDir, ".restore_journal").writeText(
            "version=1\nstaging=../evil\nbackup=.restore_backup_abc\n",
        )

        BackupRecovery.recover(filesDir)

        assertFalse(File(filesDir, ".restore_journal").exists())
        assertTrue(filesDir.listFiles().orEmpty().isEmpty())
    }
}
