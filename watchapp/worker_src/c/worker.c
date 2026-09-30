/*
 * Background worker: 24/7 sensing + detection.
 *
 * Runs the detector core against the accelerometer and (where present) the
 * heart rate sensor. The worker cannot vibrate, show UI, or send AppMessage
 * (M0 spike S3 pending) — any action needing those launches the foreground
 * app via worker_launch_app() with the action parked in persist storage.
 *
 * Memory budget: 10.5 kB. Keep static state minimal; no heap use beyond
 * what the event subscriptions allocate (~2 kB for HealthService).
 */
#include <pebble_worker.h>
#include "../../src/core/detectors.h"
#include "../../src/core/protocol.h"

static cm_core s_core;
static uint8_t s_hr_burst_active;
static uint8_t s_last_gated;   /* last HR event was zeroed by the quality gate */
static uint16_t s_heartbeat_countdown = CM_HEARTBEAT_INTERVAL_S;
static uint16_t s_last_bpm;            /* last raw HR reading (0 = none) */
static uint16_t s_drill_countdown;     /* S1 latency drill: seconds to fire */
static uint32_t s_drill_arm_ms;        /* when WMSG_DRILL arrived */
static uint8_t s_hr_lab;               /* S4 sensor lab: burst + hold + relay */
static uint8_t s_lab_tick;
static uint16_t s_lab_elapsed_s;       /* lab watchdog: a lost stop message
                                          must not hold detectors forever */
static uint32_t s_last_hr_event_ms;    /* last HealthService HR event */
static DataLoggingSessionRef s_log_session;

/* ---- debug mode (toggled from the phone; view with `pebble logs`) ---- */
static uint8_t s_debug;
static uint8_t s_qmetric; /* diag firmware confirmed: gate liveness on quality */
#if CM_WORKER_VERBOSE
static uint16_t s_dbg_motion_events;   /* per-minute counters */
static uint16_t s_dbg_last_mag;
static uint16_t s_dbg_last_bpm;
static uint16_t s_dbg_hr_updates;
#endif

/* Worker code+strings share the 10.5 kB RAM budget with the heap: every
 * format string here costs heap bytes on every wearer's wrist. Verbose
 * logging therefore compiles OUT by default (heap crisis 2026-08-29:
 * 192 B free). Build with -DCM_WORKER_VERBOSE=1 for a debug .pbw;
 * WARNING/ERROR logs always stay. */
#ifndef CM_WORKER_VERBOSE
#define CM_WORKER_VERBOSE 0
#endif
#if CM_WORKER_VERBOSE
#define WLOG(...) APP_LOG(APP_LOG_LEVEL_INFO, __VA_ARGS__)
#define DLOG(...) do { \
    if (s_debug) APP_LOG(APP_LOG_LEVEL_DEBUG, __VA_ARGS__); \
  } while (0)
#else
#define WLOG(...) ((void)0)
#define DLOG(...) ((void)0)
#endif

#if CM_WORKER_VERBOSE
#if CM_WORKER_VERBOSE
static const char *action_name(uint8_t t) {
  switch (t) {
    case CM_ACT_HR_BURST_ON:      return "HR_BURST_ON";
    case CM_ACT_HR_BURST_OFF:     return "HR_BURST_OFF";
    case CM_ACT_CHECKIN_START:    return "CHECKIN_START";
    case CM_ACT_COUNTDOWN_START:  return "COUNTDOWN_START";
    case CM_ACT_ALERT_CANCELLED:  return "ALERT_CANCELLED";
    case CM_ACT_ALARM:            return "ALARM";
    case CM_ACT_NOTWORN_NAG:      return "NOTWORN_NAG";
    case CM_ACT_CHECKIN_REMINDER: return "CHECKIN_REMINDER";
    case CM_ACT_SUSPEND_STARTED:  return "SUSPEND_STARTED";
    case CM_ACT_SUSPEND_EXPIRED:  return "SUSPEND_EXPIRED";
    case CM_ACT_AUTO_RESUMED:     return "AUTO_RESUMED";
    default:                      return "?";
  }
}
#endif
#endif

