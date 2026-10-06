package org.cryomonitor.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide snapshot the service publishes and the UI observes
 * (design D1). Replaces the per-screen polling of static fields.
 */
object LiveState {
    private val _facts = MutableStateFlow(CoverageFacts())
    val facts: StateFlow<CoverageFacts> = _facts.asStateFlow()

    private val _watchBattery = MutableStateFlow<Int?>(null)
    val watchBattery: StateFlow<Int?> = _watchBattery.asStateFlow()

    fun publish(facts: CoverageFacts, watchBattery: Int?) {
        _facts.value = facts
        _watchBattery.value = watchBattery
    }
}
