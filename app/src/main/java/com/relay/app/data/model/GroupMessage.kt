package com.relay.app.data.model

data class GroupMessage(
    val id: Long = 0L,
    val groupId: Long,
    val contactId: Long? = null,
    val body: String,
    val type: MessageType = MessageType.TEXT,
    val lat: Double? = null,
    val lng: Double? = null,
    val isSent: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val mediaUri: String? = null,
    val pinLabel: String? = null,
    val expiryAt: Long? = null,
)
