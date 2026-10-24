package com.relay.app.call

/**
 * A deliberately small, strict RFC 8259 reader for call bodies. It exists because `org.json` differs between the
 * Android platform and the desktop artifact and is lenient (single quotes, comments, unquoted keys). Bounds: depth,
 * members, array length and string length. Integers of up to 15 digits become [Long]; every other number is a
 * [Double] and is rejected by field validation. Duplicate object keys are a parse failure. Never allocates more than
 * the bounded input.
 */
internal object StrictJson {
    object Null

    class Fail : Exception(null, null, false, false)

    private const val MAX_DEPTH = 4
    private const val MAX_MEMBERS = 24
    private const val MAX_ITEMS = 64
    private const val MAX_STRING = 4096

    /** Returns Map<String, Any>, List<Any>, String, Long, Double, Boolean or [Null]; throws [Fail]. */
    fun parse(text: String): Any {
        val r = Reader(text)
        r.ws()
        val v = r.value(1)
        r.ws()
        if (r.i != text.length) throw Fail()
        return v
    }

    private class Reader(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }

        fun value(depth: Int): Any {
            if (i >= s.length) throw Fail()
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", Null)
                else -> if (c == '-' || c in '0'..'9') num() else throw Fail()
            }
        }

        fun lit(word: String, v: Any): Any {
            if (!s.startsWith(word, i)) throw Fail()
            i += word.length
            return v
        }

        fun obj(depth: Int): Any {
            if (depth > MAX_DEPTH) throw Fail()
            i++
            val m = LinkedHashMap<String, Any>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw Fail()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Fail()
                i++
                ws()
                val v = value(depth + 1)
                if (m.put(k, v) != null || m.size > MAX_MEMBERS) throw Fail()
                ws()
                if (i >= s.length) throw Fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw Fail()
            }
        }

        fun arr(depth: Int): Any {
            if (depth > MAX_DEPTH) throw Fail()
            i++
            val l = ArrayList<Any>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value(depth + 1))
                if (l.size > MAX_ITEMS) throw Fail()
                ws()
                if (i >= s.length) throw Fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw Fail()
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw Fail()
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw Fail()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Fail()
                                var code = 0
                                for (k in 0 until 4) {
                                    val d = Character.digit(s[i + k], 16)
                                    if (d < 0) throw Fail()
                                    code = code * 16 + d
                                }
                                i += 4
                                sb.append(code.toChar())
                            }
                            else -> throw Fail()
                        }
                    }
                    c < ' ' -> throw Fail()
                    else -> sb.append(c)
                }
                if (sb.length > MAX_STRING) throw Fail()
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) throw Fail()
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ } else throw Fail()
            var integral = true
            if (i < s.length && s[i] == '.') {
                integral = false; i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) throw Fail()
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) throw Fail()
            }
            val tok = s.substring(start, i)
            if (tok.length > 32) throw Fail()
            return if (integral && tok.trimStart('-').length <= 15) tok.toLong() else tok.toDouble()
        }
    }
}
