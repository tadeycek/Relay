package com.relay.app.messaging

/** Old peers may still send these controls. Drop them without recording or advertising activity. */
object RetiredActivity {
    const val PREFIX = "TYPE:PROFILE_PHOTO|KIND:ACTIVITY|"
    // Only for historical upgrades through schema 21; current databases never create this table.
    const val LEGACY_CREATE = "CREATE TABLE contact_activity (peer TEXT PRIMARY KEY,last_at INTEGER NOT NULL,active INTEGER NOT NULL)"
    const val DROP = "DROP TABLE IF EXISTS contact_activity"
    fun isControl(body: String): Boolean = body.startsWith(PREFIX)
}
