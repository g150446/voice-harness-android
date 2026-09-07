package com.g150446.voiceharness

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleRoleNegotiationTest {

    @Test
    fun `peer connected claims when Android is preferred`() {
        assertEquals(
            BleRoleCommand.CLAIM,
            peerConnectedRoleCommand(ConnectionPriority.ANDROID),
        )
        assertEquals(BLE_RX_CLAIM_PRIMARY, roleCommandByte(BleRoleCommand.CLAIM))
        assertArrayEquals(BLE_ROLE_CLAIM_DELAYS_MS, roleCommandDelaysMs(BleRoleCommand.CLAIM))
    }

    @Test
    fun `peer connected yields when Mac Handy is preferred`() {
        assertEquals(
            BleRoleCommand.YIELD,
            peerConnectedRoleCommand(ConnectionPriority.MAC_HANDY),
        )
        assertEquals(BLE_RX_YIELD_PRIMARY, roleCommandByte(BleRoleCommand.YIELD))
        assertArrayEquals(BLE_ROLE_YIELD_DELAYS_MS, roleCommandDelaysMs(BleRoleCommand.YIELD))
    }

    @Test
    fun `claim retries outlast Handy connect claim`() {
        assertArrayEquals(longArrayOf(0L, 1_000L, 1_600L), BLE_ROLE_CLAIM_DELAYS_MS)
        assertTrue(BLE_ROLE_CLAIM_DELAYS_MS.last() > 800L)
        assertArrayEquals(longArrayOf(0L, 300L, 600L), BLE_ROLE_YIELD_DELAYS_MS)
    }

    @Test
    fun `host-authorized start prepends claim only for Android priority`() {
        assertEquals(
            listOf(BLE_RX_CLAIM_PRIMARY, BLE_RX_STOP_RECORDING),
            hostAuthorizedStartPreamble(ConnectionPriority.ANDROID),
        )
        assertEquals(
            listOf(BLE_RX_STOP_RECORDING),
            hostAuthorizedStartPreamble(ConnectionPriority.MAC_HANDY),
        )
    }
}
