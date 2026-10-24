package com.relay.app.media

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

/** SAF cannot promise atomic rename: failed external documents must be deleted, or cleanup failure reported. */
object FileExportPipe {
    fun copy(source: () -> InputStream, destination: () -> OutputStream, deleteFailed: () -> Boolean) {
        try {
            // Source supplier authenticates the complete private source before opening the external sink.
            source().use { input -> destination().use { output ->
                val buffer = ByteArray(65536)
                while(true) { val n=input.read(buffer); if(n<0) break; if(n>0) output.write(buffer,0,n) }
                output.flush()
            } }
        } catch (e: Exception) {
            val cleanup = try { deleteFailed() } catch (c: Exception) { e.addSuppressed(c); false }
            if(!cleanup) throw IOException("Save failed; incomplete output could not be removed", e)
            throw IOException("Save failed; incomplete output removed", e)
        }
    }
}