/* Heartbeat record v2 for DataLogging (phone-side watchdog + audit
 * trail + remote detector diagnostics — tag CM_DL_TAG, 14 bytes). */
typedef struct __attribute__((packed)) {
  uint32_t epoch_s;
  uint8_t stage;
  uint8_t battery_pct;
  uint8_t last_bpm;
  uint8_t suspended;
  uint16_t change_age_s;   /* since last bpm VALUE CHANGE (liveness) */
  uint16_t motion_age_s;
  uint8_t flags;           /* CM_DIAG_* bits */
  uint8_t heap64;          /* free worker heap / 64 (0 = unknown; was pad) */
  uint16_t episode;        /* v3: ladder episode id (0 = none) - makes the
                              spooled channel an authoritative ALARM
                              recovery path on the phone */
} cm_heartbeat_rec;

static uint32_t now_ms(void) {
  time_t s;
  uint16_t ms;
  time_ms(&s, &ms);
  return (uint32_t)s * 1000u + ms;
}

/* Monotonic detector clock (delivery hardening D6): all cm_* calls use
 * this tick-driven time, so a wall-clock correction (phone sync, DST)
 * can never stretch or shorten a detector window or countdown. Wall
 * clock remains for DL epochs, drill stamps, and persisted suspension
 * deadlines. */
static uint32_t s_mono_ms;
static uint32_t mono_ms(void) { return s_mono_ms; }

static __attribute__((noinline)) uint16_t age_s(uint32_t since_ms) {
  uint32_t a = (mono_ms() - since_ms) / 1000u;
  return (uint16_t)(a > 9999u ? 9999u : a);
}

static uint8_t diag_flags(void) {
  /* All source fields are normalized booleans (pulse_phase is 0/1).
   * Shifts compile to straight-line Thumb instructions instead of six
   * conditional selections. */
  return (uint8_t)(s_core.charging |
                   (s_core.lab_hold << 1) |
                   (s_core.pulse_phase << 2) |
                   (s_core.notworn_nagged << 3) |
                   (s_core.ever_pulse << 4) |
                   (s_core.suspended << 5) |
                   (s_last_gated << 6));
}

static __attribute__((noinline)) void push_status_to_app(void) {
  /* Minutes round UP: a fresh 30-min suspension reads "30", not "29". */
  uint32_t heap = heap_bytes_free();
  if (heap > 255u * 64u) heap = 255u * 64u;
  /* v2 pack (delivery hardening D4):
   * data0 = stage(b0-2) | detector<<3 (b3-5) | charging<<7 | bpm<<8
   * data1 = episode id (0 when idle)
   * data2 = low byte: stage-remaining s (stage active) OR suspend
   *         remaining MIN (idle), both capped 255; high byte: heap/64 */
  uint8_t stage = (uint8_t)cm_current_stage(&s_core);
  uint32_t low = stage != CM_STAGE_NONE
      ? cm_stage_remaining_s(&s_core, mono_ms())
      : (cm_suspend_remaining_s(&s_core, mono_ms()) + 59u) / 60u;
  if (low > 255u) low = 255u;
  AppWorkerMessage m = {
    .data0 = (uint16_t)(stage | ((s_core.stage_det & 0x7) << 3) |
                        ((uint16_t)s_core.charging << 7) |
                        ((uint16_t)(s_last_bpm > 255 ? 255 : s_last_bpm) << 8)),
    .data1 = (uint16_t)(stage != CM_STAGE_NONE ? s_core.episode : 0),
    .data2 = (uint16_t)(low | ((heap / 64u) << 8)),
  };
  app_worker_send_message(WMSG_STATUS, &m);
  if (s_debug) {
    /* The exact numbers the not-worn/pulse gates run on — the field
     * debugging channel for "why didn't it fire". */
    AppWorkerMessage d = {
      .data0 = age_s(s_core.last_bpm_change_ms),
      .data1 = age_s(s_core.last_motion_ms),
      .data2 = diag_flags(),
    };
    app_worker_send_message(WMSG_DIAG, &d);
  }
}

