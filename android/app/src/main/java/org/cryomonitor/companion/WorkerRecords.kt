package org.cryomonitor.companion

import android.content.Context
import android.content.Intent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The worker's DataLogging heartbeat record (cm_heartbeat_rec) — parsing,
 * batch splitting, replay protection and hand-off to MonitorService. Shared
 * by both transports: PebbleKit2 data-log callbacks (the upstream path,
 * PebbleKit2 >= 1.3.0 + a Pebble app that forwards data logging) and the
 * classic com.getpebble.action.dl broadcasts (older patched Pebble app).
 *
 * Record layouts: v1 = 8 B (epoch u32 LE, stage, battery, bpm, suspended);
 * v2 = 14 B (+ change_age u16, motion_age u16, flags, heap64); v3 = 16 B
 * (+ episode u16; detector packed into the stage byte's high nibble).
 */
object WorkerRecords {
    const val TRANSPORT_PK2 = "PebbleKit2"
    const val TRANSPORT_CLASSIC = "classic"

    data class Rec(
        val epochS: Long, val stage: Int, val detector: Int, val episode: Int,
        val battery: Int, val bpm: Int, val suspended: Int,
        val changeAgeS: Int, val motionAgeS: Int, val flags: Int, val heapB: Int,
    )

    fun validItemSize(size: Int): Boolean = size == 8 || size == 14 || size == 16

    fun parse(bytes: ByteArray): Rec? {
        if (!validItemSize(bytes.size)) return null
        val v3 = bytes.size == 16
        val v2 = bytes.size >= 14
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return Rec(
            epochS = buf.getInt(0).toLong() and 0xFFFFFFFFL,
            stage = if (v3) bytes[4].toInt() and 0x0F else bytes[4].toInt() and 0xFF,
            detector = if (v3) (bytes[4].toInt() shr 4) and 0x0F else -1,
            episode = if (v3) buf.getShort(14).toInt() and 0xFFFF else 0,
            battery = bytes[5].toInt() and 0xFF,
            bpm = bytes[6].toInt() and 0xFF,
            suspended = bytes[7].toInt() and 0xFF,
            changeAgeS = if (v2) buf.getShort(8).toInt() and 0xFFFF else -1,
            motionAgeS = if (v2) buf.getShort(10).toInt() and 0xFFFF else -1,
            flags = if (v2) bytes[12].toInt() and 0xFF else -1,
            heapB = if (v2) (bytes[13].toInt() and 0xFF) * 64 else 0,
        )
    }

    /** A PebbleKit2 batch is N whole items in logging order. */
    fun split(data: ByteArray, itemSize: Int): List<ByteArray> {
        if (itemSize <= 0 || data.size % itemSize != 0) return emptyList()
        return (0 until data.size / itemSize).map {
            data.copyOfRange(it * itemSize, (it + 1) * itemSize)
        }
    }

    /**
     * Replay protection: PebbleKit2 may deliver a batch more than once
     * (retry after an unclear result), and the contract requires the
     * companion to tolerate it. Worker records carry a strictly increasing
     * wall-clock epoch, so "newer than the last one processed" is the
     * test. A large step backwards is a watch clock correction, not a
     * replay — accept it rather than go deaf until the clock catches up.
     */
    fun isNew(epochS: Long, lastEpochS: Long): Boolean =
        epochS > lastEpochS || epochS < lastEpochS - CLOCK_BACKSTEP_S

    const val CLOCK_BACKSTEP_S = 3600L

    fun describe(r: Rec, flushS: Long, transport: String): String {
        val diag = if (r.flags >= 0)
            " changeAge=${r.changeAgeS}s motionAge=${r.motionAgeS}s " +
                "flags=0x%02x heap=${r.heapB}B".format(r.flags) +
                (if ((r.flags and 0x40) != 0) " GATED" else "") +
                (if ((r.flags and 0x08) != 0) " NAGGED" else "") +
                (if ((r.flags and 0x04) != 0) " HUNT" else "")
        else ""
        return "WORKER HEARTBEAT via DataLogging: stage=${r.stage} " +
            "batt=${r.battery}% bpm=${r.bpm} susp=${r.suspended} " +
            "flush-latency=${flushS}s$diag [$transport]"
    }

    /**
     * Process one raw record from either transport: parse, drop replays,
     * log, hand to MonitorService (which both consumes it live and is
     * revived by it). Returns false only when the record was valid and new
     * but could NOT be handed over — the PebbleKit2 caller then Nacks so
     * the Pebble app retries.
     */
    fun process(context: Context, bytes: ByteArray, transport: String): Boolean {
        val r = parse(bytes) ?: run {
            CmLog.w(TAG, "DL record has unexpected size ${bytes.size}"); return true
        }
        val settings = SettingsStore(context)
        if (!isNew(r.epochS, settings.dlLastEpoch)) {
            CmLog.d(TAG, "DL replay dropped (epoch ${r.epochS} <= ${settings.dlLastEpoch})")
            return true
        }
        val flushS = System.currentTimeMillis() / 1000 - r.epochS
        CmLog.i(TAG, describe(r, flushS, transport))
        val ok = runCatching {
            context.startForegroundService(
                Intent(context, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_WORKER_HEARTBEAT)
                    .putExtra("battery", r.battery)
                    .putExtra("stage", r.stage)
                    .putExtra("suspended", r.suspended)
                    .putExtra("flags", r.flags)
                    .putExtra("worker_heap", r.heapB)
                    .putExtra("detector", r.detector)
                    .putExtra("episode", r.episode)
                    .putExtra("transport", transport)
                    .putExtra("flush_s", flushS))
        }.onFailure { CmLog.w(TAG, "could not deliver worker heartbeat: $it") }.isSuccess
        if (ok) settings.dlLastEpoch = r.epochS
        return ok
    }

    private const val TAG = "DataLog"
}
