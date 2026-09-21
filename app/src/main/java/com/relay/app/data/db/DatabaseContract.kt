package com.relay.app.data.db

object DatabaseContract {
    const val DB_NAME = "relay.db"
    const val DB_VERSION = 13

    object Contacts {
        const val TABLE = "contacts"
        const val COL_ID = "_id"
        const val COL_NAME = "name"
        const val COL_PHONE = "phone"
        const val COL_HAS_RELAY = "has_relay"
        const val COL_TRUST_LEVEL = "trust_level"
        const val COL_PUBLIC_KEY = "public_key"
        const val COL_SENT_PUBKEY = "sent_pubkey"
        const val COL_PENDING_PUBLIC_KEY = "pending_public_key"
        const val COL_SIGNING_PUBLIC_KEY = "signing_public_key"
        const val COL_NOSTR_PUBKEY = "nostr_pubkey"
        const val COL_RELAY_HINTS = "relay_hints"
        const val COL_QR_VERIFIED = "qr_verified"

        const val CREATE = """
            CREATE TABLE $TABLE (
                $COL_ID        INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME      TEXT    NOT NULL,
                $COL_PHONE     TEXT    NOT NULL UNIQUE,
                $COL_HAS_RELAY INTEGER NOT NULL DEFAULT 0,
                $COL_TRUST_LEVEL TEXT  NOT NULL DEFAULT 'ask',
                $COL_PUBLIC_KEY TEXT,
                $COL_SENT_PUBKEY INTEGER NOT NULL DEFAULT 0,
                $COL_PENDING_PUBLIC_KEY TEXT,
                $COL_SIGNING_PUBLIC_KEY TEXT,
                $COL_NOSTR_PUBKEY TEXT,
                $COL_RELAY_HINTS TEXT,
                $COL_QR_VERIFIED INTEGER NOT NULL DEFAULT 0
            )
        """

        const val ADD_NOSTR_PUBKEY = "ALTER TABLE $TABLE ADD COLUMN $COL_NOSTR_PUBKEY TEXT"
        const val ADD_RELAY_HINTS = "ALTER TABLE $TABLE ADD COLUMN $COL_RELAY_HINTS TEXT"
        const val ADD_QR_VERIFIED = "ALTER TABLE $TABLE ADD COLUMN $COL_QR_VERIFIED INTEGER NOT NULL DEFAULT 0"

        /** One contact per Nostr key. Partial so the many legacy rows with NULL do not collide. */
        const val INDEX_NOSTR_PUBKEY = """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_contacts_nostr_pubkey
            ON $TABLE($COL_NOSTR_PUBKEY) WHERE $COL_NOSTR_PUBKEY IS NOT NULL
        """

        const val ADD_HAS_RELAY = "ALTER TABLE $TABLE ADD COLUMN $COL_HAS_RELAY INTEGER NOT NULL DEFAULT 0"
        const val ADD_TRUST_LEVEL = "ALTER TABLE $TABLE ADD COLUMN $COL_TRUST_LEVEL TEXT NOT NULL DEFAULT 'ask'"
        const val ADD_PUBLIC_KEY = "ALTER TABLE $TABLE ADD COLUMN $COL_PUBLIC_KEY TEXT"
        const val ADD_SENT_PUBKEY = "ALTER TABLE $TABLE ADD COLUMN $COL_SENT_PUBKEY INTEGER NOT NULL DEFAULT 0"
        const val ADD_PENDING_PUBLIC_KEY = "ALTER TABLE $TABLE ADD COLUMN $COL_PENDING_PUBLIC_KEY TEXT"
        const val ADD_SIGNING_PUBLIC_KEY = "ALTER TABLE $TABLE ADD COLUMN $COL_SIGNING_PUBLIC_KEY TEXT"
    }

    object Messages {
        const val TABLE = "messages"
        const val COL_ID = "_id"
        const val COL_CONTACT_ID = "contact_id"
        const val COL_BODY = "body"
        const val COL_TYPE = "type"
        const val COL_LAT = "lat"
        const val COL_LNG = "lng"
        const val COL_IS_SENT = "is_sent"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_MEDIA_URI = "media_uri"
        const val COL_PIN_LABEL = "pin_label"
        const val COL_EXPIRY_AT = "expiry_at"
        const val COL_MSG_ID = "msg_id"
        const val COL_READ_AT = "read_at"
        const val COL_SENDER_VERIFIED = "sender_verified"
        const val COL_DELIVERY_STATE = "delivery_state"
        const val COL_UNREAD = "unread"

