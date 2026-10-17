package com.relay.app.media

import java.util.Base64
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

/** Private, versioned display names; never public relay metadata.
 * Uses the existing profile-control prefix so 1.1 clients ignore this extension rather than
 * displaying an unknown control message as chat text.
 */
object ProfileNameBody {
    private const val PREFIX = "TYPE:PROFILE_PHOTO|KIND:NAME|"
    data class Update(val revision: Long, val name: String)
    fun isControl(body: String) = body.startsWith(PREFIX)
    fun format(update: Update): String {
        require(update.revision >= 0 && validName(update.name))
        return "${PREFIX}REV:${update.revision}|NAME:${Base64.getEncoder().encodeToString(update.name.toByteArray(Charsets.UTF_8))}"
    }
    fun parse(body: String): Update? = runCatching {
        require(body.length <= 256 && isControl(body))
        val parts = body.removePrefix(PREFIX).split('|')
        require(parts.size == 2 && parts[0].startsWith("REV:") && parts[1].startsWith("NAME:"))
        val revision = parts[0].removePrefix("REV:").toLong()
        require(revision >= 0)
        val bytes = Base64.getDecoder().decode(parts[1].removePrefix("NAME:"))
        val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        require(validName(name))
        Update(revision, name)
    }.getOrNull()
    private fun validName(name: String) = name.length in 1..30 && name == name.trim() &&
        name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
}
