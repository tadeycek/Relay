package com.relay.app.data.model

enum class MessageType {
    TEXT, LOCATION, IMAGE, VIDEO, LOCATION_REQUEST, LOCATION_DECLINED;

    companion object {
        /** Safe fallback for an unrecognized/legacy stored value, same defensive pattern as
         *  ContactTrustLevel.fromDb / PinExpiry.fromCode — a bad value degrades to TEXT instead
         *  of crashing the whole chat/group-list load. */
        fun fromDb(value: String?): MessageType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TEXT
    }
}
