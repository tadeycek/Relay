package com.relay.app.nfc

import com.relay.app.util.QrContactCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Type4TagTest {

    private fun tagFor(text: String?): Type4Tag.Emulator = Type4Tag.Emulator {
        text?.let { Type4Tag.ndefFile(Type4Tag.ndefMimeMessage(Type4Tag.MIME_TYPE, it.toByteArray())) }
    }

    private fun read(emulator: Type4Tag.Emulator) = Type4Tag.readContactCode(emulator::process)

    private val npub = "b".repeat(64)
    private val nonce = "0123456789abcdef0123456789abcdef"

    @Test
    fun aRealContactCodeSurvivesTheRoundTrip() {
        val code = QrContactCode.encode(
            "Bea", npub, "K".repeat(180), "S".repeat(90),
            relayHints = listOf("wss://relay.one.example", "wss://relay.two.example", "wss://relay.three.example"),
            pairNonce = nonce,
        )
        assertTrue("long enough to need several reads", code.length > Type4Tag.MAX_READ)
        assertEquals(code, read(tagFor(code)))
        assertEquals(nonce, QrContactCode.decode(read(tagFor(code))!!)!!.pairNonce)
    }

    @Test
    fun shortAndLongRecordsBothRoundTrip() {
        for (n in listOf(1, 100, 255, 256, 257, 1000, 4000)) {
            val text = "x".repeat(n)
            assertEquals("length $n", text, read(tagFor(text)))
        }
    }

    @Test
    fun nonAsciiNamesSurvive() {
        val text = "RELAYQR:4|NAME:Žiga Šuštar 🙂"
        assertEquals(text, read(tagFor(text)))
    }

    @Test
    fun aTagWithNothingToShareDoesNotAnswer() {
        assertNull(read(tagFor(null)))
    }

    @Test
    fun theTagStopsAnsweringOnceThereIsNothingToShare() {
        var text: String? = "hello"
        val e = Type4Tag.Emulator { text?.let { Type4Tag.ndefFile(Type4Tag.ndefMimeMessage(Type4Tag.MIME_TYPE, it.toByteArray())) } }
        assertEquals("hello", read(e))
        text = null
        e.reset()
        assertNull(read(e))
    }

    @Test
    fun aReaderThatSelectsTheWrongApplicationIsRefused() {
        val e = tagFor("hello")
        val wrong = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x03, 1, 2, 3, 0x00)
        assertFalse(Type4Tag.isOk(e.process(wrong)))
    }

    @Test
    fun filesCannotBeReadBeforeTheApplicationIsSelected() {
        val e = tagFor("hello")
        val selectCc = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x03)
        assertFalse(Type4Tag.isOk(e.process(selectCc)))
    }

    @Test
    fun unknownCommandsAndOutOfRangeReadsAreRefusedNotCrashed() {
        val e = tagFor("hello")
        assertFalse(Type4Tag.isOk(e.process(byteArrayOf())))
        assertFalse(Type4Tag.isOk(e.process(byteArrayOf(0, 1, 2))))
        assertFalse(Type4Tag.isOk(e.process(byteArrayOf(0x00, 0xD6.toByte(), 0, 0, 1, 1)))) // a write
        val reader = { c: ByteArray -> e.process(c) }
        Type4Tag.readContactCode(reader) // leaves the NDEF file selected
        assertFalse(Type4Tag.isOk(e.process(byteArrayOf(0x00, 0xB0.toByte(), 0x7F, 0xFF.toByte(), 0x10))))
    }

    @Test
    fun aReaderRejectsATagThatIsNotOurs() {
        val foreign = { _: ByteArray -> byteArrayOf(0x6A, 0x82.toByte()) }
        assertNull(Type4Tag.readContactCode(foreign))
    }

    @Test
    fun aReaderRejectsAnAbsurdlyLargeTag() {
        // A tag that claims 60 KB of NDEF must not make the reader loop or allocate.
        val e = Type4Tag.Emulator { byteArrayOf(0xEA.toByte(), 0x60) + ByteArray(10) }
        assertNull(Type4Tag.readNdefMessage(e::process))
    }

    @Test
    fun aReaderGivesUpOnATagThatStopsAnswering() {
        var calls = 0
        val flaky = { c: ByteArray -> if (++calls > 4) byteArrayOf(0x6F, 0x00) else tagFor("hello").process(c) }
        assertNull(Type4Tag.readContactCode(flaky))
    }

    @Test
    fun aRecordOfAnotherMimeTypeIsNotAContactCode() {
        val other = Type4Tag.ndefMimeMessage("text/plain", "hi".toByteArray())
        assertNull(Type4Tag.payloadOfMimeRecord(other, Type4Tag.MIME_TYPE))
    }

    @Test
    fun truncatedOrGarbageNdefIsRejected() {
        val good = Type4Tag.ndefMimeMessage(Type4Tag.MIME_TYPE, "hello world".toByteArray())
        for (cut in 0 until good.size) {
            assertNull("cut at $cut", Type4Tag.payloadOfMimeRecord(good.copyOf(cut), Type4Tag.MIME_TYPE))
        }
        assertNull(Type4Tag.payloadOfMimeRecord(byteArrayOf(0x01, 0x02, 0x03, 0x04), Type4Tag.MIME_TYPE))
    }

    @Test
    fun theNdefFileStartsWithItsLength() {
        val msg = Type4Tag.ndefMimeMessage(Type4Tag.MIME_TYPE, ByteArray(300))
        val file = Type4Tag.ndefFile(msg)
        assertEquals(msg.size, ((file[0].toInt() and 0xFF) shl 8) or (file[1].toInt() and 0xFF))
        assertArrayEquals(msg, file.copyOfRange(2, file.size))
    }
}
