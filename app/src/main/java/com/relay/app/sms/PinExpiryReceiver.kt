package com.relay.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.repository.GroupMessageRepository
import com.relay.app.data.repository.MessageRepository

class PinExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val db = RelayDbHelper(context)
        MessageRepository(db).deleteExpiredPinsSync()
        GroupMessageRepository(db).deleteExpiredPinsSync()
        RelayFileCrypto.purgeStaleViewCache(context)

        val update = Intent("com.relay.app.PINS_UPDATED").apply { setPackage(context.packageName) }
        context.sendBroadcast(update)
    }
}
