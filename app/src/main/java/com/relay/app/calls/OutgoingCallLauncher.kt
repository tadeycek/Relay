package com.relay.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns a "place a call" action that first requests `RECORD_AUDIO` through a real permission launcher
 * and places the call only after the grant. The decision lives in [OutgoingCallStarter] (unit-tested);
 * this only binds it to the Android launcher. [CallController.placeCall] still re-checks as a backstop.
 */
@Composable
fun rememberOutgoingCall(): (pubkey: String, name: String?) -> Unit {
    val context = LocalContext.current
    lateinit var starter: OutgoingCallStarter
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        starter.onResult(granted)
    }
    starter = remember(context, launcher) {
        OutgoingCallStarter(
            hasMic = { hasMic(context) },
            requestMic = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            place = { pubkey, name -> CallController.placeCall(context, pubkey, name) },
            denied = {
                Toast.makeText(context, CallGuard.startMessage(CallStartBlock.MIC_DENIED), Toast.LENGTH_LONG).show()
            },
        )
    }
    return starter::start
}

private fun hasMic(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
