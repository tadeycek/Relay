package com.relay.app.mms

import java.io.ByteArrayOutputStream

object MmsPduBuilder {

    // Header field short integers (0x80 | field code per OMA MMS 1.3)
    private const val HDR_MESSAGE_TYPE   = 0x8C  // field 0x0C
    private const val HDR_TRANSACTION_ID = 0x98  // field 0x18
    private const val HDR_MMS_VERSION    = 0x8D  // field 0x0D
    private const val HDR_TO             = 0x97  // field 0x17
    private const val HDR_FROM           = 0x89  // field 0x09
    private const val HDR_CONTENT_TYPE  = 0x84  // field 0x04

    private const val MSG_TYPE_SEND_REQ  = 0x80
    private const val MMS_VERSION_12     = 0x92  // short integer for 1.2
    private const val INSERT_ADDR_TOKEN  = 0x81

    // Short integer for application/vnd.wap.multipart.related (0x33)
    private const val CT_MULTIPART_RELATED = 0xB3

    // Content-ID part-header field short integer (0x80 | 0x40)
    private const val PART_HDR_CONTENT_ID = 0xC0

    fun build(
        to: String,
        textBody: String?,
        mediaBytes: ByteArray,
        mimeType: String,
        transId: String,
    ): ByteArray {
        val smilBytes = buildSmil(mimeType, !textBody.isNullOrEmpty()).toByteArray(Charsets.UTF_8)
        val parts = buildList {
            add(Triple("application/smil", "<smil>", smilBytes))
            if (!textBody.isNullOrEmpty()) {
                add(Triple("text/plain", "text_body", textBody.toByteArray(Charsets.UTF_8)))
            }
            add(Triple(mimeType, "attachment", mediaBytes))
        }

        val out = ByteArrayOutputStream()

        out.write(HDR_MESSAGE_TYPE)
        out.write(MSG_TYPE_SEND_REQ)

        out.write(HDR_TRANSACTION_ID)
        writeNullTermText(out, transId)

        out.write(HDR_MMS_VERSION)
        out.write(MMS_VERSION_12)

        out.write(HDR_TO)
        writeNullTermText(out, to.toMmsAddress())

        // From: insert-address-token
        out.write(HDR_FROM)
        out.write(0x01)              // value-length = 1
        out.write(INSERT_ADDR_TOKEN)

        // Content-Type: multipart/related; type=application/smil; start=<smil>
        val ctVal = buildMultipartRelatedCT()
        out.write(HDR_CONTENT_TYPE)
        writeValueLength(out, ctVal.size)
        out.write(ctVal)

        // Multipart body
        writeUintVar(out, parts.size)
        for ((ct, cid, data) in parts) {
            writePart(out, ct, cid, data)
        }

        return out.toByteArray()
    }

    private fun buildSmil(mimeType: String, hasText: Boolean): String {
        val mediaTag = if (mimeType.startsWith("image")) "img" else "video"
        val textPart = if (hasText) "<text src=\"text_body\" region=\"r\"/>" else ""
        return "<smil><head><layout><root-layout/><region id=\"r\" fit=\"meet\"/>" +
                "</layout></head><body><par dur=\"5000ms\">" +
                "<$mediaTag src=\"attachment\" region=\"r\"/>$textPart" +
                "</par></body></smil>"
    }

    private fun buildMultipartRelatedCT(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CT_MULTIPART_RELATED)
        writeNullTermText(out, "type")
        writeNullTermText(out, "application/smil")
        writeNullTermText(out, "start")
        writeNullTermText(out, "<smil>")
        return out.toByteArray()
    }

    private fun writePart(out: ByteArrayOutputStream, contentType: String, contentId: String, data: ByteArray) {
        val hdrs = ByteArrayOutputStream()
        writeNullTermText(hdrs, contentType)
        hdrs.write(PART_HDR_CONTENT_ID)
        writeNullTermText(hdrs, "<$contentId>")

        val hdrBytes = hdrs.toByteArray()
        writeUintVar(out, hdrBytes.size)
        writeUintVar(out, data.size)
        out.write(hdrBytes)
        out.write(data)
    }

    private fun writeNullTermText(out: ByteArrayOutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.write(0x00)
    }

    private fun writeValueLength(out: ByteArrayOutputStream, length: Int) {
        if (length <= 30) {
            out.write(length)
        } else {
            out.write(31) // length-quote
            writeUintVar(out, length)
        }
    }

    private fun writeUintVar(out: ByteArrayOutputStream, value: Int) {
        val bytes = mutableListOf<Int>()
        var v = value
        bytes.add(v and 0x7F)
        v = v ushr 7
        while (v > 0) {
            bytes.add(0, (v and 0x7F) or 0x80)
            v = v ushr 7
        }
        bytes.forEach { out.write(it) }
    }

    private fun String.toMmsAddress(): String {
        val digits = filter { it.isDigit() || it == '+' }
        return "$digits/TYPE=PLMN"
    }
}