/* Tell the foreground app about an action.
 *
 * `launch` must be true ONLY when the wearer has to see or do something
 * right now: launching takes over the screen and hides the watchface, so
 * informational events (cancellations, nags, suspension changes) are
 * delivered just to an app that already happens to be open. */
static void notify_app(const cm_action *a, bool launch) {
  AppWorkerMessage m = {
    .data0 = a->type, .data1 = a->detector, .data2 = a->seconds,
  };
  app_worker_send_message(WMSG_ACTION, &m); /* no-op if app not running */
  if (!launch) return;
  persist_write_data(PK_PENDING_ACTION, a, sizeof(*a));
  persist_write_int(PK_PENDING_ACTION_T, (int32_t)time(NULL));
  worker_launch_app();
}

static void set_hr_burst(bool on) {
#if defined(PBL_HEALTH)
  /* Idle cadence: 60 s while monitoring, 300 s while suspended or on
   * the charger — the sensor only serves auto-resume / re-arm then. */
  health_service_set_heart_rate_sample_period(
      on ? CM_HR_PERIOD_BURST_S
         : (s_core.suspended || s_core.charging) ? CM_HR_PERIOD_HOLD_S
                                                  : CM_HR_PERIOD_NORMAL_S);
#endif
  s_hr_burst_active = on ? 1 : 0;
}

static void drain_actions(void) {
  cm_action a;
  while (cm_next_action(&s_core, &a)) {
    DLOG("act %s det=%u sec=%u reason=%u",
         action_name(a.type), a.detector, a.seconds, a.reason);
    switch (a.type) {
      case CM_ACT_HR_BURST_ON:  set_hr_burst(true);  break;
      case CM_ACT_HR_BURST_OFF: set_hr_burst(false); break;

      /* Needs the wearer's attention now: take the screen. */
      case CM_ACT_CHECKIN_START:
      case CM_ACT_COUNTDOWN_START:
      case CM_ACT_ALARM:
        /* Episode ids must survive restarts: persist the sequence when a
         * new episode starts (mint happens core-side). */
        persist_write_int(PK_EPISODE_SEQ, s_core.episode_seq);
        notify_app(&a, true);
        break;
      /* Only reached when the wearer opted into scheduled check-ins, i.e.
       * asked to be prompted. */
      case CM_ACT_CHECKIN_REMINDER:
        notify_app(&a, true);
        break;

      /* The watch looks off-wrist: nobody is watching the watchface, so
       * taking the screen for the nag is the point — otherwise the nag
       * is invisible in worker mode (the worker cannot vibrate). */
      case CM_ACT_NOTWORN_NAG:
        WLOG("NOTWORN nag -> launching app");
        notify_app(&a, true);
        break;
      /* Pulse monitoring is blind while the wearer moves: the wearer must
       * see the reboot/suspend guidance, so take the screen. */
      case CM_ACT_SENSOR_FAULT:
        WLOG("SENSOR fault -> launching app");
        notify_app(&a, true);
        break;

      /* Informational: never hijack the watchface for these. A
       * cancellation also invalidates any parked launch action — the
       * racing app launch must find nothing rather than a stale alert. */
      case CM_ACT_ALERT_CANCELLED:
        persist_delete(PK_PENDING_ACTION);
        persist_delete(PK_PENDING_ACTION_T);
        notify_app(&a, false);
        break;
      case CM_ACT_SUSPEND_STARTED:
        set_hr_burst(false); /* -> 5-min sample cadence for the hold */
        notify_app(&a, false);
        break;
      /* Resume clears the persisted suspension — without this a worker
       * restart would silently re-suspend until the original expiry. */
      /* Resume/expiry must reach the PHONE, and the app is its only
       * mouthpiece: launch it briefly (field bug: phone showed
       * SUSPENDED 20m long after auto-resume because the closed app
       * never relayed PMSG_SUSPENDED=0). The wearer also gets the
       * double-pulse feedback; the guard returns the watchface. */
      case CM_ACT_SUSPEND_EXPIRED:
      case CM_ACT_AUTO_RESUMED:
        persist_delete(PK_SUSPEND_UNTIL);
        persist_delete(PK_SUSPEND_AUTORESUME);
        set_hr_burst(false); /* back to the 60 s monitoring cadence */
        notify_app(&a, true);
        break;
      /* Dock/undock are deliberate wearer acts: launch the app briefly so
       * the wearer sees "Charging"/"Monitoring" AND the phone learns the
       * hold state + fresh battery (the worker itself cannot AppMessage;
       * these were silently dropped before — field bug 2026-08-27). The
       * auto-launch guard returns the watchface within seconds. */
      case CM_ACT_CHARGING_STARTED:
      case CM_ACT_CHARGING_ENDED:
        set_hr_burst(false); /* hold cadence on dock, normal on undock */
        notify_app(&a, true);
        break;
      default: break;
    }
  }
  if (s_core.q_overflow) {
    s_core.q_overflow = 0;
    /* Surfaced to phone as FAULT via the next heartbeat. TODO: flag field. */
  }
}

