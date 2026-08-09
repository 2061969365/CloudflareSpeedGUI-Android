package com.cfst.android.data

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: HistoryDao) {
    val all: Flow<List<HistoryEntry>> = dao.getAll()
    suspend fun add(entry: HistoryEntry): Long = dao.insert(entry)
    suspend fun delete(id: Long) = dao.deleteById(id)
    suspend fun deleteOlderThan(ts: Long) = dao.deleteOlderThan(ts)
    suspend fun clearAll() = dao.clearAll()
}