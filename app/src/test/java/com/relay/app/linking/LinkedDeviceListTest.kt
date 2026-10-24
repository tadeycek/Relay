package com.relay.app.linking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedDeviceListTest {

    @Test
    fun parsesLinkedDevicesAndDropsThePhonesOwnPrimaryRow() {
        val json = """
            {"v":1,"head":42,"devices":[
              {"device":"primary","label":"","added_at":1,"last_seen":2,"active":true},
              {"device":"0123456789abcdef","label":"Studio laptop","added_at":1700000000000,"last_seen":1700000001000,"active":true},
              {"device":"fedcba9876543210","label":"","added_at":1700000002000,"last_seen":1700000003000,"active":false}
            ]}
        """.trimIndent()

        val devices = LinkedDeviceList.parse(json).getOrThrow()
        assertEquals(2, devices.size)
        assertEquals("Studio laptop", devices[0].displayName)
        assertTrue(devices[0].active)
        // An unlabelled computer still gets a distinguishable name.
        assertEquals("Computer fedc", devices[1].displayName)
        assertEquals(false, devices[1].active)
    }

    @Test
    fun anIdentityThatNeverLinkedHasNoDevices() {
        assertEquals(emptyList<LinkedDevice>(), LinkedDeviceList.parse("""{"v":1,"devices":[],"head":0}""").getOrThrow())
    }

    @Test
    fun hostileRepliesFailInsteadOfLookingEmpty() {
        for (bad in listOf(null, "", "not json", "[]", """{"v":1}""")) {
            assertTrue("reply=$bad", LinkedDeviceList.parse(bad).isFailure)
        }
    }

    @Test
    fun rowsAreBoundedAndBadRowsSkipped() {
        val rows = (1..100).joinToString(",") {
            """{"device":"${"%032x".format(it)}","label":"c$it","added_at":1,"last_seen":1,"active":true}"""
        }
        val devices = LinkedDeviceList.parse("""{"v":1,"devices":[$rows]}""").getOrThrow()
        assertEquals(LinkedDeviceList.MAX_DEVICES, devices.size)
    }
}
