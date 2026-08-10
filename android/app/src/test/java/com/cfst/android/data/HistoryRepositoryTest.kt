package com.cfst.android.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryRepositoryTest {

    private lateinit var db: HistoryDb
    private lateinit var repo: HistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, HistoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = HistoryRepository(db.historyDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(startedAt: Long, recordsCsv: String = "") = HistoryEntry(
        startedAt = startedAt,
        ipCount = 3,
        resultCount = 2,
        fastestMs = 42,
        regionsSummary = "HKG:2;LAX:1",
        recordsCsv = recordsCsv,
    )

    @Test
    fun insertAndGetSummaries_emitsInDescOrder() = runTest {
        repo.add(entry(1000))
        repo.add(entry(3000))
        repo.add(entry(2000))
        val summaries = repo.summaries.first()
        assertEquals(listOf(3000L, 2000L, 1000L), summaries.map { it.startedAt })
    }

    @Test
    fun insert_returnsGeneratedId() = runTest {
        val id = repo.add(entry(1000))
        assertTrue(id > 0)
        assertEquals(listOf(id), repo.summaries.first().map { it.id })
    }

    @Test
    fun summaries_doNotCarryRecordsCsvBlob() = runTest {
        val id = repo.add(entry(1000, recordsCsv = "1.1.1.1#443".repeat(1000)))
        val summary = repo.summaries.first().single()
        assertEquals(id, summary.id)
    }

    @Test
    fun recordsCsv_returnsStoredCsvById() = runTest {
        val id = repo.add(entry(1000, recordsCsv = "1.1.1.1#443;2.2.2.2#443"))
        assertEquals("1.1.1.1#443;2.2.2.2#443", repo.recordsCsv(id))
        assertNull(repo.recordsCsv(id + 999))
    }

    @Test
    fun deleteById_removesEntry() = runTest {
        repo.add(entry(1000))
        repo.add(entry(2000))
        val id = repo.add(entry(3000))
        repo.delete(id)
        assertEquals(listOf(2000L, 1000L), repo.summaries.first().map { it.startedAt })
    }

    @Test
    fun deleteOlderThan_removesOnlyOlder() = runTest {
        repo.add(entry(1000))
        repo.add(entry(2000))
        repo.deleteOlderThan(1500)
        assertEquals(listOf(2000L), repo.summaries.first().map { it.startedAt })
    }

    @Test
    fun clearAll_emptiesTable() = runTest {
        repo.add(entry(1000))
        repo.add(entry(2000))
        repo.clearAll()
        assertTrue(repo.summaries.first().isEmpty())
    }
}
