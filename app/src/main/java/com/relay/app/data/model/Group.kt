package com.relay.app.data.model

data class Group(
    val id: Long = 0L,
    val name: String,
    val members: List<Contact> = emptyList(),
)
