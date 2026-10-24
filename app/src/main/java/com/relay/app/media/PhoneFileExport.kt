package com.relay.app.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.relay.app.files.FileSafety
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

object PhoneFileExport {
    fun saveSaf(context: Context, token: String, uri: Uri) {
        FileExportPipe.copy(
            { PhoneFiles.engine(context).verifiedSource(token).inputStream() },
            { context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open destination") },
            { DocumentsContract.deleteDocument(context.contentResolver, uri) },
        )
    }
    fun saveDownloads(context: Context, token: String, name: String, mime: String) {
        check(Build.VERSION.SDK_INT >= 29) { "Use Save as on this Android version" }
        // Authenticate before creating a public pending record.
        val source=PhoneFiles.engine(context).verifiedSource(token)
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,FileSafety.safeName(name)); put(MediaStore.Downloads.MIME_TYPE,FileSafety.safeMime(mime))
            put(MediaStore.Downloads.RELATIVE_PATH,"Download/Relay"); put(MediaStore.Downloads.IS_PENDING,1)
        }
        val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: throw IOException("Cannot create download")
        FileExportPipe.copy({source.inputStream()},{resolver.openOutputStream(uri,"w") ?: throw IOException("Cannot open download")},
            {resolver.delete(uri,null,null)>0})
        try {
            check(resolver.update(uri,ContentValues().apply {put(MediaStore.Downloads.IS_PENDING,0)},null,null)==1)
        } catch(e: Exception) {
            val removed=try {resolver.delete(uri,null,null)>0} catch(cleanup: Exception) {e.addSuppressed(cleanup);false}
            throw IOException(if(removed) "Cannot publish download; output removed" else "Cannot publish download; incomplete output could not be removed",e)
        }
    }
    /** No auto-open; UI asks first. Executables are save-only, never offered to an installer/interpreter. */
    fun openIntent(context: Context, token: String, name: String, mime: String): Intent {
        val source=PhoneFiles.engine(context).verifiedSource(token)
        val head=ByteArray(8); source.inputStream().use {it.read(head)}
        require(!FileSafety.isExecutableLike(name,head)) { "Executable files can be saved, not opened by Relay" }
        val root=File(context.cacheDir,"file-open")
        if(!root.exists()) Files.createDirectory(root.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        require(!Files.isSymbolicLink(root.toPath()))
        // A separate, narrow provider root contains verified export copies only, never keys or ciphertext parts.
        val file=Files.createTempFile(root.toPath(),"verified-", "."+FileSafety.safeName(name).substringAfterLast('.',"bin"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).toFile()
        try { source.inputStream().use {input -> file.outputStream().use {input.copyTo(it,65536)} } }
        catch(e: Exception) {try {Files.delete(file.toPath())} catch(cleanup: Exception) {e.addSuppressed(cleanup)};throw e}
        val uri=FileProvider.getUriForFile(context,context.packageName+".fileprovider",file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,FileSafety.safeMime(mime))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData=android.content.ClipData.newRawUri("File",uri) }
    }
}
