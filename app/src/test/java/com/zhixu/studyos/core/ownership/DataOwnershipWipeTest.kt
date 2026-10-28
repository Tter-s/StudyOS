package com.zhixu.studyos.core.ownership

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.ai.security.ApiKeyStore
import com.zhixu.studyos.core.data.export.AppFileStore
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.StudyTaskEntity
import com.zhixu.studyos.core.data.local.SyncTriggers
import com.zhixu.studyos.core.data.preferences.AiConfigStore
import com.zhixu.studyos.core.data.preferences.UserPreferencesRepository
import com.zhixu.studyos.core.model.AiProviderConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DataOwnershipWipeTest {
    private lateinit var database: StudyOSDatabase
    private lateinit var filesDir: File
    private lateinit var service: OfflineDataOwnershipService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StudyOSDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SyncTriggers.install(database.openHelper.writableDatabase)
        filesDir = File(context.cacheDir, "wipe_test_${UUID.randomUUID()}").also { it.mkdirs() }
        service = OfflineDataOwnershipService(
            database = database,
            filesDir = filesDir,
            fileStore = NoOpFileStore,
            apiKeyStore = NoOpApiKeyStore,
            aiConfigStore = NoOpAiConfigStore,
            userPreferencesRepository = UserPreferencesRepository(context),
            clock = Clock.systemDefaultZone(),
            zoneId = ZoneId.systemDefault(),
            appVersion = "test",
        )
    }

    @After
    fun tearDown() {
        database.close()
        filesDir.deleteRecursively()
    }

    @Test
    fun wipeAllData_doesNotLeaveSyncTombstonesOrOperationsBehind() = runTest {
        database.studyTaskDao().insert(task("会被删除的任务"))
        database.studyTaskDao().deleteTask(1)
        database.studyTaskDao().insert(task("另一个任务"))
        assertTrue(count("sync_tombstones") > 0)
        assertTrue(count("sync_operations") > 0)

        val restoreTemp = File(filesDir, ".restore_staging_deadbeef").apply { mkdirs() }
        File(restoreTemp, "old.pdf").writeText("old")

        service.wipeAllData(deleteApiKey = false)

        assertEquals(0, count("study_tasks"))
        assertEquals(0, count("sync_tombstones"))
        assertEquals(0, count("sync_operations"))
        assertEquals(0, count("sync_baselines"))
        assertEquals(0, count("sync_state"))
        assertFalse(restoreTemp.exists())
        assertEquals(5, database.learningDao().getSubjects().size)
        assertEquals(7, database.financeDao().getCategories().size)
    }

    private fun count(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun task(title: String) = StudyTaskEntity(
        title = title,
        description = "",
        subject = "",
        priority = "MEDIUM",
        estimatedMinutes = 30,
        dueDateEpochDay = null,
        status = "BACKLOG",
        completedAtEpochMillis = null,
        seriesId = null,
        recurrenceType = "NONE",
        recurrenceInterval = 1,
        recurrenceUntilEpochDay = null,
        sortOrder = 0,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
    )

    private object NoOpFileStore : AppFileStore {
        override suspend fun writeText(uri: Uri, content: String) = Unit
        override suspend fun <T> writeBinary(uri: Uri, writer: suspend (OutputStream) -> T): T =
            writer(OutputStream.nullOutputStream())
        override suspend fun <T> readBinary(uri: Uri, reader: suspend (InputStream) -> T): T =
            reader(InputStream.nullInputStream())
        override suspend fun describe(uri: Uri): Pair<String, Long> = "test" to 0L
    }

    private object NoOpApiKeyStore : ApiKeyStore {
        override suspend fun save(key: String) = Unit
        override suspend fun load(): String? = null
        override suspend fun clear() = Unit
        override suspend fun hasKey(): Boolean = false
    }

    private object NoOpAiConfigStore : AiConfigStore {
        override val config: Flow<AiProviderConfig> = flowOf(AiProviderConfig.Default)
        override suspend fun getConfig(): AiProviderConfig = AiProviderConfig.Default
        override suspend fun saveConfig(config: AiProviderConfig) = Unit
        override suspend fun updatePrivacy(
            includeTaskTitles: Boolean,
            includeSubjectNames: Boolean,
            includeProblemStatements: Boolean,
        ) = Unit
    }
}
