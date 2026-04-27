package com.relay.app.data.model

enum class ContactTrustLevel(val dbValue: String, val label: String) {
    TRUSTED("trusted", "Trusted"),
    ASK("ask", "Ask"),
    BLOCKED("blocked", "Blocked");

    companion object {
        fun fromDb(value: String?): ContactTrustLevel =
            entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) } ?: ASK
    }
}
