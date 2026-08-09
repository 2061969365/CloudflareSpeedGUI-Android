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
         * - topRegions: top 3 region codes by lowest latency (min non-null avgMs within the region),
         *   sorted ascending; ties broken by code asc for determinism. Blank region codes are excluded.
         * - fastestMs: minimum non-null avgMs, or null when no record has a measured latency
         */
        fun compute(records: List<ScanResult>): ResultStats {
            val topRegions = records
                .filter { it.regionCode.isNotBlank() }
                .groupBy { it.regionCode }
                .mapNotNull { (code, list) ->
                    val bestMs = list.mapNotNull { it.avgMs }.minOrNull()
                        ?: return@mapNotNull null
                    Triple(code, list.size, bestMs)
                }
                .sortedWith(compareBy<Triple<String, Int, Float>> { it.third }.thenBy { it.first })
                .take(3)
                .map { it.first to it.second }
            val fastestMs = records.mapNotNull { it.avgMs }.minOrNull()
            return ResultStats(total = records.size, topRegions = topRegions, fastestMs = fastestMs)
        }
    }
}
