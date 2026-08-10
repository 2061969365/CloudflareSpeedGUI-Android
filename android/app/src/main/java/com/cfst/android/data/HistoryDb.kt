package com.cfst.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val startedAt: Long,
    val ipCount: Int,
    val resultCount: Int,
    val fastestMs: Long?,
    val regionsSummary: String,
    val recordsCsv: String,
)

@Dao
interface HistoryDao {
    @Insert suspend fun insert(entry: HistoryEntry): Long
    @Query("SELECT id, startedAt, ipCount, resultCount, fastestMs, regionsSummary FROM history ORDER BY startedAt DESC")
    fun getAllSummaries(): Flow<List<HistorySummary>>
    @Query("SELECT recordsCsv FROM history WHERE id = :id") suspend fun recordsCsv(id: Long): String?
    @Query("DELETE FROM history WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM history WHERE startedAt < :ts") suspend fun deleteOlderThan(ts: Long)
    @Query("DELETE FROM history") suspend fun clearAll()
}

data class HistorySummary(
    val id: Long,
    val startedAt: Long,
    val ipCount: Int,
    val resultCount: Int,
    val fastestMs: Long?,
    val regionsSummary: String,
)

@Database(entities = [HistoryEntry::class], version = 2, exportSchema = true)
abstract class HistoryDb : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        // v1→v2：startedAt 加索引。索引名须与 Room 自动生成规则
        // index_<table>_<column> 一致，否则 schema 校验不通过。
        // 需由 AppContainer（CfApp.kt，WP-A1）以 .addMigrations(HistoryDb.MIGRATIONS) 接线并移除 fallbackToDestructiveMigration。
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_startedAt ON history (startedAt)")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}