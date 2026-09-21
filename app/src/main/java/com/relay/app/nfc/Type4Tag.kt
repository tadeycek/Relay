package com.relay.app.nfc

/**
 * The NFC Forum Type 4 tag protocol, in plain Kotlin so both halves can be unit-tested against each
 * other without a phone.
 *
 * One phone (the one showing its code) emulates a tag holding a single NDEF record with the contact code;
 * the other phone reads it like any NFC tag. Android has no built-in phone-to-phone push any more, and
 * this is the standard way to do it: host card emulation on one side, reader mode on the other.
 */
object Type4Tag {
    /** MIME type of the record that carries the contact code. */
    const val MIME_TYPE = "application/vnd.relay.contact"

    val AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
    const val CC_FILE_ID = 0xE103
    const val NDEF_FILE_ID = 0xE104

    /** Largest read we ask for at once; well inside what every phone accepts. */
    const val MAX_READ = 0xF0

    /** Refuse an NDEF message bigger than this: a contact code is a few hundred bytes. */
    const val MAX_NDEF_BYTES = 8 * 1024

    private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    private val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    private val SW_WRONG_PARAMS = byteArrayOf(0x6B, 0x00)
    private val SW_UNSUPPORTED = byteArrayOf(0x6D, 0x00)

    fun isOk(response: ByteArray?): Boolean =
        response != null && response.size >= 2 &&
            response[response.size - 2] == SW_OK[0] && response[response.size - 1] == SW_OK[1]

    private fun data(response: ByteArray) = response.copyOfRange(0, response.size - 2)

    // ---- NDEF ---------------------------------------------------------------------------------

    /** A one-record NDEF message: a MIME record of [mimeType] holding [payload]. */
    fun ndefMimeMessage(mimeType: String, payload: ByteArray): ByteArray {
        val type = mimeType.toByteArray(Charsets.US_ASCII)
        require(type.size in 1..255) { "MIME type length" }
        val short = payload.size < 256
        val header = if (short) 0xD2 else 0xC2 // MB | ME | (SR) | TNF=media-type
        val out = java.io.ByteArrayOutputStream()
        out.write(header)
        out.write(type.size)
        if (short) {
            out.write(payload.size)
        } else {
            out.write(payload.size ushr 24); out.write(payload.size ushr 16)
            out.write(payload.size ushr 8); out.write(payload.size)
        }
        out.write(type)
        out.write(payload)
        return out.toByteArray()
    }

    /** The payload of the first record if it is a MIME record of [mimeType]; null for anything else. */
    fun payloadOfMimeRecord(message: ByteArray, mimeType: String): ByteArray? {
        if (message.size < 3) return null
        val header = message[0].toInt() and 0xFF
        val tnf = header and 0x07
        val shortRecord = header and 0x10 != 0
        val hasId = header and 0x08 != 0
        if (tnf != 0x02) return null
        var i = 1
        val typeLen = message[i++].toInt() and 0xFF
        val payloadLen: Int
        if (shortRecord) {
            if (i >= message.size) return null
            payloadLen = message[i++].toInt() and 0xFF
        } else {
            if (i + 4 > message.size) return null
            payloadLen = ((message[i].toInt() and 0xFF) shl 24) or ((message[i + 1].toInt() and 0xFF) shl 16) or
                ((message[i + 2].toInt() and 0xFF) shl 8) or (message[i + 3].toInt() and 0xFF)
            i += 4
        }
        var idLen = 0
        if (hasId) {
            if (i >= message.size) return null
            idLen = message[i++].toInt() and 0xFF
        }
        if (payloadLen < 0 || i + typeLen + idLen + payloadLen > message.size) return null
        val type = String(message, i, typeLen, Charsets.US_ASCII)
        if (type != mimeType) return null
        val start = i + typeLen + idLen
        return message.copyOfRange(start, start + payloadLen)
    }

    /** The NDEF file: a two-byte length followed by the message. */
    fun ndefFile(message: ByteArray): ByteArray {
        val out = ByteArray(2 + message.size)
        out[0] = (message.size ushr 8).toByte()
        out[1] = message.size.toByte()
        message.copyInto(out, 2)
        return out
    }

    private fun capabilityContainer(maxNdefFileSize: Int): ByteArray = byteArrayOf(
        0x00, 0x0F, // CCLEN
        0x20, // mapping version 2.0
        0x00, MAX_READ.toByte(), // MLe: largest read response
        0x00, 0x3B, // MLc: largest command (we take no writes)
        0x04, 0x06, // NDEF File Control TLV
        (NDEF_FILE_ID ushr 8).toByte(), NDEF_FILE_ID.toByte(),
        (maxNdefFileSize ushr 8).toByte(), maxNdefFileSize.toByte(),
        0x00, // read access: open
        0xFF.toByte(), // write access: none
    )

    // ---- the tag side ---------------------------------------------------------------------------

