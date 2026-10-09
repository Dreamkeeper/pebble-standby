package org.cryomonitor.companion.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.cryomonitor.companion.MonitorService
import org.cryomonitor.companion.R
import org.cryomonitor.companion.WatchConfig
import org.cryomonitor.companion.WatchConfig.Field

/**
 * "What Standby watches for": one page per detector with the big switch,
 * what it detects and what it cannot, then its thresholds as pickers
 * (DESIGN §4.5). Every change goes to WatchConfig and asks the service
 * to push it; the row shows "pending" until the watch confirms.
 */
enum class Detector(
    val enabledField: Field,
    val title: Int, val what: Int, val limits: Int,
    val knobs: List<Knob>,
) {
    PULSE(Field.PULSE_ENABLED, R.string.det_pulse, R.string.det_pulse_what, R.string.det_pulse_limits,
        listOf(Knob(Field.PULSE_LOST_AFTER_S, R.string.knob_pulse_lost, Unit.SECONDS, 30),
               Knob(Field.PULSE_FLAT_AFTER_S, R.string.knob_pulse_flat, Unit.SECONDS, 60),
               Knob(Field.PULSE_SNOOZE_MIN, R.string.knob_pulse_snooze, Unit.MINUTES, 5))),
    IMPACT(Field.IMPACT_ENABLED, R.string.det_impact, R.string.det_impact_what, R.string.det_impact_limits,
        listOf(Knob(Field.IMPACT_IMMOBILE_S, R.string.knob_impact_still, Unit.SECONDS, 10),
               Knob(Field.SHOCK_IMMOBILE_S, R.string.knob_shock_still, Unit.SECONDS, 10),
               Knob(Field.COUNTDOWN_IMPACT_S, R.string.knob_impact_countdown, Unit.SECONDS, 5))),
    NONMOTION(Field.NONMOTION_ENABLED, R.string.det_nonmotion, R.string.det_nonmotion_what, R.string.det_nonmotion_limits,
        listOf(Knob(Field.NONMOTION_DAY_MIN, R.string.knob_nonmotion_day, Unit.MINUTES, 10),
               Knob(Field.NONMOTION_NIGHT_MIN, R.string.knob_nonmotion_night, Unit.MINUTES, 10))),
    CHECKIN(Field.CHECKIN_ENABLED, R.string.det_checkin, R.string.det_checkin_what, R.string.det_checkin_limits,
        listOf(Knob(Field.CHECKIN_INTERVAL_MIN, R.string.knob_checkin_interval, Unit.MINUTES, 30),
               Knob(Field.CHECKIN_GRACE_MIN, R.string.knob_checkin_grace, Unit.MINUTES, 5),
               Knob(Field.CHECKIN_REMIND_MIN, R.string.knob_checkin_remind, Unit.MINUTES, 5))),
    NOTWORN(Field.NOTWORN_ENABLED, R.string.det_notworn, R.string.det_notworn_what, R.string.det_notworn_limits,
        listOf(Knob(Field.NOTWORN_AFTER_MIN, R.string.knob_notworn_after, Unit.MINUTES, 1),
               Knob(Field.SENSOR_FAULT_AFTER_MIN, R.string.knob_sensor_after, Unit.MINUTES, 1)));

    enum class Unit { SECONDS, MINUTES, HOUR }
    data class Knob(val field: Field, val label: Int, val unit: Unit, val step: Int)
}

/** Short value line for the Settings row, e.g. "On · asks after 2½ min without signal". */
@Composable
fun detectorValueLine(d: Detector, cfg: WatchConfig): String {
    if (!cfg.detectorEnabled(d.enabledField)) return stringResource(R.string.state_off)
    val on = stringResource(R.string.state_on)
    val detail = when (d) {
        Detector.PULSE -> stringResource(R.string.det_pulse_value, humanDuration(cfg.get(Field.PULSE_LOST_AFTER_S)))
        Detector.IMPACT -> stringResource(R.string.det_impact_value, cfg.get(Field.IMPACT_IMMOBILE_S), cfg.get(Field.SHOCK_IMMOBILE_S))
        Detector.NONMOTION -> stringResource(R.string.det_nonmotion_value,
            cfg.get(Field.NONMOTION_DAY_MIN), cfg.get(Field.NONMOTION_NIGHT_MIN))
        Detector.CHECKIN -> stringResource(R.string.det_checkin_value, humanMinutes(cfg.get(Field.CHECKIN_INTERVAL_MIN)))
        Detector.NOTWORN -> stringResource(R.string.det_notworn_value, cfg.get(Field.NOTWORN_AFTER_MIN))
    }
    val pending = d.knobs.any { cfg.isPending(it.field) } || cfg.isPending(d.enabledField)
    return "$on · $detail" + if (pending) " · " + stringResource(R.string.state_pending) else ""
}

