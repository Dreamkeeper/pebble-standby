package org.cryomonitor.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import java.io.Serializable
import java.util.UUID

/**
 * PebbleKit-Classic DataLogging receiver — the worker's only voice while
 * the watchapp is closed (workers cannot use AppMessage; M0 spike S5).
 *
 * The worker logs an 8-byte cm_heartbeat_rec every 60 s into session tag
 * 0xC201; the phone's Pebble app ferries records over BT in batches and
 * broadcasts them per the legacy protocol reimplemented here (PebbleKit2
 * 1.2.0 has no DataLogging API). Every record is ACKed back — unACKed
 * data is re-broadcast forever and clogs the pipe.
 *
 * Whether the Core app emits these broadcasts at all IS the S5
 * experiment: every step logs loudly so one field run answers it.
 */
class DataLogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // App filter: broadcasts carry the watchapp UUID in extra "uuid".
        val appUuid = runCatching {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("uuid") as? UUID
        }.getOrNull()
        if (appUuid != null && appUuid.toString() != Protocol.WATCHAPP_UUID) return

        when (intent.action) {
            ACTION_RECEIVE_DATA -> {
                val dataId = intent.getIntExtra("pbl_data_id", -1)
                val type = intent.getByteExtra("pbl_data_type", (-1).toByte())
                val b64 = intent.getStringExtra("pbl_data_object")
                CmLog.d(TAG, "DL rx id=$dataId type=$type uuid=$appUuid " +
                    "payload=${b64?.length ?: -1}ch")
                if (dataId >= 0) ack(context, intent, dataId)
                if (dataId < 0 || dataId == lastDataId) return
                lastDataId = dataId
                if (type != TYPE_BYTES || b64 == null) return
                val bytes = runCatching {
                    Base64.decode(b64, Base64.NO_WRAP)
                }.getOrNull() ?: return
                parseHeartbeat(context, bytes)
            }
            ACTION_FINISH_SESSION -> CmLog.d(TAG, "DL session finished")
        }
    }

    private fun ack(context: Context, intent: Intent, dataId: Int) {
        runCatching {
            @Suppress("DEPRECATION")
            val session = intent.getSerializableExtra("data_log_uuid")
            context.sendBroadcast(Intent(ACTION_ACK_DATA).apply {
                (session as? Serializable)?.let { putExtra("data_log_uuid", it) }
                putExtra("pbl_data_id", dataId)
            })
        }.onFailure { CmLog.w(TAG, "DL ack failed: $it") }
    }

    /** Parsing, replay protection and hand-off live in [WorkerRecords],
     *  shared with the PebbleKit2 data-log path. */
    private fun parseHeartbeat(context: Context, bytes: ByteArray) {
        WorkerRecords.process(context, bytes, WorkerRecords.TRANSPORT_CLASSIC)
    }

    companion object {
        private const val TAG = "DataLog"
        const val ACTION_RECEIVE_DATA = "com.getpebble.action.dl.RECEIVE_DATA"
        const val ACTION_FINISH_SESSION = "com.getpebble.action.dl.FINISH_SESSION"
        const val ACTION_ACK_DATA = "com.getpebble.action.dl.ACK_DATA"
        const val ACTION_REQUEST_DATA = "com.getpebble.action.dl.REQUEST_DATA"
        private const val TYPE_BYTES: Byte = 0x00
        @Volatile private var lastDataId = -1

        /** Ask the phone's Pebble app to flush buffered worker records. */
        fun requestFlush(context: Context) {
            context.sendBroadcast(Intent(ACTION_REQUEST_DATA).apply {
                putExtra("uuid", UUID.fromString(Protocol.WATCHAPP_UUID))
            })
        }
    }
}
