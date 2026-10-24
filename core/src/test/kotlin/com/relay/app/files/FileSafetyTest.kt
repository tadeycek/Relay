package com.relay.app.files

import org.junit.Assert.*
import org.junit.Test

class FileSafetyTest {
    private fun safe(s: String?) = FileSafety.safeName(s)

    @Test fun ordinaryNamesPassUnchanged() {
        assertEquals("report.pdf", safe("report.pdf"))
        assertEquals("Ünïcode 文件.txt", safe("Ünïcode 文件.txt"))
    }

    @Test fun separatorsAreStrippedSoNameIsNeverAPath() {
        assertEquals("_....etcpasswd", safe("../../etc/passwd"))
        assertEquals("ab.txt", safe("a/b.txt")); assertEquals("ab.txt", safe("a\\b.txt"))
        assertFalse(safe("..\\..\\x").contains("\\"))
    }

    @Test fun controlAndFormatCharactersAreStripped() {
        assertEquals("ab.txt", safe("a\u0000b\n.txt"))
        assertEquals("exe.txt", safe("e\u202Exe.txt")) // right-to-left override
        assertEquals("ab", safe("a\u200Bb")); assertEquals("ab", safe("a\u2028b")); assertEquals("ab", safe("a\u007Fb"))
        assertEquals("ab", safe("a|b"))
    }

    @Test fun leadingDotsAreMadeVisible() {
        assertEquals("_.bashrc", safe(".bashrc"))
        assertEquals("_..hidden", safe("..hidden"))
        assertFalse(safe(".ssh").startsWith("."))
    }

    @Test fun emptyOrUselessNamesBecomeTheDefault() {
        for (n in listOf(null, "", " ", "/", "\u0000", ".", "..", "...", "\u202E", "  .  ")) assertEquals("file", safe(n))
    }

    @Test fun windowsHazardsAreNeutralised() {
        assertEquals("_CON", safe("CON")); assertEquals("_nul.txt", safe("nul.txt"))
        assertEquals("a", safe("a. ")); assertEquals("abc", safe("a<b>c"))
    }

    @Test fun resultNeverExceeds255Utf8BytesAndStaysValidUtf16() {
        val long = "x".repeat(300) + ".pdf"
        val s = safe(long); assertTrue(s.toByteArray().size <= 255); assertTrue(s.endsWith(".pdf"))
        val emoji = "😀".repeat(100) + ".png"
        val e = safe(emoji); assertTrue(e.toByteArray().size <= 255); assertTrue(e.endsWith(".png"))
        assertFalse(e.any { Character.isSurrogate(it) && !(e.codePoints().allMatch { cp -> Character.isValidCodePoint(cp) }) })
        val dots = safe("." + "y".repeat(254)); assertTrue(dots.toByteArray().size <= 255)
    }

    @Test fun safeNameIsIdempotent() {
        for (n in listOf(".x", "../a", "CON", "a\u202Eb", "😀".repeat(200), "x".repeat(400))) assertEquals(safe(n), safe(safe(n)))
    }

    @Test fun mimeSanitising() {
        assertEquals("image/png", FileSafety.safeMime("image/png"))
        assertEquals("image/png", FileSafety.safeMime("IMAGE/PNG"))
        assertEquals("application/vnd.api+json", FileSafety.safeMime("application/vnd.api+json"))
        for (m in listOf("", "png", "a/b/c", "a/", "/b", "text/html; charset=x", "te xt/plain", "a/" + "b".repeat(100), "é/x", "a/b\n"))
            assertEquals(m, "application/octet-stream", FileSafety.safeMime(m))
        assertEquals("application/octet-stream", FileSafety.safeMime(null))
        assertEquals(100, FileSafety.safeMime("a/" + "b".repeat(98)).length)
    }

    @Test fun executableExtensions() {
        for (n in listOf("a.desktop", "a.sh", "a.AppImage", "a.app", "a.command", "a.exe", "a.bat", "a.cmd", "a.msi", "a.apk", "a.jar", "a.scr", "A.EXE", "x.appimage"))
            assertTrue(n, FileSafety.isExecutableLike(n))
        for (n in listOf("a.txt", "exe", "sh", "a.exe.txt", "a.shx", "notes.jpg", "app")) assertFalse(n, FileSafety.isExecutableLike(n))
    }

    @Test fun executableMagic() {
        val elf = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1)
        assertTrue(FileSafety.isExecutableLike("photo.jpg", elf))
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        for (m in listOf(b(0xfe, 0xed, 0xfa, 0xce), b(0xfe, 0xed, 0xfa, 0xcf), b(0xce, 0xfa, 0xed, 0xfe), b(0xcf, 0xfa, 0xed, 0xfe), b(0xca, 0xfe, 0xba, 0xbe)))
            assertTrue(FileSafety.isExecutableLike("x.bin", m))
        assertTrue(FileSafety.isExecutableLike("x.txt", "#!/bin/sh\n".toByteArray()))
        assertFalse(FileSafety.isExecutableLike("x.txt", "hello world".toByteArray()))
        assertFalse(FileSafety.isExecutableLike("x.txt", byteArrayOf(0x7f, 'E'.code.toByte())))
        assertFalse(FileSafety.isExecutableLike("x.txt", ByteArray(0)))
        assertFalse(FileSafety.isExecutableLike("x.txt", null))
    }
}
