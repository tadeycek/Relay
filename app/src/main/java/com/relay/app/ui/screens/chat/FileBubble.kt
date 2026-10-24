package com.relay.app.ui.screens.chat

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.relay.app.data.model.Message
import com.relay.app.files.*
import com.relay.app.media.*
import com.relay.app.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FileBubble(message: Message) {
    val context=LocalContext.current
    val generation by PhoneFiles.changes.collectAsState()
    val scope=rememberCoroutineScope()
    val parsed=remember(message.body) { (FileBody.parse(message.body,FileBody.urlBasePredicate(com.relay.app.transport.mailbox.MailboxMedia::isOurs)) as? FileBodyResult.Ok)?.body }
    if(parsed==null) { Text("[Unsupported file]");return }
    val job by produceState<PhoneFileJob?>(null,message.msgId,generation) {
        value=withContext(Dispatchers.IO) { message.msgId?.let {PhoneFiles.store(context).forMessage(message.contactId,it)} }
    }
    var confirmOpen by remember {mutableStateOf(false)}
    fun action(block: () -> Unit) { scope.launch {
        val result=withContext(Dispatchers.IO) {try {block(); "Done"} catch(e: Exception) {e.message ?: "File action failed"}}
        Toast.makeText(context,result,Toast.LENGTH_LONG).show()
    } }
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(parsed.mime)) { uri ->
        if(uri!=null) job?.let {j -> action {PhoneFileExport.saveSaf(context,j.token,uri)} }
    }
    Column(Modifier.padding(12.dp)) {
        Text(parsed.name)
        Text("${parsed.size} bytes")
        val j=job
        when {
            j==null -> Text("File unavailable")
            j.cancelled -> Text("Cancelled")
            j.error!=null -> {Text(j.error);TextButton(onClick={action {PhoneFiles.retry(context,j.token)}}) {Text("Retry")} }
            !j.ready -> {
                val complete=Integer.bitCount(j.transferred)
                Text("${if(j.recipients.isEmpty()) "Receiving" else "Sending"} · $complete / ${parsed.parts.size} parts")
                LinearProgressIndicator(progress=complete.toFloat()/parsed.parts.size)
                TextButton(onClick={action {PhoneFiles.cancel(context,j.token)}}) {Text("Cancel")}
            }
            else -> {
                Text("Verified")
                Row {
                    TextButton(onClick={saver.launch(parsed.name)}) {Text("Save as")}
                    if(Build.VERSION.SDK_INT>=29) TextButton(onClick={action {PhoneFileExport.saveDownloads(context,j.token,parsed.name,parsed.mime)}}) {Text("Downloads")}
                }
                TextButton(onClick={confirmOpen=true}) {Text("Open…")}
            }
        }
        if(parsed.caption.isNotEmpty()) Text(parsed.caption)
    }
    if(confirmOpen) ConfirmDialog(title="Open this file in another app?",message="Only open files you trust. The selected app receives a read-only copy. Executable files are save-only.",confirmLabel="Choose app",
        onConfirm={confirmOpen=false; job?.let {j -> scope.launch {
            try {
                val intent=withContext(Dispatchers.IO) {PhoneFileExport.openIntent(context,j.token,parsed.name,parsed.mime)}
                context.startActivity(android.content.Intent.createChooser(intent,"Open file"))
            } catch(e: Exception) {Toast.makeText(context,e.message ?: "Cannot open file",Toast.LENGTH_LONG).show()}
        } } },onDismiss={confirmOpen=false})
}
