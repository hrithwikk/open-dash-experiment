package com.example.opendash.dash.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DashMediaCommandsTest {
    @Test
    fun nowPlayingUsesCapturedNullSeparatedLayout() {
        val packet = DashCommands.nowPlaying("Title", "Album", "Artist")

        assertEquals(packet.size, u16(packet, 0))
        assertEquals(2, u16(packet, 2))
        assertEquals(0x05, packet[17].toInt() and 0xFF)
        assertEquals(0x0D, packet[18].toInt() and 0xFF)
        val length = u16(packet, 19)
        assertArrayEquals(
            "Title\u0000Album\u0000Artist".toByteArray(Charsets.UTF_8),
            packet.copyOfRange(21, 21 + length),
        )
    }

    @Test
    fun callerCardIsTerminatedAndLimitedToDisplayWidth() {
        val packet = DashCommands.callNotify("12345678901234567890extra")
        val length = u16(packet, 19)

        assertEquals(0x05, packet[17].toInt() and 0xFF)
        assertEquals(0x22, packet[18].toInt() and 0xFF)
        assertEquals(21, length)
        assertEquals(0, packet[20 + length].toInt())
    }

    @Test
    fun callClearCarriesSingleNullByte() {
        val packet = DashCommands.callClear()

        assertEquals(1, u16(packet, 19))
        assertEquals(0, packet[21].toInt())
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
}
