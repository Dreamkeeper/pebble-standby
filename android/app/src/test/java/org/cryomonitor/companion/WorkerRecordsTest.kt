package org.cryomonitor.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WorkerRecordsTest {

    private fun v3(epoch: Long, stage: Int, det: Int, batt: Int, bpm: Int, susp: Int,
                   chg: Int, mot: Int, flags: Int, heap64: Int, episode: Int): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(epoch.toInt()); put(((det shl 4) or stage).toByte()); put(batt.toByte())
            put(bpm.toByte()); put(susp.toByte()); putShort(chg.toShort()); putShort(mot.toShort())
            put(flags.toByte()); put(heap64.toByte()); putShort(episode.toShort())
        }.array()

    @Test
    fun `v3 record decodes every field`() {
        val r = WorkerRecords.parse(v3(1_789_000_000L, 3, 1, 57, 182, 0, 274, 60, 0x54, 26, 4242))!!
        assertEquals(1_789_000_000L, r.epochS)
        assertEquals(3, r.stage); assertEquals(1, r.detector); assertEquals(4242, r.episode)
        assertEquals(57, r.battery); assertEquals(182, r.bpm); assertEquals(0, r.suspended)
        assertEquals(274, r.changeAgeS); assertEquals(60, r.motionAgeS)
        assertEquals(0x54, r.flags); assertEquals(1664, r.heapB)
    }

    @Test
    fun `epoch is unsigned and older layouts still parse`() {
        val late = WorkerRecords.parse(v3(0xF0000000L, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0))!!
        assertEquals(0xF0000000L, late.epochS)
        val v1 = WorkerRecords.parse(byteArrayOf(1, 0, 0, 0, 2, 90, 66, 1))!!
        assertEquals(2, v1.stage); assertEquals(-1, v1.detector); assertEquals(-1, v1.flags)
        assertEquals(0, v1.heapB); assertEquals(0, v1.episode)
        assertNull(WorkerRecords.parse(ByteArray(10)))
    }

    @Test
    fun `a PebbleKit2 batch splits into whole items in order`() {
        val a = v3(100, 0, 0, 50, 60, 0, 1, 1, 0x10, 26, 0)
        val b = v3(160, 0, 0, 50, 61, 0, 1, 1, 0x10, 26, 0)
        val items = WorkerRecords.split(a + b, 16)
        assertEquals(2, items.size)
        assertEquals(100L, WorkerRecords.parse(items[0])!!.epochS)
        assertEquals(160L, WorkerRecords.parse(items[1])!!.epochS)
        assertTrue(WorkerRecords.split(ByteArray(17), 16).isEmpty())   // partial item
        assertTrue(WorkerRecords.split(ByteArray(16), 0).isEmpty())
    }

    @Test
    fun `a redelivered batch is dropped and a clock correction is not`() {
        assertTrue(WorkerRecords.isNew(epochS = 1000, lastEpochS = 0))
        assertTrue(WorkerRecords.isNew(epochS = 1060, lastEpochS = 1000))
        assertFalse(WorkerRecords.isNew(epochS = 1000, lastEpochS = 1000))   // replay
        assertFalse(WorkerRecords.isNew(epochS = 940, lastEpochS = 1000))    // older replay
        // watch clock set back by more than an hour: a new timeline, not a replay
        assertTrue(WorkerRecords.isNew(epochS = 1000, lastEpochS = 1000 + 3601))
        assertFalse(WorkerRecords.isNew(epochS = 1000, lastEpochS = 1000 + 3600))
    }

    @Test
    fun `log line names the transport and the diagnostic flags`() {
        val r = WorkerRecords.parse(v3(100, 0, 0, 57, 0, 0, 214, 53, 0x54, 27, 0))!!
        val line = WorkerRecords.describe(r, 92, WorkerRecords.TRANSPORT_PK2)
        assertTrue(line.contains("flush-latency=92s"))
        assertTrue(line.contains("GATED")); assertTrue(line.contains("HUNT"))
        assertTrue(line.endsWith("[PebbleKit2]"))
    }
}
