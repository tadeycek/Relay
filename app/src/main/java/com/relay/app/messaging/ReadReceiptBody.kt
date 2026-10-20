package com.relay.app.messaging

import com.relay.app.util.SmsMessageParser
import java.util.UUID

object ReadReceiptBody {
    const val MAX_IDS = 64

    fun parseIds(value: String): List<String>? = runCatching {
        require(value.length <= MAX_IDS * 37)
        val ids = value.split(',')
        require(ids.size in 1..MAX_IDS && ids.distinct().size == ids.size)
        require(ids.all { UUID.fromString(it).toString() == it })
        ids
    }.getOrNull()

    fun encodeIds(ids: List<String>): String = ids.joinToString(",").also {
        require(parseIds(it) != null)
    }

    fun format(timestamp: Long, ids: List<String>): String {
        require(timestamp > 0)
        return SmsMessageParser.formatReadReceipt(timestamp) + "|IDS:" + encodeIds(ids)
    }
}
