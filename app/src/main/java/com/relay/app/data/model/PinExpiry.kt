package com.relay.app.data.model

enum class PinExpiry(val label: String, val smsCode: String, val durationMs: Long?) {
    ONE_HOUR("1 hour", "1hr", 3_600_000L),
    SIX_HOURS("6 hours", "6hr", 21_600_000L),
    TWENTY_FOUR_HOURS("24 hours", "24hr", 86_400_000L),
    FORTY_EIGHT_HOURS("48 hours", "48hr", 172_800_000L),
    NEVER("Never", "never", null);

    companion object {
        fun fromCode(code: String?): PinExpiry =
            entries.find { it.smsCode.equals(code, ignoreCase = true) } ?: NEVER

        fun fromPrefsKey(key: String?): PinExpiry =
            entries.find { it.smsCode == key } ?: NEVER
    }
}
