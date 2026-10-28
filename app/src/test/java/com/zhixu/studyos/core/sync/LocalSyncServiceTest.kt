package com.zhixu.studyos.core.sync

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.export.AppFileStore
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.StudyTaskEntity
import com.zhixu.studyos.core.data.local.TaskSessionEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocalSyncServiceTest {
    private lateinit var deviceA: StudyOSDatabase
    private lateinit var deviceB: StudyOSDatabase
    private lateinit var fileStore: InMemoryFileStore
    private lateinit var serviceA: LocalSyncService
    private lateinit var serviceB: LocalSyncService

    private val clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val zone = ZoneId.of("Asia/Shanghai")
    private val scopes = setOf(SyncScope.TASKS)

    @Before
    fun setUp() {
        deviceA = inMemoryDatabase()
        deviceB = inMemoryDatabase()
        fileStore = InMemoryFileStore()
        serviceA = LocalSyncService(
            deviceA,
            deviceA.syncDao(),
            fileStore,
            clock,
            zone,
            InMemorySyncWatermarkStore(),
        )
        serviceB = LocalSyncService(
            deviceB,
            deviceB.syncDao(),
            fileStore,
            clock,
            zone,
            InMemorySyncWatermarkStore(),
        )
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    @Test
    fun export_advancesWatermarksSoSecondExportIsIncremental() = runTest {
        deviceA.studyTaskDao().insert(task("A 的任务"))
        assertEquals(1, serviceA.buildBundle(scopes).objects.size)

        serviceA.exportEncryptedBundle(
            uri = Uri.parse("content://studyos/test-export"),
            passphrase = "12345678".toCharArray(),
            scopes = scopes,
        )

        assertEquals(0, serviceA.buildBundle(scopes).objects.size)
    }

    @Test
    fun keepLocal_doesNotGetSilentlyOverwrittenAndIsExportedNextTime() = runTest {
        deviceA.studyTaskDao().insert(task("本地版本"))
        deviceB.studyTaskDao().insert(task("远端版本"))
        val remoteBundle = serviceB.buildBundle(scopes)

        val preview = serviceA.preview(remoteBundle)
        assertEquals(1, preview.conflicts.size)

        serviceA.applyPreview(
            preview = preview,
            resolutions = mapOf("TASK:1" to ConflictResolution.KEEP_LOCAL),
        )
        assertEquals("本地版本", deviceA.studyTaskDao().getTask(1)?.title)

        // 同一个远端包再次导入：不能把 KEEP_LOCAL 的结果静默覆盖掉。
        val secondPreview = serviceA.preview(remoteBundle)
        assertTrue(secondPreview.conflicts.isEmpty())
        assertTrue(secondPreview.updatedObjects.isEmpty())
        assertEquals("本地版本", deviceA.studyTaskDao().getTask(1)?.title)

        // 本地版本应被视为待导出，而不是因“基线等于本地”永远不再发送。
        val localBundle = serviceA.buildBundle(scopes)
        assertEquals(1, localBundle.objects.size)
        assertEquals(
            "本地版本",
            JSONObject(localBundle.objects.single().payloadJson)
                .getJSONObject("row")
                .getString("title"),
        )
    }

    @Test
    fun export_doesNotAdvanceConflictBaseline() = runTest {
        val base = inMemoryDatabase()
        val baseService = LocalSyncService(
            base,
            base.syncDao(),
            fileStore,
            clock,
            zone,
            InMemorySyncWatermarkStore(),
        )
        base.studyTaskDao().insert(task("共同基线"))
        val baseBundle = baseService.buildBundle(scopes)

        serviceA.applyPreview(serviceA.preview(baseBundle), emptyMap())
        serviceB.applyPreview(serviceB.preview(baseBundle), emptyMap())

        val taskA = deviceA.studyTaskDao().getTask(1)!!
        deviceA.studyTaskDao().update(taskA.copy(title = "A 的修改", updatedAtEpochMillis = 2_000))
        val taskB = deviceB.studyTaskDao().getTask(1)!!
        deviceB.studyTaskDao().update(taskB.copy(title = "B 的修改", updatedAtEpochMillis = 2_000))

        // A 先导出了自己的版本；导出水位变化但冲突基线不能变。
        serviceA.exportEncryptedBundle(
            uri = Uri.parse("content://studyos/export-a"),
            passphrase = "12345678".toCharArray(),
            scopes = scopes,
        )
        val bundleB = serviceB.buildBundle(scopes)

        val preview = serviceA.preview(bundleB)
        assertEquals(1, preview.conflicts.size)
        base.close()
    }

    @Test
    fun legacyBundleHashFromBeforeChildIdNormalization_isAccepted() = runTest {
        val taskId = deviceB.studyTaskDao().insert(task("旧版本同步包"))
        deviceB.studyTaskDao().insertSessions(listOf(session(taskId, 20_000, 8 * 60, 9 * 60)))
        val bundle = serviceB.buildBundle(scopes)
        val objectWithLegacyHash = bundle.objects.single().copy(
            payloadHash = SyncPayloadHasher.hashLegacy(bundle.objects.single().payloadJson),
        )
        val legacyBundle = bundle.copy(objects = listOf(objectWithLegacyHash))

        val preview = serviceA.preview(legacyBundle)

        assertEquals(1, preview.newObjects.size)
    }

    @Test
    fun importedChildSessionIds_doNotCauseEndlessReapply() = runTest {
        val taskId = deviceB.studyTaskDao().insert(task("带时段的任务"))
        deviceB.studyTaskDao().insertSessions(listOf(session(taskId, 20_000, 8 * 60, 9 * 60)))
        val remoteBundle = serviceB.buildBundle(scopes)

        val preview = serviceA.preview(remoteBundle)
        assertEquals(1, preview.newObjects.size)
        serviceA.applyPreview(preview, emptyMap())

        // 导入时 task_sessions 会自动分配新 id；hash 必须忽略子行 id，否则这里会
        // 永远被判定为“有更新”并反复重建会话。
        val secondPreview = serviceA.preview(remoteBundle)
        assertTrue(secondPreview.newObjects.isEmpty())
        assertTrue(secondPreview.updatedObjects.isEmpty())
        assertTrue(secondPreview.conflicts.isEmpty())
    }

    private fun inMemoryDatabase(): StudyOSDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()

    private fun task(title: String) = StudyTaskEntity(
        title = title,
        description = "",
        subject = "数学分析",
        priority = "MEDIUM",
        estimatedMinutes = 60,
        dueDateEpochDay = null,
        status = "SCHEDULED",
        completedAtEpochMillis = null,
        seriesId = null,
        recurrenceType = "NONE",
        recurrenceInterval = 1,
        recurrenceUntilEpochDay = null,
        sortOrder = 0,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
    )

    private fun session(taskId: Long, day: Long, start: Int, end: Int) = TaskSessionEntity(
        taskId = taskId,
        dateEpochDay = day,
        startMinute = start,
        endMinuteExclusive = end,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
    )

    private class InMemoryFileStore : AppFileStore {
        private var bytes: ByteArray? = null

        override suspend fun writeText(uri: Uri, content: String) {
            bytes = content.toByteArray(Charsets.UTF_8)
        }

        override suspend fun <T> writeBinary(uri: Uri, writer: suspend (OutputStream) -> T): T {
            val output = ByteArrayOutputStream()
            val result = writer(output)
            bytes = output.toByteArray()
            return result
        }

        override suspend fun <T> readBinary(uri: Uri, reader: suspend (InputStream) -> T): T {
            val data = bytes ?: error("没有写入任何文件")
            return reader(ByteArrayInputStream(data))
        }

        override suspend fun describe(uri: Uri): Pair<String, Long> =
            "test-sync.studyos" to (bytes?.size?.toLong() ?: -1L)
    }
}
