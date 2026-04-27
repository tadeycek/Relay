package com.relay.app.data.model

data class Message(
    val id: Long = 0L,
    val contactId: Long,
    val body: String,
    val type: MessageType = MessageType.TEXT,
    val lat: Double? = null,
    val lng: Double? = null,
    val isSent: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val mediaUri: String? = null,
    val pinLabel: String? = null,
    val expiryAt: Long? = null,
    val msgId: String? = null,
    val readAt: Long? = null,
)
