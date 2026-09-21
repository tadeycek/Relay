package com.relay.app.sms

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.DeliveryState
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.util.RelayPreferences
import com.relay.app.util.SmsMessageParser

class LocationShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: run { stopSelf(startId); return START_NOT_STICKY }
        val phone = intent.getStringExtra(EXTRA_PHONE) ?: run { stopSelf(startId); return START_NOT_STICKY }
        val contactId = intent.getLongExtra(EXTRA_CONTACT_ID, -1L)
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: ""
        val doNotify = intent.getBooleanExtra(EXTRA_NOTIFY, true)

        when (action) {
            ACTION_SHARE -> requestLocationAndShare(phone, contactId, contactName, doNotify, startId)
            ACTION_DECLINE -> {
                sendDecline(phone, contactId)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationAndShare(
        phone: String,
        contactId: Long,
        contactName: String,
        doNotify: Boolean,
        startId: Int,
    ) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val lastKnown = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                runCatching {
                    if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
                }.getOrNull()
            }
            .maxByOrNull { it.time }

        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 300_000L) {
            sendLocationSms(phone, contactId, contactName, doNotify, lastKnown)
            stopSelf(startId)
            return
        }

        val handler = Handler(Looper.getMainLooper())

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lm.removeUpdates(this)
                sendLocationSms(phone, contactId, contactName, doNotify, location)
                stopSelf(startId)
            }
        }

        val timeoutRunnable = Runnable {
            lm.removeUpdates(listener)
            if (lastKnown != null) {
                sendLocationSms(phone, contactId, contactName, doNotify, lastKnown)
            }
            stopSelf(startId)
        }

        try {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            handler.postDelayed(timeoutRunnable, 8_000L)
        } catch (e: SecurityException) {
            handler.removeCallbacks(timeoutRunnable)
            if (lastKnown != null) sendLocationSms(phone, contactId, contactName, doNotify, lastKnown)
            stopSelf(startId)
        }
    }

    private fun sendLocationSms(
        phone: String,
        contactId: Long,
        contactName: String,
        doNotify: Boolean,
        location: Location,
    ) {
        val body = SmsMessageParser.formatLocation(location.latitude, location.longitude)
        val db = RelayDbHelper(this)
        val contactRepo = ContactRepository(db)
        val contact = contactRepo.getByIdSync(contactId) ?: Contact(id = contactId, name = contactName, phone = phone)
        val handle = RelaySecureSend.sendWithId(this, contactRepo, contact, body)

        val repo = MessageRepository(db)
        repo.insertMessageSync(
            Message(
                contactId = contactId,
                body = body,
                type = MessageType.LOCATION,
                lat = location.latitude,
                lng = location.longitude,
                isSent = true,
                msgId = handle.msgId,
                deliveryState = if (contact.canUseInternetTransport) DeliveryState.QUEUED else DeliveryState.NONE,
            )
        )

        sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").apply {
            putExtra("contact_id", contactId)
            setPackage(packageName)
        })

        if (doNotify) showSharedNotification(contactName)
    }

    private fun sendDecline(phone: String, contactId: Long) {
        val db = RelayDbHelper(this)
        val contactRepo = ContactRepository(db)
        val contact = contactRepo.getByIdSync(contactId) ?: Contact(id = contactId, name = phone, phone = phone)
        val handle = RelaySecureSend.sendWithId(this, contactRepo, contact, SmsMessageParser.LOCATION_DECLINED_MSG)

        val repo = MessageRepository(db)
        repo.insertMessageSync(
            Message(
                contactId = contactId,
                body = SmsMessageParser.LOCATION_DECLINED_MSG,
                type = MessageType.LOCATION_DECLINED,
                isSent = true,
                msgId = handle.msgId,
                deliveryState = if (contact.canUseInternetTransport) DeliveryState.QUEUED else DeliveryState.NONE,
            )
        )

        sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").apply {
            putExtra("contact_id", contactId)
            setPackage(packageName)
        })
    }

    private fun showSharedNotification(contactName: String) {
        ensureChannel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, RelayPreferences.LOCATION_REQUEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location shared")
            .setContentText("Your location was shared with $contactName")
            .setAutoCancel(true)
            .build()
        nm.notify(SHARED_NOTIF_ID, notif)
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(RelayPreferences.LOCATION_REQUEST_CHANNEL) != null) return
        val channel = NotificationChannel(
            RelayPreferences.LOCATION_REQUEST_CHANNEL,
            "Location Requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications for location sharing requests"
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_SHARE = "com.relay.app.SHARE_LOCATION"
        const val ACTION_DECLINE = "com.relay.app.DECLINE_LOCATION"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_NOTIFY = "notify"
        private const val SHARED_NOTIF_ID = 2001
    }
}
