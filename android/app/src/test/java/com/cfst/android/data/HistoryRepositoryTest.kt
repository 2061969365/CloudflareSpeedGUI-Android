package com.cfst.android.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun entry(startedAt: Long) = HistoryEntry(
        startedAt = startedAt,
        ipCount = 3,
        resultCount = 2,
        fastestMs = 42,
        regionsSummary = "HKG:2;LAX:1",
        recordsCsv = "",
    )

    @Test
    fun insertAndGetAll_emitsInDescOrder() = runTest {
        repo.add(entry(1000))
        repo.add(entry(3000))
        repo.add(entry(2000))
        val all = repo.all.first()
        assertEquals(listOf(3000L, 2000L, 1000L), all.map { it.startedAt })
    }

    @Test
    fun insert_returnsGeneratedId() = runTest {
        val id = repo.add(entry(1000))
        assertTrue(id > 0)
        assertEquals(listOf(id), repo.all.first().map { it.id })
    }

    @Test
    fun deleteById_removesEntry() = runTest {
        repo.add(entry(1000))
        repo.add(entry(2000))
        val id = repo.add(entry(3000))
        repo.delete(id)
        assertEquals(listOf(2000L, 1000L), repo.all.first().map { it.startedAt })
    }

    @Test
    fun deleteOlderThan_removesOnlyOlder() = runTest {
        repo.add(entry(1000))
        repo.add(entry(2000))
        repo.deleteOlderThan(1500)
        assertEquals(listOf(2000L), repo.all.first().map { it.startedAt })
    }

    @Test
    fun clearAll_emptiesTable() = runTest {
        repo.add(entry(1000))
        repo.add(entry(2000))
        repo.clearAll()
        assertTrue(repo.all.first().isEmpty())
    }
}