#if CM_VIBE_DIAG
/* Experiment (2026-09-30): count samples the firmware flags did_vibrate.
 * Persisted on every flagged batch so the app can show them later even
 * though opening the app itself is an app launch/exit. */
static uint32_t s_vd_samples, s_vd_bursts;
#endif

static void accel_handler(AccelData *data, uint32_t num_samples) {
  /* AccelData layout matches cm_accel_sample closely; repack (int16 x/y/z). */
  cm_accel_sample s[25];
  uint32_t n = num_samples > 25 ? 25 : num_samples;
#if CM_VIBE_DIAG
  uint32_t flagged = 0;
#endif
  for (uint32_t i = 0; i < n; i++) {
    s[i].x = data[i].x;
    s[i].y = data[i].y;
    s[i].z = data[i].z;
    s[i].did_vibrate = data[i].did_vibrate ? 1 : 0;
#if CM_VIBE_DIAG
    flagged += s[i].did_vibrate;
#endif
  }
#if CM_VIBE_DIAG
  if (flagged) {
    if (!s_vd_bursts && persist_exists(PK_VIBE_DIAG_SAMPLES)) {
      s_vd_samples = (uint32_t)persist_read_int(PK_VIBE_DIAG_SAMPLES);
      s_vd_bursts = (uint32_t)persist_read_int(PK_VIBE_DIAG_BURSTS);
    }
    s_vd_samples += flagged;
    s_vd_bursts++;
    persist_write_int(PK_VIBE_DIAG_SAMPLES, (int32_t)s_vd_samples);
    persist_write_int(PK_VIBE_DIAG_BURSTS, (int32_t)s_vd_bursts);
    persist_write_int(PK_VIBE_DIAG_LAST_T, (int32_t)time(NULL));
    WLOG("vibe-diag: %lu flagged samples (total %lu, bursts %lu)",
         (unsigned long)flagged, (unsigned long)s_vd_samples, (unsigned long)s_vd_bursts);
  }
#endif
#if CM_WORKER_VERBOSE
  uint32_t motion_before = s_core.last_motion_ms;
#endif
  cm_accel_feed(&s_core, s, n, mono_ms());
#if CM_WORKER_VERBOSE
  if (s_debug) {
    if (s_core.last_motion_ms != motion_before) s_dbg_motion_events++;
    s_dbg_last_mag = s_core.prev_mag;
  }
#endif
  drain_actions();
}

