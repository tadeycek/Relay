package com.relay.app.lab

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityOptionsCompat
import com.relay.app.media.ChatMediaPicker
import com.relay.app.media.rememberChatMediaPicker
import com.relay.app.ui.screens.chat.ConversationUpdates

/** Offline instrumentation harness: exercises real picker state without opening another app. */
class MediaPickerFixtureActivity : ComponentActivity() {
    lateinit var picker: ChatMediaPicker
    var privateVisible by mutableStateOf(true)
    var bindings = 0
    var refreshes = 0
    var unbindings = 0
    private var request = -1
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            request = requestCode
            privateVisible = false
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry get() = registry
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                val current = rememberChatMediaPicker(onReturn = { privateVisible = true })
                SideEffect { picker = current }
                if (privateVisible) {
                    ConversationUpdates(Unit, { bindings++ }, { unbindings++ }, { refreshes++ })
                    Text("${current.draftFor(7).size} attachments")
                }
            }
        }
    }

    fun returnSelection(uris: List<Uri>) {
        val result = Intent().apply {
            if (uris.isNotEmpty()) {
                data = uris.first()
                clipData = ClipData.newRawUri("Attachments", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
            }
        }
        registry.dispatchResult(request, Activity.RESULT_OK, result)
    }
}