        const val CREATE = """
            CREATE TABLE $TABLE (
                $COL_ID          INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONTACT_ID  INTEGER NOT NULL REFERENCES ${Contacts.TABLE}(${Contacts.COL_ID})
                                     ON DELETE CASCADE,
                $COL_BODY        TEXT    NOT NULL,
                $COL_TYPE        TEXT    NOT NULL DEFAULT 'TEXT',
                $COL_LAT         REAL,
                $COL_LNG         REAL,
                $COL_IS_SENT     INTEGER NOT NULL DEFAULT 1,
                $COL_TIMESTAMP   INTEGER NOT NULL,
                $COL_MEDIA_URI   TEXT,
                $COL_PIN_LABEL   TEXT,
                $COL_EXPIRY_AT   INTEGER,
                $COL_MSG_ID      TEXT,
                $COL_READ_AT     INTEGER,
                $COL_SENDER_VERIFIED INTEGER NOT NULL DEFAULT 1,
                $COL_DELIVERY_STATE INTEGER NOT NULL DEFAULT 0,
                $COL_UNREAD INTEGER NOT NULL DEFAULT 0
            )
        """

        /** Added in DB v13. A per-row flag (not a last-read timestamp): sender timestamps can be older than the read time when messages arrive late. */
        const val ADD_UNREAD = "ALTER TABLE $TABLE ADD COLUMN $COL_UNREAD INTEGER NOT NULL DEFAULT 0"

        const val INDEX_UNREAD = """
            CREATE INDEX IF NOT EXISTS idx_messages_unread ON $TABLE($COL_CONTACT_ID, $COL_UNREAD)
        """

        const val INDEX_CONTACT = """
            CREATE INDEX idx_messages_contact ON $TABLE($COL_CONTACT_ID, $COL_TIMESTAMP DESC)
        """

        /** Non-unique on purpose: legacy rows may already hold duplicate ids, and a unique index would fail the migration. */
        const val INDEX_MSG_ID = """
            CREATE INDEX IF NOT EXISTS idx_messages_msg_id ON $TABLE($COL_CONTACT_ID, $COL_MSG_ID)
        """

        const val ADD_DELIVERY_STATE = "ALTER TABLE $TABLE ADD COLUMN $COL_DELIVERY_STATE INTEGER NOT NULL DEFAULT 0"

        const val ADD_MEDIA_URI = "ALTER TABLE $TABLE ADD COLUMN $COL_MEDIA_URI TEXT"
        const val ADD_PIN_LABEL = "ALTER TABLE $TABLE ADD COLUMN $COL_PIN_LABEL TEXT"
        const val ADD_EXPIRY_AT = "ALTER TABLE $TABLE ADD COLUMN $COL_EXPIRY_AT INTEGER"
        const val ADD_MSG_ID = "ALTER TABLE $TABLE ADD COLUMN $COL_MSG_ID TEXT"
        const val ADD_READ_AT = "ALTER TABLE $TABLE ADD COLUMN $COL_READ_AT INTEGER"
        const val ADD_SENDER_VERIFIED = "ALTER TABLE $TABLE ADD COLUMN $COL_SENDER_VERIFIED INTEGER NOT NULL DEFAULT 1"
    }

    object Groups {
        const val TABLE = "groups"
        const val COL_ID = "_id"
        const val COL_NAME = "name"

        const val CREATE = """
            CREATE TABLE $TABLE (
                $COL_ID   INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT    NOT NULL
            )
        """
    }

    object GroupMembers {
        const val TABLE = "group_members"
        const val COL_GROUP_ID = "group_id"
        const val COL_CONTACT_ID = "contact_id"

        const val CREATE = """
            CREATE TABLE $TABLE (
                $COL_GROUP_ID   INTEGER NOT NULL REFERENCES ${Groups.TABLE}(${Groups.COL_ID}) ON DELETE CASCADE,
                $COL_CONTACT_ID INTEGER NOT NULL REFERENCES ${Contacts.TABLE}(${Contacts.COL_ID}) ON DELETE CASCADE,
                PRIMARY KEY ($COL_GROUP_ID, $COL_CONTACT_ID)
            )
        """
    }

