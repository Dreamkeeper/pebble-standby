package org.cryomonitor.companion

import android.content.Intent
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.DataLogSession
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

/**
 * PebbleKit2 inbound path: the Core mobile app binds this service (declared
 * with the RECEIVE_DATA_FROM_WATCH intent filter) whenever our watchapp is
 * open on the watch, and delivers AppMessages here.
 *
 * The service instance is owned by the binder, not by us — messages are
 * funneled through [Pk2Bus] to whoever is consuming (MonitorService), with
 * a small buffer for messages that arrive before the service is up.
 */
class PebbleKit2ListenerService : BasePebbleListenerService() {

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID.toString() != Protocol.WATCHAPP_UUID) {
            CmLog.w(TAG, "message for foreign watchapp $watchappUUID — nack")
            return ReceiveResult.Nack
        }
        val map = data.entries.associate { (k, item) -> k.toInt() to unwrap(item) }
        CmLog.d(TAG, "pk2 rx $map")
        ensureMonitorRunning()
        Pk2Bus.deliverMessage(map)
        return ReceiveResult.Ack
    }

    /**
     * Worker DataLogging over PebbleKit2 (library >= 1.3.0, Pebble app with
     * data-log forwarding — upstream mobileapp#378). Unlike AppMessage this
     * arrives with the watchapp CLOSED: it is the worker's only voice. The
     * Pebble app keeps a batch until we Ack and may send it again, so Ack
     * means "processed" and replays are dropped by epoch (WorkerRecords).
     *
     * Ack policy: anything we can never use (foreign watchapp, unknown tag,
     * unparseable item size) is Acked so the Pebble app discards it instead
     * of retrying; only a valid new record that could not be handed to
     * MonitorService is Nacked.
     */
    override suspend fun onDataLogReceived(
        watchappUUID: UUID,
        session: DataLogSession,
        data: ByteArray,
        itemsLeft: Long,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID.toString() != Protocol.WATCHAPP_UUID) {
            CmLog.w(TAG, "data log for foreign watchapp $watchappUUID — discarding")
            return ReceiveResult.Ack
        }
        if (session.tag != Protocol.DL_TAG) {
            CmLog.w(TAG, "data log with unknown tag 0x%x — discarding".format(session.tag))
            return ReceiveResult.Ack
        }
        val items = WorkerRecords.split(data, session.itemSize)
        if (items.isEmpty() || !WorkerRecords.validItemSize(session.itemSize)) {
            CmLog.w(TAG, "data log batch unusable: ${data.size} B, item ${session.itemSize} B — discarding")
            return ReceiveResult.Ack
        }
        CmLog.d(TAG, "pk2 data log: ${items.size} records, $itemsLeft left, session ${session.timestamp}")
        var allDelivered = true
        for (item in items) {
            if (!WorkerRecords.process(this, item, WorkerRecords.TRANSPORT_PK2)) allDelivered = false
        }
        return if (allDelivered) ReceiveResult.Ack else ReceiveResult.Nack
    }

    override suspend fun onDataLogSessionFinished(
        watchappUUID: UUID,
        session: DataLogSession,
        watch: WatchIdentifier,
    ): ReceiveResult {
        CmLog.i(TAG, "pk2 data log session finished (tag 0x%x, opened ${session.timestamp})".format(session.tag))
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID.toString() != Protocol.WATCHAPP_UUID) return
        CmLog.i(TAG, "watchapp opened on watch ${watch}")
        ensureMonitorRunning()
        Pk2Bus.deliverAppOpened()
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID.toString() != Protocol.WATCHAPP_UUID) return
        CmLog.i(TAG, "watchapp closed on watch $watch")
        Pk2Bus.deliverAppClosed()
    }

    private fun unwrap(item: PebbleDictionaryItem): Any = when (item) {
        is PebbleDictionaryItem.Text -> item.value
        is PebbleDictionaryItem.Bytes -> item.value
        is PebbleDictionaryItem.Int8 -> item.value.toInt()
        is PebbleDictionaryItem.UInt8 -> item.value.toInt()
        is PebbleDictionaryItem.Int16 -> item.value.toInt()
        is PebbleDictionaryItem.UInt16 -> item.value.toInt()
        is PebbleDictionaryItem.Int32 -> item.value
        is PebbleDictionaryItem.UInt32 -> item.value.toInt()
    }

    /** We are bound by the (foreground) Core app, which permits FGS start. */
    private fun ensureMonitorRunning() {
        runCatching {
            startForegroundService(Intent(this, MonitorService::class.java))
        }.onFailure { CmLog.w(TAG, "could not start MonitorService: $it") }
    }

    companion object { private const val TAG = "PebbleKit2Listener" }
}
