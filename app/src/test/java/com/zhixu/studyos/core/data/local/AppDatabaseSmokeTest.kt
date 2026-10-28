package com.zhixu.studyos.core.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.repository.OfflineFinanceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppDatabaseSmokeTest {
    @Test
    fun fileDatabase_opensAndSeedsFinanceCategories() = runBlocking {
        val db = StudyOSDatabase.build(ApplicationProvider.getApplicationContext())
        db.openHelper.writableDatabase
        val categories = db.financeDao().getCategories()
        assertEquals(7, categories.size)
        assertEquals("餐饮", categories.first().name)
        val snapshot = withTimeout(5_000) {
            OfflineFinanceRepository(db, db.financeDao()).observeSnapshot().first()
        }
        assertEquals(7, snapshot.categories.size)
        db.close()
    }
}