    /**
     * Answers a reader's commands. [ndefFileProvider] returns the NDEF file to serve, or null when there is
     * nothing to share right now (then the tag does not answer to its application ID at all).
     */
    class Emulator(private val ndefFileProvider: () -> ByteArray?) {
        private var ndef: ByteArray? = null
        private var selected: Int? = null

        fun reset() {
            ndef = null
            selected = null
        }

        fun process(cmd: ByteArray): ByteArray {
            if (cmd.size < 4) return SW_UNSUPPORTED
            val ins = cmd[1].toInt() and 0xFF
            val p1 = cmd[2].toInt() and 0xFF
            val p2 = cmd[3].toInt() and 0xFF
            return when (ins) {
                0xA4 -> select(cmd, p1)
                0xB0 -> readBinary(cmd, p1, p2)
                else -> SW_UNSUPPORTED
            }
        }

        private fun select(cmd: ByteArray, p1: Int): ByteArray {
            if (cmd.size < 5) return SW_WRONG_PARAMS
            val lc = cmd[4].toInt() and 0xFF
            if (cmd.size < 5 + lc) return SW_WRONG_PARAMS
            val arg = cmd.copyOfRange(5, 5 + lc)
            return when (p1) {
                0x04 -> { // by name: our application
                    if (!arg.contentEquals(AID)) return SW_NOT_FOUND
                    val file = ndefFileProvider() ?: return SW_NOT_FOUND
                    ndef = file
                    selected = null
                    SW_OK
                }
                0x00 -> { // by file id
                    if (ndef == null || lc != 2) return SW_NOT_FOUND
                    val id = ((arg[0].toInt() and 0xFF) shl 8) or (arg[1].toInt() and 0xFF)
                    if (id != CC_FILE_ID && id != NDEF_FILE_ID) return SW_NOT_FOUND
                    selected = id
                    SW_OK
                }
                else -> SW_NOT_FOUND
            }
        }

        private fun readBinary(cmd: ByteArray, p1: Int, p2: Int): ByteArray {
            val file = when (selected) {
                CC_FILE_ID -> capabilityContainer(ndef?.size ?: 2)
                NDEF_FILE_ID -> ndef
                else -> null
            } ?: return SW_NOT_FOUND
            val offset = (p1 shl 8) or p2
            if (offset > file.size) return SW_WRONG_PARAMS
            val le = if (cmd.size > 4) (cmd[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
            val end = minOf(file.size, offset + minOf(le, MAX_READ))
            return file.copyOfRange(offset, end) + SW_OK
        }
    }

    // ---- the reader side ------------------------------------------------------------------------

    /**
     * Reads the NDEF message from a tag through [transceive] (one command in, one response out). Null if
     * the tag is not ours, does not answer as expected, or claims an unreasonable size.
     */
    fun readNdefMessage(transceive: (ByteArray) -> ByteArray): ByteArray? {
        val selectApp = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)
        if (!isOk(transceive(selectApp))) return null

        if (!isOk(transceive(selectFile(CC_FILE_ID)))) return null
        val ccResponse = transceive(readBinary(0, 15))
        if (!isOk(ccResponse)) return null
        val cc = data(ccResponse)
        if (cc.size < 15) return null
        val mle = ((cc[3].toInt() and 0xFF) shl 8) or (cc[4].toInt() and 0xFF)
        val fileId = ((cc[9].toInt() and 0xFF) shl 8) or (cc[10].toInt() and 0xFF)
        val chunk = mle.coerceIn(1, MAX_READ)

        if (!isOk(transceive(selectFile(fileId)))) return null
        val lenResponse = transceive(readBinary(0, 2))
        if (!isOk(lenResponse) || data(lenResponse).size != 2) return null
        val nlen = ((data(lenResponse)[0].toInt() and 0xFF) shl 8) or (data(lenResponse)[1].toInt() and 0xFF)
        if (nlen <= 0 || nlen > MAX_NDEF_BYTES) return null

        val out = java.io.ByteArrayOutputStream(nlen)
        var offset = 2
        while (out.size() < nlen) {
            val want = minOf(chunk, nlen - out.size())
            val r = transceive(readBinary(offset, want))
            if (!isOk(r)) return null
            val d = data(r)
            if (d.isEmpty()) return null
            out.write(d, 0, minOf(d.size, nlen - out.size()))
            offset += d.size
        }
        return out.toByteArray()
    }

    /** Reads the contact code text from a tag, or null if it is not a Relay contact tag. */
    fun readContactCode(transceive: (ByteArray) -> ByteArray): String? {
        val message = readNdefMessage(transceive) ?: return null
        val payload = payloadOfMimeRecord(message, MIME_TYPE) ?: return null
        return String(payload, Charsets.UTF_8)
    }

    private fun selectFile(id: Int) =
        byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, (id ushr 8).toByte(), id.toByte())

    private fun readBinary(offset: Int, length: Int) =
        byteArrayOf(0x00, 0xB0.toByte(), (offset ushr 8).toByte(), offset.toByte(), length.toByte())
}
