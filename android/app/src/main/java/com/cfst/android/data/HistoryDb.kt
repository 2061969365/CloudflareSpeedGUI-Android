package com.cfst.android.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val ipCount: Int,
    val resultCount: Int,
    val fastestMs: Long?,
    val regionsSummary: String,
    val recordsCsv: String,
)

@Dao
interface HistoryDao {
    @Insert suspend fun insert(entry: HistoryEntry): Long
    @Query("SELECT * FROM history ORDER BY startedAt DESC") fun getAll(): Flow<List<HistoryEntry>>
    @Query("DELETE FROM history WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM history WHERE startedAt < :ts") suspend fun deleteOlderThan(ts: Long)
    @Query("DELETE FROM history") suspend fun clearAll()
}

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = false)
abstract class HistoryDb : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}