#if defined(PBL_HEALTH)
static void health_handler(HealthEventType event, void *context) {
  if (event == HealthEventHeartRateUpdate || event == HealthEventSignificantUpdate) {
    HealthValue bpm = health_service_peek_current_value(HealthMetricHeartRateRawBPM);
    s_last_gated = 0;
    /* Quality gate (lab 2026-08-29, n=450+): worn readings never fall
     * below Acceptable (loose strap included), while ambient-light noise
     * that fools the wear classifier is the only way a nonzero bpm can
     * reach us off-body. When the diag firmware's raw-quality metric is
     * available (phone-confirmed — stock firmware asserts on the unknown
     * metric), a sub-Acceptable reading counts as NO signal for
     * liveness/resume. Lab streaming stays raw. */
    if (s_qmetric && s_mono_ms > 90000u && bpm > 0) {
      HealthValue q = health_service_peek_current_value((HealthMetric)9);
      if (q < 2 /* HRMQuality_Acceptable */) {
        DLOG("hr raw=%d gated: quality=%d", (int)bpm, (int)q);
        bpm = 0;
        s_last_gated = 1;
      }
    }
    s_last_bpm = bpm > 0 ? (uint16_t)bpm : 0;
    s_last_hr_event_ms = mono_ms();
#if CM_WORKER_VERBOSE
    if (s_debug) {
      s_dbg_hr_updates++;
      s_dbg_last_bpm = s_last_bpm;
      DLOG("hr raw=%d burst=%u", (int)bpm, s_hr_burst_active);
    }
#endif
    cm_hr_feed(&s_core, bpm > 0 ? (uint16_t)bpm : 0, mono_ms());
    drain_actions();
  }
}
#endif

/* On the charger = deliberately off-wrist: implicit suspension. The core
 * silences detectors while plugged and resets baselines on unplug. */
static void battery_handler(BatteryChargeState state) {
  cm_set_charging(&s_core, state.is_plugged, mono_ms());
  drain_actions();
}

static void log_heartbeat(void) {
  uint32_t heap64 = heap_bytes_free() / 64u;
  if (heap64 > 255u) heap64 = 255u;
  uint8_t stage = (uint8_t)cm_current_stage(&s_core);
  cm_heartbeat_rec rec = {
    .epoch_s = (uint32_t)time(NULL),
    /* v3: low nibble = stage, high nibble = stage detector */
    .stage = (uint8_t)(stage |
                       (stage != CM_STAGE_NONE ? (s_core.stage_det << 4) : 0)),
    .battery_pct = battery_state_service_peek().charge_percent,
    .last_bpm = (uint8_t)(s_last_bpm > 255 ? 255 : s_last_bpm),
    .suspended = s_core.suspended,
    .change_age_s = age_s(s_core.last_bpm_change_ms),
    .motion_age_s = age_s(s_core.last_motion_ms),
    .flags = diag_flags(),
    /* Heap low-water telemetry (soak): a worker allocation failure is a
     * crash risk at the worst moment, so the margin must be a trended
     * number, not an anecdote. */
    .heap64 = (uint8_t)heap64,
    .episode = (uint16_t)(stage != CM_STAGE_NONE ? s_core.episode : 0),
  };
  data_logging_log(s_log_session, &rec, 1);
}

static uint32_t s_last_wall_ms;

/* The wearer's alarm clock is a strong, long vibration the worker cannot
 * see coming through did_vibrate (see cm_vibe_guard): peek the next
 * enabled alarm and guard from shortly before it rings until well after
 * (field 2026-09-30 07:30: alarm vibration -> "hard shock" -> check-in).
 * Snoozed re-rings are not exposed by the firmware. On diorite the SDK
 * defines the peek as 0 and this compiles to nothing. */
