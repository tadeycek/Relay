package com.relay.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.mms.MediaCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Own photo (id 0) and received approved-contact photos, encrypted at rest and excluded from backup. */
object ContactPhotos {
    private val changes = MutableStateFlow(0L)
    val revisions = changes.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences("profile_photo_state", Context.MODE_PRIVATE)
    private fun file(context: Context, id: Long): File {
        require(id >= 0)
        return File(File(context.filesDir, "contact-photos"), "$id.enc")
    }

    @Synchronized fun load(context: Context, id: Long): Bitmap? = runCatching {
        if (id < 0) null else {
            val bytes = RelayFileCrypto.readDecrypted(context, file(context, id))
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    @Synchronized fun hasPhoto(context: Context, id: Long) = id >= 0 && file(context, id).isFile
    @Synchronized fun selfUpdate(context: Context): ProfilePhotoBody.Update {
        val revision = prefs(context).getLong("self_revision", 0)
        val data = if (hasPhoto(context, 0)) RelayFileCrypto.readDecrypted(context, file(context, 0)) else null
        return ProfilePhotoBody.Update(revision, data)
    }

    fun importSelfPhoto(context: Context, uri: Uri): Boolean {
        val result = MediaCompressor.compressImage(context, uri, ProfilePhotoBody.MAX_BYTES.toLong(), ProfilePhotoBody.MAX_DIM)
        if (result !is MediaCompressor.Result.Success) return false
        return try {
            synchronized(this) {
                val target = file(context, 0).also { it.parentFile!!.mkdirs() }
                RelayFileCrypto.writeEncrypted(context, target, result.file.readBytes())
                check(prefs(context).edit().putLong("self_revision", Math.addExact(selfUpdate(context).revision, 1)).commit())
                changes.value += 1
            }
            ProfilePhotoSync.syncPending(context)
            true
        } finally { result.file.delete() }
    }

    @Synchronized fun removeSelf(context: Context) {
        val revision = Math.addExact(selfUpdate(context).revision, 1)
        check(!file(context, 0).exists() || file(context, 0).delete())
        check(prefs(context).edit().putLong("self_revision", revision).commit())
        changes.value += 1
        ProfilePhotoSync.syncPending(context)
    }

    @Synchronized fun receive(context: Context, id: Long, update: ProfilePhotoBody.Update): Boolean {
        require(id > 0)
        if (update.revision <= prefs(context).getLong("received_$id", -1)) return false
        val bytes = update.jpeg
        if (bytes != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..ProfilePhotoBody.MAX_DIM || bounds.outHeight !in 1..ProfilePhotoBody.MAX_DIM) return false
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            decoded.recycle()
            val target = file(context, id).also { it.parentFile!!.mkdirs() }
            RelayFileCrypto.writeEncrypted(context, target, bytes)
        } else {
            check(!file(context, id).exists() || file(context, id).delete())
        }
        check(prefs(context).edit().putLong("received_$id", update.revision).commit())
        changes.value += 1
        return true
    }

    @Synchronized fun remove(context: Context, id: Long) {
        if (id > 0) {
            file(context, id).delete()
            prefs(context).edit().remove("received_$id").remove("queued_$id").apply()
            changes.value += 1
        }
    }
}

class ProfilePhotoPicker(val choose: () -> Unit, val remove: () -> Unit)
val LocalProfilePhotoPicker = staticCompositionLocalOf<ProfilePhotoPicker> { error("No profile photo picker") }

/** Activity-root registration survives backgrounding, navigation and Calculator session expiry. */
@Composable
fun rememberProfilePhotoPicker(): ProfilePhotoPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { ContactPhotos.importSelfPhoto(context, uri) }.getOrDefault(false) }
            Toast.makeText(context, if (saved) "Profile photo saved; sharing queued" else "Couldn't use this profile photo", Toast.LENGTH_SHORT).show()
        }
    }
    return ProfilePhotoPicker(
        choose = { launcher.launch("image/*") },
        remove = { scope.launch { withContext(Dispatchers.IO) { ContactPhotos.removeSelf(context) } } },
    )
}
