package com.relay.app.data.model

data class Contact(
    val id: Long = 0L,
    val name: String,
    val phone: String,
    val hasRelay: Boolean = false,
    val trustLevel: ContactTrustLevel = ContactTrustLevel.ASK,
)
