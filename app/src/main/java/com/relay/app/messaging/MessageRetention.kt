package com.relay.app.messaging

import android.content.Context
import android.content.Intent
import com.relay.app.groups.SharedGroups

/** Local seven-day retention applies independently on every updated device; it never deletes contacts/rosters. */
object MessageRetention {
    fun sweep(context: Context, now: Long = System.currentTimeMillis()) = synchronized(SharedGroups) {
        synchronized(MessageDeletion) { MessageDeletion.expire(context, RetentionPolicy.cutoff(now)) }
    }
}
