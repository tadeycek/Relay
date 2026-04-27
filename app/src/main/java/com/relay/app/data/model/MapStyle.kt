package com.relay.app.data.model

enum class MapStyle(val label: String, val prefsKey: String) {
    STANDARD("Standard", "standard"),
    SATELLITE("Satellite", "satellite"),
    TERRAIN("Terrain", "terrain");

    companion object {
        fun fromKey(key: String?): MapStyle =
            entries.find { it.prefsKey == key } ?: STANDARD
    }
}