#define CM_ALARM_GUARD_LEAD_S 15
#define CM_ALARM_GUARD_TAIL_S 120
static void alarm_guard_check(void) {
  time_t next = 0;
  if (!alarm_service_peek_next(&next)) return;
  int32_t delta = (int32_t)(next - time(NULL)); /* seconds until it rings */
  if (delta > CM_ALARM_GUARD_LEAD_S || delta < -CM_ALARM_GUARD_TAIL_S) return;
  cm_vibe_guard(&s_core, (uint32_t)(delta + CM_ALARM_GUARD_TAIL_S) * 1000u, mono_ms());
}

static void tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  s_mono_ms += 1000;
  if (tick_time->tm_sec % 10 == 0) alarm_guard_check();
  /* Wall-clock jump detection: suspensions deliberately keep wall-clock
   * semantics ("30 min" means 30 wall minutes), so on a correction the
   * persisted epoch deadline re-syncs the mono deadline. Detectors are
   * untouched - they only ever see the mono clock. */
  uint32_t wall = now_ms();
  if (s_last_wall_ms) {
    int32_t drift = (int32_t)(wall - s_last_wall_ms) - 1000;
    if (drift > 5000 || drift < -5000) {
      APP_LOG(APP_LOG_LEVEL_WARNING, "wall clock jumped %ld ms", (long)drift);
      if (s_core.suspended && persist_exists(PK_SUSPEND_UNTIL)) {
        time_t until = (time_t)persist_read_int(PK_SUSPEND_UNTIL);
        time_t nowep = time(NULL);
        uint32_t rem = until > nowep ? (uint32_t)(until - nowep) : 0;
        cm_suspend_sync_remaining(&s_core, rem, mono_ms());
      }
    }
  }
  s_last_wall_ms = wall;
  cm_tick(&s_core, mono_ms(), (uint8_t)tick_time->tm_hour);
  drain_actions();

  /* S1 latency drill: fire a synthetic alert through the REAL alarm path
   * (persist handoff + worker_launch_app) and stamp the fire time so the
   * app can compute the cold-launch latency on a shared clock. */
  if (s_drill_countdown && --s_drill_countdown == 0) {
    cm_action a = {.type = CM_ACT_LATENCY_DRILL, .detector = 0, .seconds = 0};
    persist_write_int(PK_DRILL_ARM_MS, (int32_t)s_drill_arm_ms);
    persist_write_int(PK_DRILL_FIRE_MS, (int32_t)now_ms());
    notify_app(&a, true);
    WLOG("latency drill fired");
  }

  /* S4 sensor lab: every 2 s relay the raw peek value, the event age,
   * and the free heap to the (open) app -> phone. The peek is exactly
   * what the detectors would see; the age shows whether the sensor is
   * still producing events at all in this wear condition. */
  /* Lab watchdog: the stop message travels app->worker and can be lost
   * if the app closes at the wrong moment — auto-release after 30 min. */
  if (s_hr_lab && ++s_lab_elapsed_s >= 1800) {
    s_hr_lab = 0;
    s_lab_elapsed_s = 0;
    cm_set_lab_hold(&s_core, 0, now_ms());
    drain_actions();
    set_hr_burst(false);
    APP_LOG(APP_LOG_LEVEL_WARNING, "sensor lab timed out — hold released");
  }

  if (s_hr_lab && (++s_lab_tick & 1)) {
    HealthValue v = 0;
    HealthValue filt = 0;
    uint16_t q_enc = 255; /* n/a: quality metric not requested */
#if defined(PBL_HEALTH)
    v = health_service_peek_current_value(HealthMetricHeartRateRawBPM);
    filt = health_service_peek_current_value(HealthMetricHeartRateBPM);
    if (s_hr_lab == 2) {
      /* Fork-firmware diagnostic (hr-quality-diag): HealthMetric 9 =
       * raw HRMQuality of the newest sample. Guarded by lab mode 2 —
       * stock firmware asserts on unknown metrics, so the phone only
       * requests this when the wearer confirms the diag firmware. */
      HealthValue q = health_service_peek_current_value((HealthMetric)9);
      q_enc = (uint16_t)(q + 1); /* OffWrist(-1)->0 .. Excellent(4)->5 */
      if (q_enc > 5) q_enc = 255;
    }
#endif
    uint32_t heap = heap_bytes_free();
    if (heap > 255u * 64u) heap = 255u * 64u;
    uint32_t age_s = s_last_hr_event_ms
        ? (mono_ms() - s_last_hr_event_ms) / 1000u : 255u;
    if (age_s > 255u) age_s = 255u;
    uint32_t filt_c = filt > 0 ? (filt > 255 ? 255u : (uint32_t)filt) : 0u;
    AppWorkerMessage lab = {
      .data0 = (uint16_t)((v > 0 ? (v > 255 ? 255 : v) : 0) | (q_enc << 8)),
      .data1 = (uint16_t)(heap / 64u),
      .data2 = (uint16_t)(age_s | (filt_c << 8)),
    };
    app_worker_send_message(WMSG_HR_SAMPLE, &lab);
  }

  if (--s_heartbeat_countdown == 0) {
    s_heartbeat_countdown = CM_HEARTBEAT_INTERVAL_S;
    log_heartbeat();
#if CM_WORKER_VERBOSE
    if (s_debug) {
      DLOG("min: stage=%u susp=%u motion_evts=%u mag=%u bpm=%u hr_upd=%u heap_free=%u",
           (unsigned)cm_current_stage(&s_core), s_core.suspended,
           s_dbg_motion_events, s_dbg_last_mag, s_dbg_last_bpm,
           s_dbg_hr_updates, (unsigned)heap_bytes_free());
      s_dbg_motion_events = 0;
      s_dbg_hr_updates = 0;
    }
#endif
  }
}

