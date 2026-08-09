package com.cfst.android.engine

import java.util.Locale

object ColoRegionMapper {

    private val REGIONS = mapOf(
        "HKG" to "中国香港",
        "LAX" to "美国洛杉矶",
        "SJC" to "美国圣何塞",
        "SEA" to "美国西雅图",
        "NRT" to "日本东京成田",
        "HND" to "日本东京羽田",
        "FRA" to "德国法兰克福",
        "LHR" to "英国伦敦",
        "AMS" to "荷兰阿姆斯特丹",
        "SIN" to "新加坡",
        "KHH" to "中国台湾高雄",
        "SGN" to "越南胡志明",
        "BKK" to "泰国曼谷",
        "KUL" to "马来西亚吉隆坡",
        "KIX" to "日本大阪",
        "ICN" to "韩国首尔",
        "CDG" to "法国巴黎",
        "WAW" to "波兰华沙",
        "MAD" to "西班牙马德里",
        "BCN" to "西班牙巴塞罗那",
        "ARN" to "瑞典斯德哥尔摩",
        "OSL" to "挪威奥斯陆",
        "GIG" to "巴西里约",
        "GRU" to "巴西圣保罗",
        "EZE" to "阿根廷布宜诺斯艾利斯",
        "SCL" to "智利圣地亚哥",
        "JNB" to "南非约翰内斯堡",
        "MIA" to "美国迈阿密",
        "ATL" to "美国亚特兰大",
        "IAD" to "美国华盛顿",
        "ORD" to "美国芝加哥",
        "DFW" to "美国达拉斯",
        "PHX" to "美国凤凰城",
        "DEN" to "美国丹佛",
        "EWR" to "美国纽约",
        "YYZ" to "加拿大多伦多",
        "YUL" to "加拿大蒙特利尔"
    )

    fun map(colo: String): String {
        if (colo.isEmpty()) return "未知"
        val key = colo.uppercase(Locale.US)
        return REGIONS[key] ?: key
    }
}
