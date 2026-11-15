package com.relay.app.crypto

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Expose the encrypted payload after a local file's wrapped-key header, without copying it. */
internal class OffsetByteChannel(private val source: SeekableByteChannel, private val offset: Long) : SeekableByteChannel {
    init { require(offset >= 0 && offset <= source.size()); source.position(offset) }
    override fun read(dst: ByteBuffer): Int = source.read(dst)
    override fun position(): Long = source.position() - offset
    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0 && newPosition <= Long.MAX_VALUE - offset)
        source.position(newPosition + offset)
        return this
    }
    override fun size(): Long = source.size() - offset
    override fun isOpen(): Boolean = source.isOpen
    override fun close() = source.close()
    override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException("Read-only media")
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("Read-only media")
}