static void worker_message_handler(uint16_t type, AppWorkerMessage *m) {
  switch (type) {
    case WMSG_USER_OK: cm_user_ok(&s_core, mono_ms()); break;
    case WMSG_SUSPEND:
      cm_suspend(&s_core, (uint32_t)m->data0 * 60u, (uint8_t)m->data1, mono_ms());
      persist_write_int(PK_SUSPEND_UNTIL, (int)(time(NULL) + m->data0 * 60));
      persist_write_int(PK_SUSPEND_AUTORESUME, m->data1);
      break;
    case WMSG_RESUME:
      cm_resume(&s_core, mono_ms());
      persist_delete(PK_SUSPEND_UNTIL);
      persist_delete(PK_SUSPEND_AUTORESUME);
      break;
    case WMSG_SOS: cm_manual_sos(&s_core, mono_ms()); break;
    case WMSG_STATUS_REQ: push_status_to_app(); break;
    case WMSG_DRILL:
      s_drill_countdown = CM_DRILL_DELAY_S;
      s_drill_arm_ms = now_ms();
      WLOG("latency drill armed (%us)", CM_DRILL_DELAY_S);
      break;
    case WMSG_HR_LAB:
      s_hr_lab = (uint8_t)m->data0;
      s_lab_elapsed_s = 0;
      cm_set_lab_hold(&s_core, s_hr_lab, mono_ms());
      drain_actions();
      set_hr_burst(s_hr_lab != 0); /* 1 s sampling for the lab duration */
      WLOG("hr lab %s", s_hr_lab ? "ON" : "off");
      break;
    case WMSG_SET_DEBUG:
      s_debug = (uint8_t)m->data0;
      persist_write_int(PK_DEBUG, s_debug);
      WLOG("worker debug %s", s_debug ? "ON" : "off");
      break;
    case WMSG_VIBE: cm_vibe_guard(&s_core, m->data0, mono_ms()); break;
    case WMSG_SET_QMETRIC:
      s_qmetric = (uint8_t)m->data0;
      persist_write_int(PK_QMETRIC, s_qmetric);
      WLOG("quality gate %s", s_qmetric ? "ON" : "off");
      break;
    default: break;
  }
  DLOG("wmsg type=%u d0=%u", type, m->data0);
  drain_actions();
}

