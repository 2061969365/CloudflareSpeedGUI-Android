package com.cfst.android.engine.model

data class ResultStats(
    val total: Int,
    val topRegions: List<Pair<String, Int>>,
    val fastestMs: Float?,
) {
    companion object {
        /**
         * Aggregates a list of scan results.
         * - total: number of records
         * - topRegions: top 3 region codes by record count, sorted desc (ties broken by code asc for determinism)
         * - fastestMs: minimum non-null avgMs, or null when no record has a measured latency
         */
        fun compute(records: List<ScanResult>): ResultStats {
            val topRegions = records
                .groupingBy { it.regionCode }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(3)
                .map { it.key to it.value }
            val fastestMs = records.mapNotNull { it.avgMs }.minOrNull()
            return ResultStats(total = records.size, topRegions = topRegions, fastestMs = fastestMs)
        }
    }
}