    /** Outgoing messages waiting to be (re)published by the transport. See transport/outbox. */
    object Outbox {
        const val TABLE = "outbox"
        const val COL_ID = "_id"
        const val COL_CONTACT_ID = "contact_id"
        const val COL_RECIPIENT = "recipient_pubkey"
        const val COL_PAYLOAD_ID = "payload_id"
        const val COL_PAYLOAD_JSON = "payload_json"
        const val COL_CREATED_AT = "created_at"
        const val COL_ATTEMPTS = "attempts"
        const val COL_NEXT_ATTEMPT_AT = "next_attempt_at"
        const val COL_STATE = "state"

        const val CREATE = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_ID              INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONTACT_ID      INTEGER NOT NULL REFERENCES ${Contacts.TABLE}(${Contacts.COL_ID}) ON DELETE CASCADE,
                $COL_RECIPIENT       TEXT    NOT NULL,
                $COL_PAYLOAD_ID      TEXT    NOT NULL,
                $COL_PAYLOAD_JSON    TEXT    NOT NULL,
                $COL_CREATED_AT      INTEGER NOT NULL,
                $COL_ATTEMPTS        INTEGER NOT NULL DEFAULT 0,
                $COL_NEXT_ATTEMPT_AT INTEGER NOT NULL,
                $COL_STATE           INTEGER NOT NULL DEFAULT 0
            )
        """

        const val INDEX_DUE = """
            CREATE INDEX IF NOT EXISTS idx_outbox_due ON $TABLE($COL_STATE, $COL_NEXT_ATTEMPT_AT)
        """
    }

    /**
     * Ids of payloads already processed, including control messages (location requests, receipts,
     * key announcements) that leave no message row. The transport re-fetches a multi-day window on
     * every start (NIP-17 timestamp fuzzing), so without a persistent record a replayed
     * location request could re-trigger an auto-share.
     */
    object SeenPayloads {
        const val TABLE = "seen_payloads"
        const val COL_PAYLOAD_ID = "payload_id"
        const val COL_SEEN_AT = "seen_at"

        const val CREATE = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_PAYLOAD_ID TEXT PRIMARY KEY,
                $COL_SEEN_AT    INTEGER NOT NULL
            )
        """
    }

    object GroupMessages {
        const val TABLE = "group_messages"
        const val COL_ID = "_id"
        const val COL_GROUP_ID = "group_id"
        const val COL_CONTACT_ID = "contact_id"
        const val COL_BODY = "body"
        const val COL_TYPE = "type"
        const val COL_LAT = "lat"
        const val COL_LNG = "lng"
        const val COL_IS_SENT = "is_sent"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_MEDIA_URI = "media_uri"
        const val COL_PIN_LABEL = "pin_label"
        const val COL_EXPIRY_AT = "expiry_at"
        const val COL_UNREAD = "unread"

        const val CREATE = """
            CREATE TABLE $TABLE (
                $COL_ID          INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_GROUP_ID    INTEGER NOT NULL REFERENCES ${Groups.TABLE}(${Groups.COL_ID}) ON DELETE CASCADE,
                $COL_CONTACT_ID  INTEGER REFERENCES ${Contacts.TABLE}(${Contacts.COL_ID}) ON DELETE SET NULL,
                $COL_BODY        TEXT    NOT NULL,
                $COL_TYPE        TEXT    NOT NULL DEFAULT 'TEXT',
                $COL_LAT         REAL,
                $COL_LNG         REAL,
                $COL_IS_SENT     INTEGER NOT NULL DEFAULT 1,
                $COL_TIMESTAMP   INTEGER NOT NULL,
                $COL_MEDIA_URI   TEXT,
                $COL_PIN_LABEL   TEXT,
                $COL_EXPIRY_AT   INTEGER,
                $COL_UNREAD      INTEGER NOT NULL DEFAULT 0
            )
        """

        const val INDEX_GROUP = """
            CREATE INDEX idx_group_messages_group ON $TABLE($COL_GROUP_ID, $COL_TIMESTAMP DESC)
        """

        /** Added in DB v13, same meaning as Messages.COL_UNREAD. */
        const val ADD_UNREAD = "ALTER TABLE $TABLE ADD COLUMN $COL_UNREAD INTEGER NOT NULL DEFAULT 0"

        const val INDEX_UNREAD = """
            CREATE INDEX IF NOT EXISTS idx_group_messages_unread ON $TABLE($COL_GROUP_ID, $COL_UNREAD)
        """
    }
}
