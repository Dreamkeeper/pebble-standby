package org.cryomonitor.companion

import android.content.Context
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.model.ConnectedWatch
import io.rebble.pebblekit2.model.Watchapp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Unified watch link: PebbleKit2 (primary, Core app >= 1.0.7.7) with
 * automatic fallback to the PebbleKit Classic broadcast transport for
 * older phone apps.
 *
 * Outbound: try PK2; on results that indicate "the phone app doesn't do
 * PK2" (no permission / unknown / exception) fall back to Classic
 * broadcasts. Watch-state results (not connected, NACK, timeout) are
 * authoritative and NOT retried over Classic — the watch is simply
 * unreachable.
 *
 * Inbound: both the PK2 listener service (via [Pk2Bus]) and the Classic
 * receiver deliver into the same [PebbleTransport.Listener].
 */
class WatchLink(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: PebbleTransport.Listener,
) : Pk2Bus.Consumer {

    private val uuid = UUID.fromString(Protocol.WATCHAPP_UUID)
    private val classic = PebbleTransport(context, uuid, listener)
    private val pk2Sender = DefaultPebbleSender(context)
    private val pk2Info = DefaultPebbleInfoRetriever(context)
    @Volatile private var lastWatch: ConnectedWatch? = null

    /**
     * True when a NON-watchface app that is not ours is on the watch
     * screen. Launching our watchapp would close it (one foreground app
     * on Pebble) and kill its phone-side JS — field finding 2026-08-28:
     * our self-heal launches were breaking GymTracker mid-workout.
     */
    /** True when the connected watch runs a dev firmware build (v9.9.9)
     *  — the only builds carrying the diagnostic raw-quality metric.
     *  Gating the quality push on this makes a stale persisted flag
     *  harmless after a downgrade to stock (review finding 7). */
    fun firmwareIsDevBuild(): Boolean = lastWatch?.let {
        it.firmwareVersionMajor == 9 && it.firmwareVersionMinor == 9 &&
            it.firmwareVersionPatch == 9
    } ?: false

    suspend fun foreignAppActive(): Boolean {
        val watch = lastWatch ?: return false
        return runCatching {
            withTimeoutOrNull(2_000) {
                val app = pk2Info.getActiveApp(watch.id).first()
                app != null && app.type != Watchapp.Type.WATCHFACE &&
                    app.id.toString() != Protocol.WATCHAPP_UUID
            }
        }.getOrNull() ?: false
    }

    fun start() {
        classic.start()
        Pk2Bus.attach(this)
        // Authoritative connection truth: the Core app's content provider
        // streams the connected-watch list. Without this, a powered-off
        // watch kept showing "watch ✓" — PK2 app-open events only ever
        // reported *connected*, never gone (E2E field finding 2026-08-18).
        // Disconnects are DEBOUNCED: the provider can flicker empty for a
        // moment during watch state churn (field finding 2026-08-28 —
        // starting a workout produced a phantom disconnect+reconnect,
        // whose self-heal put our watchapp on the wearer's screen).
        // Connects report instantly; a disconnect must persist 5 s.
        scope.launch {
            var pendingDown: Job? = null
            runCatching {
                pk2Info.getConnectedWatches().collect { watches ->
                    CmLog.i(TAG, "pk2: connected watches = ${watches.size}")
                    lastWatch = watches.firstOrNull() ?: lastWatch
                    pendingDown?.cancel()
                    if (watches.isNotEmpty()) {
                        listener.onConnectionChanged(true)
                    } else {
                        pendingDown = scope.launch {
                            delay(5_000)
                            listener.onConnectionChanged(false)
                        }
                    }
                }
            }.onFailure {
                CmLog.w(TAG, "pk2 watch-state flow unavailable ($it) — " +
                    "connection detection degraded to classic broadcasts")
            }
        }
    }

    fun stop() {
        Pk2Bus.detach(this)
        classic.stop()
        runCatching { pk2Sender.close() }
    }

    /** True between the Pebble app's opened and closed callbacks for our watchapp. */
    @Volatile var watchappOpen = false
        private set

    fun send(data: Map<Int, Any>) {
        scope.launch {
            // Per-watch result map; null = the phone app has no PK2 at all.
            val results: Map<WatchIdentifier, TransmissionResult>? = runCatching {
                pk2Sender.sendDataToPebble(uuid, data.toPk2Dictionary())
            }.getOrElse { e ->
                CmLog.d(TAG, "pk2 send threw: $e")
                null
            }
            when {
                results == null -> {
                    CmLog.d(TAG, "pk2 unavailable — classic fallback")
                    classic.send(data)
                }
                results.values.any { it is TransmissionResult.Success } ->
                    CmLog.d(TAG, "pk2 tx ok $data")
                results.values.all {
                    it is TransmissionResult.FailedNoPermissions ||
                        it is TransmissionResult.Unknown
                } -> {
                    CmLog.d(TAG, "pk2 rejected ($results) — classic fallback")
                    classic.send(data)
                }
                else -> // watch-state failure: authoritative, don't double-send
                    CmLog.w(TAG, "watch send failed: $results")
            }
        }
    }

    fun startWatchapp() {
        scope.launch {
            val results = runCatching { pk2Sender.startAppOnTheWatch(uuid) }
                .getOrNull()
            if (results == null ||
                results.values.none { it is TransmissionResult.Success }) {
                CmLog.d(TAG, "pk2 startApp $results — classic fallback")
                classic.startWatchapp()
            }
        }
    }

    // ---- Pk2Bus.Consumer: inbound PK2 -> same listener as Classic ----

    override fun onPk2Message(data: Map<Int, Any>) = listener.onAppMessage(data)

    override fun onPk2AppOpened() {
        watchappOpen = true
        CmLog.i(TAG, "pk2: watchapp opened")
        listener.onConnectionChanged(true)
        listener.onWatchappOpened()
    }

    override fun onPk2AppClosed() {
        watchappOpen = false
        CmLog.i(TAG, "pk2: watchapp closed (worker keeps monitoring)")
    }

    private fun Map<Int, Any>.toPk2Dictionary():
        Map<UInt, PebbleDictionaryItem> = entries.associate { (k, v) ->
        k.toUInt() to when (v) {
            is Int -> PebbleDictionaryItem.Int32(v)
            is String -> PebbleDictionaryItem.Text(v)
            is ByteArray -> PebbleDictionaryItem.Bytes(v)
            else -> error("unsupported type for key $k")
        }
    }

    companion object { private const val TAG = "WatchLink" }
}