fun humanDuration(seconds: Int): String = when {
    seconds % 60 == 0 -> "${seconds / 60} min"
    seconds >= 60 && seconds % 30 == 0 -> "${seconds / 60}½ min"
    else -> "$seconds s"
}

fun humanMinutes(min: Int): String = when {
    min % 60 == 0 -> "${min / 60} h"
    min > 60 -> "${min / 60} h ${min % 60} min"
    else -> "$min min"
}

/** Tell the service a value changed so it reaches the watch (design D5). */
fun notifyWatchConfigChanged(context: Context) {
    context.startService(Intent(context, MonitorService::class.java)
        .setAction(MonitorService.ACTION_WATCH_CONFIG_CHANGED))
}

@Composable
fun DetectorScreen(d: Detector, cfg: WatchConfig, onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(cfg.detectorEnabled(d.enabledField)) }
    SubScreen(stringResource(d.title), onBack) {
        Card(Modifier.padding(16.dp).fillMaxWidth(),
             colors = CardDefaults.cardColors(
                 containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer
                                  else MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (enabled) R.string.state_on else R.string.state_off),
                         style = MaterialTheme.typography.titleLarge)
                    if (cfg.isPending(d.enabledField)) Text(stringResource(R.string.state_pending_long),
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    cfg.set(d.enabledField, if (it) 1 else 0)
                    notifyWatchConfigChanged(context)
                })
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.det_what_header), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(d.what), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.det_limits_header), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(d.limits), style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.det_knobs_header), style = MaterialTheme.typography.titleMedium,
             modifier = Modifier.padding(horizontal = 16.dp))
        d.knobs.forEach { k -> KnobRow(k, cfg) }
    }
}

@Composable
fun KnobRow(k: Detector.Knob, cfg: WatchConfig) {
    val context = LocalContext.current
    var value by remember { mutableIntStateOf(cfg.get(k.field)) }
    val f = k.field
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(k.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(formatKnob(k, value), style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { value = (Math.round(it / k.step) * k.step).coerceIn(f.min, f.max) },
            onValueChangeFinished = {
                if (cfg.set(f, value)) notifyWatchConfigChanged(context)
            },
            valueRange = f.min.toFloat()..f.max.toFloat(),
            steps = ((f.max - f.min) / k.step - 1).coerceAtLeast(0),
        )
        Text(stringResource(R.string.knob_default, formatKnob(k, f.default)) +
             if (cfg.isPending(f)) " · " + stringResource(R.string.state_pending) else "",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatKnob(k: Detector.Knob, v: Int): String = when (k.unit) {
    Detector.Unit.SECONDS -> humanDuration(v)
    Detector.Unit.MINUTES -> humanMinutes(v)
    Detector.Unit.HOUR -> "%02d:00".format(v)
}

/** "When it asks": the ladder timings and the night hours. */
@Composable
fun WhenItAsksScreen(cfg: WatchConfig, onBack: () -> Unit) {
    SubScreen(stringResource(R.string.when_title), onBack) {
        Text(stringResource(R.string.when_intro), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        KnobRow(Detector.Knob(Field.CHECKIN_UI_S, R.string.knob_time_to_answer, Detector.Unit.SECONDS, 5), cfg)
        KnobRow(Detector.Knob(Field.COUNTDOWN_S, R.string.knob_countdown, Detector.Unit.SECONDS, 5), cfg)
        KnobRow(Detector.Knob(Field.COUNTDOWN_IMPACT_S, R.string.knob_impact_countdown, Detector.Unit.SECONDS, 5), cfg)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.when_night), style = MaterialTheme.typography.titleMedium,
             modifier = Modifier.padding(horizontal = 16.dp))
        Text(stringResource(R.string.when_night_explain), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        KnobRow(Detector.Knob(Field.NIGHT_START_HOUR, R.string.knob_night_start, Detector.Unit.HOUR, 1), cfg)
        KnobRow(Detector.Knob(Field.NIGHT_END_HOUR, R.string.knob_night_end, Detector.Unit.HOUR, 1), cfg)
    }
}

/** Value line for the "When it asks" row. */
fun whenItAsksValue(cfg: WatchConfig): String =
    "${cfg.get(Field.CHECKIN_UI_S)} s · ${cfg.get(Field.COUNTDOWN_S)} s" +
    " · %02d:00–%02d:00".format(cfg.get(Field.NIGHT_START_HOUR), cfg.get(Field.NIGHT_END_HOUR))
