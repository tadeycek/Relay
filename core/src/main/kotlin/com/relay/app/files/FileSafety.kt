package com.relay.app.files

import java.util.Locale

/** Pure helpers that make hostile file metadata safe to display and save. Nothing here touches a file system. */
object FileSafety {
    const val MAX_NAME_BYTES = 255
    const val DEFAULT_NAME = "file"
    const val DEFAULT_MIME = "application/octet-stream"
    const val MAX_MIME_LENGTH = 100

    private const val STRIPPED_PUNCTUATION = "/\\|<>:\"?*"
    private val MIME = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
    private val EXEC_EXTENSIONS = setOf("desktop", "sh", "appimage", "app", "command", "exe", "bat", "cmd", "msi", "apk", "jar", "scr")

    /**
     * A name that is safe as ONE path component on Linux, macOS and Windows and as a UI label: control and
     * format characters (including bidi overrides and zero-width characters), line/paragraph separators,
     * unpaired surrogates, path separators and Windows-illegal punctuation (and `|`, the wire separator) are
     * stripped; edge whitespace and trailing dots are trimmed; a leading dot gets a visible `_` in front (never
     * a hidden file); Windows device names get a `_` prefix; the result is at most [MAX_NAME_BYTES] UTF-8 bytes
     * (the extension is kept); an empty result becomes [DEFAULT_NAME]. Idempotent.
     */
    fun safeName(raw: String?): String {
        if (raw == null) return DEFAULT_NAME
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i); i += Character.charCount(cp)
            if (cp < 0x80 && STRIPPED_PUNCTUATION.indexOf(cp.toChar()) >= 0) continue
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                Character.SURROGATE, Character.UNASSIGNED -> continue
            }
            sb.appendCodePoint(cp)
        }
        var s = trimEnds(sb.toString())
        if (s.isEmpty()) return DEFAULT_NAME
        if (s.startsWith(".")) s = "_$s"
        if (s.substringBefore('.').uppercase(Locale.ROOT) in RESERVED) s = "_$s"
        if (s.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) s = truncate(s)
        return if (s.isEmpty()) DEFAULT_NAME else s
    }

    private fun isSpace(cp: Int) = Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    /** Trims whitespace at the start, and whitespace and dots at the end (Windows drops those silently). */
    private fun trimEnds(s: String): String {
        var start = 0
        while (start < s.length && isSpace(s.codePointAt(start))) start += Character.charCount(s.codePointAt(start))
        var end = s.length
        while (end > start) {
            val cp = s.codePointBefore(end)
            if (cp == '.'.code || isSpace(cp)) end -= Character.charCount(cp) else break
        }
        return s.substring(start, end)
    }

    private fun truncate(s: String): String {
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && s.length - dot <= 20) s.substring(dot) else ""
        val stem = StringBuilder(s.substring(0, s.length - ext.length))
        val budget = MAX_NAME_BYTES - ext.toByteArray(Charsets.UTF_8).size
        while (stem.isNotEmpty() && stem.toString().toByteArray(Charsets.UTF_8).size > budget) {
            stem.setLength(stem.length - Character.charCount(stem.codePointBefore(stem.length)))
        }
        return trimEnds(stem.toString()) + ext
    }

    /** `type/subtype` over `[a-z0-9.+-]`, at most [MAX_MIME_LENGTH] characters; anything else is [DEFAULT_MIME]. */
    fun safeMime(raw: String?): String {
        val m = raw?.lowercase(Locale.ROOT) ?: return DEFAULT_MIME
        return if (m.length <= MAX_MIME_LENGTH && MIME.matches(m)) m else DEFAULT_MIME
    }

    /**
     * Heuristic for "warn before opening": a risky extension on the (last) extension, or ELF / Mach-O (thin,
     * fat/universal) / `#!` script magic in the first bytes. It is a warning aid, not a security boundary.
     */
    fun isExecutableLike(name: String, head: ByteArray? = null): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot >= 0 && name.substring(dot + 1).lowercase(Locale.ROOT) in EXEC_EXTENSIONS) return true
        if (head == null) return false
        fun at(vararg v: Int) = head.size >= v.size && v.indices.all { (head[it].toInt() and 0xff) == v[it] }
        return at(0x7f, 0x45, 0x4c, 0x46) ||
            at(0xfe, 0xed, 0xfa, 0xce) || at(0xfe, 0xed, 0xfa, 0xcf) || at(0xce, 0xfa, 0xed, 0xfe) || at(0xcf, 0xfa, 0xed, 0xfe) ||
            at(0xca, 0xfe, 0xba, 0xbe) || at('#'.code, '!'.code)
    }
}
