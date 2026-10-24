package com.relay.app.transport.mailbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The migration decision is a pure function so the lost-response recovery rule is testable without an
 * Android Context, Keystore or network.
 */
class PhoneDeviceEnrollmentMigrationTest {

    @Test
    fun `2xx persists the migrated flag`() {
        assertEquals(MigrationAction.PERSIST_MIGRATED, migrationAction(200, null, AdminProbe.NOT_ATTEMPTED))
        assertEquals(MigrationAction.PERSIST_MIGRATED, migrationAction(201, null, AdminProbe.NOT_ATTEMPTED))
    }

    @Test
    fun `401 auth_invalid with a working admin key means the migration committed`() {
        // A previous attempt migrated the identity but its reply was lost; the shared key is now disabled.
        assertEquals(MigrationAction.PERSIST_MIGRATED, migrationAction(401, "auth_invalid", AdminProbe.ACCEPTED))
    }

    @Test
    fun `401 auth_invalid with a refused or missing probe keeps the shared key`() {
        assertEquals(MigrationAction.KEEP_SHARED_KEY, migrationAction(401, "auth_invalid", AdminProbe.REFUSED))
        assertEquals(MigrationAction.KEEP_SHARED_KEY, migrationAction(401, "auth_invalid", AdminProbe.NOT_ATTEMPTED))
    }

    @Test
    fun `other refusals never flip the flag`() {
        assertEquals(MigrationAction.KEEP_SHARED_KEY, migrationAction(401, "auth_expired", AdminProbe.NOT_ATTEMPTED))
        assertEquals(MigrationAction.KEEP_SHARED_KEY, migrationAction(400, "bad_request", AdminProbe.NOT_ATTEMPTED))
        assertEquals(MigrationAction.KEEP_SHARED_KEY, migrationAction(409, "admin_key_in_use", AdminProbe.NOT_ATTEMPTED))
        assertEquals(MigrationAction.KEEP_SHARED_KEY, migrationAction(503, "storage_error", AdminProbe.NOT_ATTEMPTED))
        assertEquals(MigrationAction.KEEP_SHARED_KEY, migrationAction(0, null, AdminProbe.NOT_ATTEMPTED))
    }
}
