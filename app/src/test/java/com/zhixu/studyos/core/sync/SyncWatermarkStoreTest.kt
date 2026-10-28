package com.zhixu.studyos.core.sync

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncWatermarkStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fileStore_roundTripsAndClearsWatermarks() = runTest {
        val store = FileSyncWatermarkStore(temporaryFolder.newFolder("files"))

        store.save(mapOf("TASK:1" to "hash-a", "GOAL:2" to "hash-b"))
        assertEquals(
            mapOf("TASK:1" to "hash-a", "GOAL:2" to "hash-b"),
            store.load(),
        )

        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun corruptWatermarkFile_fallsBackToFullExport() = runTest {
        val filesDir = temporaryFolder.newFolder("files")
        File(filesDir, FileSyncWatermarkStore.FILE_NAME).writeText("not-json")

        assertTrue(FileSyncWatermarkStore(filesDir).load().isEmpty())
    }
}
