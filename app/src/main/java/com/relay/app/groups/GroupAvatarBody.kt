package com.relay.app.groups

import com.relay.app.media.ProfilePhotoBody
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/** Owner-authenticated private avatar control; never changes membership. */
object GroupAvatarBody {
    const val PREFIX = "TYPE:PROFILE_PHOTO|KIND:GROUP_AVATAR|"
    data class Update(val group: String, val revision: Long, val jpeg: ByteArray?)
    fun format(update: Update): String {
        require(UUID.fromString(update.group).toString() == update.group && update.revision >= 0)
        require(update.jpeg == null || update.jpeg.size in 1..ProfilePhotoBody.MAX_BYTES)
        return PREFIX + JSONObject().put("group", update.group).put("revision", update.revision)
            .put("jpeg", update.jpeg?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()).toString()
    }
    fun parse(body: String): Update? = runCatching {
        require(body.startsWith(PREFIX) && body.length <= 11500)
        val o = JSONObject(body.removePrefix(PREFIX))
        val group = o.getString("group"); require(UUID.fromString(group).toString() == group)
        val revision = o.getLong("revision"); require(revision >= 0)
        val text = o.getString("jpeg")
        val jpeg = if (text.isBlank()) null else Base64.getDecoder().decode(text)
        require(jpeg == null || (jpeg.size in 3..ProfilePhotoBody.MAX_BYTES && jpeg[0] == (-1).toByte() && jpeg[1] == (-40).toByte()))
        Update(group, revision, jpeg)
    }.getOrNull()
}