static void load_config(cm_config *cfg) {
  cm_config_defaults(cfg);
  if (persist_exists(PK_CONFIG) &&
      persist_get_size(PK_CONFIG) == (int)sizeof(*cfg)) {
    persist_read_data(PK_CONFIG, cfg, sizeof(*cfg));
  }
#if !defined(PBL_HEALTH)
  cfg->hr_available = 0;
#else
  if (!health_service_metric_accessible(HealthMetricHeartRateBPM,
                                        time(NULL), time(NULL))) {
    cfg->hr_available = 0; /* flint / gabbro */
  }
#endif
}

static void restore_suspension(void) {
  if (!persist_exists(PK_SUSPEND_UNTIL)) return;
  time_t until = (time_t)persist_read_int(PK_SUSPEND_UNTIL);
  time_t now = time(NULL);
  if (until > now) {
    cm_suspend(&s_core, (uint32_t)(until - now),
               (uint8_t)persist_read_int(PK_SUSPEND_AUTORESUME), mono_ms());
    drain_actions();
  }
}

static void init(void) {
  /* A parked launch action must not outlive the worker session that
   * emitted it: after a reboot the detector state it came from is void,
   * and replaying it delivers yesterday's nag onto a fresh boot (field
   * bug 2026-08-29). The timestamp check in the app covers the
   * launch-in-flight race; this covers everything else. */
  persist_delete(PK_PENDING_ACTION);
  persist_delete(PK_PENDING_ACTION_T);
  cm_config cfg;
  load_config(&cfg);
  cm_init(&s_core, &cfg, mono_ms());
  s_debug = persist_exists(PK_DEBUG) ? (uint8_t)persist_read_int(PK_DEBUG) : 0;
  s_qmetric = persist_exists(PK_QMETRIC) ? (uint8_t)persist_read_int(PK_QMETRIC) : 0;
  s_core.episode_seq = persist_exists(PK_EPISODE_SEQ)
      ? (uint16_t)persist_read_int(PK_EPISODE_SEQ) : 0;
#if CM_WORKER_VERBOSE
  if (s_debug) {
    APP_LOG(APP_LOG_LEVEL_INFO,
            "worker up (debug ON) hr=%u heap_free=%u",
            cfg.hr_available, (unsigned)heap_bytes_free());
  }
#endif
  restore_suspension();

  accel_service_set_sampling_rate(ACCEL_SAMPLING_25HZ);
  accel_data_service_subscribe(25, accel_handler); /* one callback per second */

  battery_state_service_subscribe(battery_handler);
  battery_handler(battery_state_service_peek()); /* seed: may boot on charger */

#if defined(PBL_HEALTH)
  if (cfg.hr_available) {
    health_service_events_subscribe(health_handler, NULL); /* costs ~2 kB heap */
    set_hr_burst(false);
  }
#endif

  tick_timer_service_subscribe(SECOND_UNIT, tick_handler);
  app_worker_message_subscribe(worker_message_handler);

  s_log_session = data_logging_create(
      CM_DL_TAG, DATA_LOGGING_BYTE_ARRAY, sizeof(cm_heartbeat_rec),
      /* resume */ true);
}

static void deinit(void) {
  data_logging_finish(s_log_session);
  app_worker_message_unsubscribe();
  tick_timer_service_unsubscribe();
#if defined(PBL_HEALTH)
  set_hr_burst(false); /* never leave the HRM in burst mode */
  health_service_events_unsubscribe();
#endif
  battery_state_service_unsubscribe();
  accel_data_service_unsubscribe();
}

int main(void) {
  init();
  worker_event_loop();
  deinit();
}
