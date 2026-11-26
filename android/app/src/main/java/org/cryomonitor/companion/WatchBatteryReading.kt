package org.cryomonitor.companion

data class WatchBatteryReading(val percent: Int, val observedAtS: Long?) {
    companion object {
        fun latest(previous: WatchBatteryReading?, percent: Int, sampleS: Long,
                   receivedS: Long): WatchBatteryReading? {
            if (percent !in 0..100) return previous
            val observed = sampleS.takeIf { it in 1..receivedS }
            val prior = previous?.observedAtS
            if (prior != null && prior <= receivedS && (observed == null || observed < prior)) {
                return previous
            }
            return WatchBatteryReading(percent, observed)
        }
    }
}
