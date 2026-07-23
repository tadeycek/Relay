package com.relay.app.data.db

object DatabaseContract {
    const val DB_NAME = "relay.db"
    const val DB_VERSION = 7

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

        const val CREATE = """
            CREATE TABLE $TABLE (
                $COL_ID        INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME      TEXT    NOT NULL,
                $COL_PHONE     TEXT    NOT NULL UNIQUE,
                $COL_HAS_RELAY INTEGER NOT NULL DEFAULT 0,
                $COL_TRUST_LEVEL TEXT  NOT NULL DEFAULT 'ask',
                $COL_PUBLIC_KEY TEXT,
                $COL_SENT_PUBKEY INTEGER NOT NULL DEFAULT 0,
                $COL_PENDING_PUBLIC_KEY TEXT
            )
        """

        const val ADD_HAS_RELAY = "ALTER TABLE $TABLE ADD COLUMN $COL_HAS_RELAY INTEGER NOT NULL DEFAULT 0"
        const val ADD_TRUST_LEVEL = "ALTER TABLE $TABLE ADD COLUMN $COL_TRUST_LEVEL TEXT NOT NULL DEFAULT 'ask'"
        const val ADD_PUBLIC_KEY = "ALTER TABLE $TABLE ADD COLUMN $COL_PUBLIC_KEY TEXT"
        const val ADD_SENT_PUBKEY = "ALTER TABLE $TABLE ADD COLUMN $COL_SENT_PUBKEY INTEGER NOT NULL DEFAULT 0"
        const val ADD_PENDING_PUBLIC_KEY = "ALTER TABLE $TABLE ADD COLUMN $COL_PENDING_PUBLIC_KEY TEXT"
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
                $COL_READ_AT     INTEGER
            )
        """

        const val INDEX_CONTACT = """
            CREATE INDEX idx_messages_contact ON $TABLE($COL_CONTACT_ID, $COL_TIMESTAMP DESC)
        """

        const val ADD_MEDIA_URI = "ALTER TABLE $TABLE ADD COLUMN $COL_MEDIA_URI TEXT"
        const val ADD_PIN_LABEL = "ALTER TABLE $TABLE ADD COLUMN $COL_PIN_LABEL TEXT"
        const val ADD_EXPIRY_AT = "ALTER TABLE $TABLE ADD COLUMN $COL_EXPIRY_AT INTEGER"
        const val ADD_MSG_ID = "ALTER TABLE $TABLE ADD COLUMN $COL_MSG_ID TEXT"
        const val ADD_READ_AT = "ALTER TABLE $TABLE ADD COLUMN $COL_READ_AT INTEGER"
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
                $COL_EXPIRY_AT   INTEGER
            )
        """

        const val INDEX_GROUP = """
            CREATE INDEX idx_group_messages_group ON $TABLE($COL_GROUP_ID, $COL_TIMESTAMP DESC)
        """
    }
}
