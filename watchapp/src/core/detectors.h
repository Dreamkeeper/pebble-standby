/*
 * Standby (formerly Pebble Cryonics Monitor) — detector core.
 *
 * Platform-independent: no Pebble headers, no dynamic allocation, no
 * floating point. The same code runs inside the 10.5 kB background worker,
 * in the foreground app, and host-compiled in unit tests.
 *
 * The shell (worker or app) feeds sensor data + time in, polls actions out:
 *
 *   cm_core core;
 *   cm_config cfg; cm_config_defaults(&cfg);
 *   cm_init(&core, &cfg);
 *   ...
 *   cm_accel_feed(&core, samples, n, now_ms);   // per accel batch
 *   cm_hr_feed(&core, bpm, now_ms);             // per HR reading (0 = no signal)
 *   cm_tick(&core, now_ms, local_hour);         // 1 Hz
 *   cm_action a;
 *   while (cm_next_action(&core, &a)) { ...perform vibration/UI/AppMessage... }
 *
 * Alert ladder (per docs/PLAN.md): silent gates -> CHECKIN ("Are you OK?",
 * motion/pulse auto-dismisses except for scheduled check-ins) -> COUNTDOWN
 * (explicit button only) -> ALARM (latched until cm_user_ok).
 *
 * Removal vs. arrest discrimination: a dead wearer does not move after the
 * pulse stops, while removing a watch necessarily moves it. Motion observed
 * within removal_window_s AFTER the last valid pulse therefore classifies a
 * signal loss as "probably taken off" — routed to the not-worn nag (wearer
 * only, default 3 min) instead of the alarm ladder. Loss with no motion
 * after the last pulse keeps the full ladder (possible arrest). Documented
 * residual risks: a collapse whose motion lands just after the last pulse
 * reading is mis-read as removal (the impact detector covers falls), and a
 * perfectly gentle removal still runs the ladder (phone cancel absorbs it).
 *
 * Timestamps are uint32 milliseconds and wrap-safe for spans < 24 days.
 */
#ifndef CM_DETECTORS_H
#define CM_DETECTORS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
  int16_t x, y, z;      /* milli-g */
  uint8_t did_vibrate;  /* sample polluted by our own vibration motor */
} cm_accel_sample;

typedef enum {
  CM_DET_PULSE = 0,   /* pulse-signal loss + stillness (HR hardware only) */
  CM_DET_IMPACT,      /* freefall->impact or high-G shock, then immobility */
  CM_DET_NONMOTION,   /* no micro-movement for N minutes while worn */
  CM_DET_CHECKIN,     /* scheduled check-in missed */
  CM_DET_NOTWORN,     /* watch appears off-wrist without suspension (nag only) */
  CM_DET_SOS,         /* manual SOS */
  CM_DET_SENSOR,      /* no pulse signal while motion continues: sensor dead
                         or watch carried off-wrist (nag only) */
  CM_DET_COUNT
} cm_detector;

typedef enum {
  CM_ACT_NONE = 0,
  CM_ACT_HR_BURST_ON,      /* shell: set HR sample period to 1 s (pulse hunt) */
  CM_ACT_HR_BURST_OFF,     /* shell: restore normal HR sample period */
  CM_ACT_CHECKIN_START,    /* shell: vibrate + "Are you OK?" UI; .detector, .seconds */
  CM_ACT_COUNTDOWN_START,  /* shell: escalate vibration, notify phone (pre-alarm); .detector, .seconds */
  CM_ACT_ALERT_CANCELLED,  /* alert dismissed; .detector, .reason (cm_cancel_reason) */
  CM_ACT_ALARM,            /* shell: full alarm to phone; .detector */
  CM_ACT_NOTWORN_NAG,      /* shell: gentle watch+phone nag, never contacts */
  CM_ACT_CHECKIN_REMINDER, /* shell: vibrate "check-in due in N min"; .seconds until due */
  CM_ACT_SUSPEND_STARTED,  /* .seconds = duration */
  CM_ACT_SUSPEND_EXPIRED,
  CM_ACT_AUTO_RESUMED,     /* wear signals returned early; monitoring resumed */
  CM_ACT_LATENCY_DRILL,    /* M0 S1 test tooling: synthetic launch, no ladder.
                              Emitted by the worker shell, never by the core. */
  CM_ACT_CHARGING_STARTED, /* on charger = deliberate off-wrist: implicit hold */
  CM_ACT_CHARGING_ENDED,   /* unplugged: baselines reset, monitoring resumes */
  CM_ACT_SENSOR_FAULT      /* shell: "no pulse signal, motion continues" nag —
                              sensor dead or carried off-wrist; never contacts */
} cm_action_type;

typedef enum {
  CM_CANCEL_MOTION = 0,  /* implicit: wearer moved during CHECKIN stage */
  CM_CANCEL_PULSE,       /* implicit: pulse signal returned */
  CM_CANCEL_USER,        /* explicit button press */
  CM_CANCEL_SUSPEND      /* suspension started mid-alert */
} cm_cancel_reason;

typedef struct {
  uint8_t  type;     /* cm_action_type */
  uint8_t  detector; /* cm_detector, when applicable */
  uint8_t  reason;   /* cm_cancel_reason, for CM_ACT_ALERT_CANCELLED */
  uint16_t episode;  /* ladder episode id (CHECKIN/COUNTDOWN/ALARM/
                        CANCELLED); 0 = none/informational */
  uint16_t seconds;  /* stage duration / time-until, when applicable */
} cm_action;

typedef enum {
  CM_STAGE_NONE = 0,
  CM_STAGE_CHECKIN,
  CM_STAGE_COUNTDOWN,
  CM_STAGE_ALARM     /* latched until cm_user_ok() */
} cm_stage;

typedef struct {
  uint8_t enabled[CM_DET_COUNT]; /* per-detector on/off (SOS always allowed) */
  uint8_t hr_available;          /* emery/diorite: 1, flint/gabbro: 0 */

  /* Motion: per-sample magnitude jerk above this counts as movement. */
  uint16_t motion_jerk_mg;        /* default 60 */

  /* Pulse-loss gates */
  uint16_t pulse_lost_after_s;    /* signal absent this long -> start hunt
                                     (default 150; must exceed 2x normal HR sample period) */
  uint16_t pulse_flat_after_s;    /* value unchanged this long -> stale even if
                                     readings continue (default 300). S4 field
                                     finding: off-body the firmware serves the
                                     LAST bpm with fresh events, bit-identical
                                     for many minutes; a live wearer always
                                     jitters. Liveness = the value CHANGES. */
  uint16_t pulse_hunt_s;          /* burst-sampling hunt duration (default 45;
                                     S4 measured ~23 s burst spin-up lag) */
  uint16_t pulse_still_s;         /* must also be still this long (default 20) */
  uint8_t  pulse_min_bpm;         /* readings below this count as no signal (default 25) */
  uint16_t pulse_worn_grace_min;  /* pulse seen within this = "was worn" (default 10) */
  uint16_t pulse_snooze_min;      /* after user-cancel, suppress re-trigger (default 10) */

  /* Impact gates (OSD-derived defaults; tune in M0/field trials) */
  uint16_t freefall_below_mg;     /* magnitude under this = freefall (default 300) */
  uint16_t impact_above_mg;       /* magnitude over this after freefall (default 2400) */
  uint16_t freefall_window_ms;    /* freefall->impact max gap (default 1500) */
  uint16_t crash_above_mg;        /* single-shock threshold, no freefall needed (default 3800) */
  uint16_t impact_settle_s;       /* post-impact bounce ignored (default 5) */
  uint16_t impact_immobile_s;     /* stillness required before alerting (default 60) */

  /* Non-motion */
  uint16_t nonmotion_day_min;     /* default 40 */
  uint16_t nonmotion_night_min;   /* default 90 */
  uint8_t  night_start_hour;      /* default 23 */
  uint8_t  night_end_hour;        /* default 7 */

  /* Not-worn (HR hardware only) */
  uint16_t notworn_after_min;     /* default 15 */

  /* Sensor fault (HR hardware only): bpm unchanged this long while
   * motion stays recent = sensor dead or carried off-wrist (nag) */
  uint16_t sensor_fault_after_min; /* default 10 */

  /* Scheduled check-in */
  uint16_t checkin_interval_min;  /* default 240 (4 h) */
  uint16_t checkin_remind_min;    /* reminder this long before due (default 5) */
  uint16_t checkin_grace_min;     /* past due before ladder starts (default 15) */

  /* Ladder timing */
  uint16_t checkin_ui_s;          /* CHECKIN stage length (default 30) */
  uint16_t countdown_s;           /* COUNTDOWN stage length (default 30) */
  uint16_t countdown_impact_s;    /* faster fuse for impacts (default 20) */
  uint16_t countdown_sos_s;       /* mis-press protection for manual SOS (default 5) */

  /* Suspension auto-resume: sustained motion AND (on HR hardware) a
   * live pulse — a bpm CHANGE during this suspension. Motion alone is
   * not wear-proof: a suspended watch carried in a bag moves, and
   * being carried off-wrist is a valid suspend reason (2026-08-29). */
  uint16_t resume_motion_s;       /* consecutive seconds of motion (default 15) */
  uint16_t resume_grace_s;        /* signals ignored this long after the
                                     suspension starts (default 60) */
  uint16_t resume_pulse_fresh_s;  /* bpm change no older than this when the
                                     motion run completes (default 150) */

  /* Wear discrimination */
  uint16_t pulse_proof_min;       /* valid pulse within this = proof of life:
                                     suppresses non-motion pings (default 5) */
  uint16_t removal_window_s;      /* motion within this after the last valid
                                     pulse classifies signal loss as removal,
                                     not arrest (default 45) */
} cm_config;

typedef struct {
  cm_config cfg;

  /* action queue (ring buffer) */
  cm_action q[8];
  uint8_t q_head, q_len;

  /* Keep 32-bit runtime fields together. cm_core is never persisted or
   * sent over the wire, so ordering by alignment removes RAM padding
   * without changing any durable format. */

  /* time */
  uint32_t now_ms;

  /* motion tracking */
  uint32_t last_motion_ms;

  /* pulse tracking */
  uint32_t last_pulse_ms;         /* last valid READING (worn evidence) */
  uint32_t last_bpm_change_ms;    /* last value CHANGE (liveness evidence) */
  uint32_t hunt_start_ms;
  uint32_t pulse_snooze_until_ms;
  uint32_t notworn_hunt_next_ms;  /* no new not-worn hunt before this: a
                                     confirming hunt buys quiet time */
  uint32_t motion_win_start_ms;   /* sustained-motion window start */
  uint32_t vibe_guard_until_ms;   /* own vibration: jerks ignored until then */
  uint32_t last_sustained_ms;     /* last SUSTAINED motion: jerk in several
                                     distinct seconds, not a single bump */

  /* impact tracking */
  uint32_t freefall_ms;
  uint32_t impact_ms;

  /* alert ladder */
  uint32_t stage_start_ms;

  /* scheduled check-in */
  uint32_t checkin_due_ms;

  /* suspension */
  uint32_t suspend_start_ms;
  uint32_t suspend_until_ms;

  /* 16-bit runtime fields. */
  uint16_t prev_mag;
  uint16_t last_bpm_value;
  uint16_t episode_seq;          /* last minted episode id (shell seeds from
                                    persist so ids survive restarts) */
  uint16_t episode;              /* current/most recent episode id */
  uint16_t suspend_motion_run_s;

  /* Byte-sized runtime state. */
  uint8_t  q_overflow;
  uint8_t  hour;
  uint8_t  have_prev_mag;
  uint8_t  motion_this_second;
  uint8_t  ever_pulse;
  uint8_t  pulse_phase;          /* 0 idle, 1 hunting */
  uint8_t  hunt_purpose;         /* 0 pulse ladder, 1 not-worn arbiter */
  uint8_t  motion_win_secs;      /* motion-seconds seen in the current window */
  uint8_t  pulse_snoozed;
  uint8_t  impact_phase;         /* 0 none, 1 freefall seen, 2 awaiting immobility */
  uint8_t  stage;                /* cm_stage */
  uint8_t  stage_det;            /* cm_detector */
  uint8_t  checkin_reminded;
  uint8_t  notworn_nagged;
  uint8_t  sensor_nagged;        /* re-armed by bpm change, never by motion */
  uint8_t  nonmotion_armed;
  uint8_t  suspended;
  uint8_t  suspend_auto_resume;
  uint8_t  charging;
  uint8_t  lab_hold;
} cm_core;

void cm_config_defaults(cm_config *cfg);
void cm_init(cm_core *c, const cm_config *cfg, uint32_t now_ms);

/* Sensor + time inputs */
void cm_accel_feed(cm_core *c, const cm_accel_sample *s, uint32_t n, uint32_t now_ms);
void cm_hr_feed(cm_core *c, uint16_t bpm, uint32_t now_ms);
void cm_tick(cm_core *c, uint32_t now_ms, uint8_t local_hour);

/* User inputs */
void cm_user_ok(cm_core *c, uint32_t now_ms);      /* "I'm OK" / cancel / check-in button */
void cm_manual_sos(cm_core *c, uint32_t now_ms);
void cm_suspend(cm_core *c, uint32_t seconds, uint8_t auto_resume, uint32_t now_ms);
void cm_resume(cm_core *c, uint32_t now_ms);       /* manual early resume */
void cm_set_charging(cm_core *c, int charging, uint32_t now_ms);
                                    /* charger state = implicit suspension */
void cm_set_lab_hold(cm_core *c, int hold, uint32_t now_ms);
                                    /* S4 sensor lab: silent detector hold */

/* Output: returns 1 while actions are pending */
int cm_next_action(cm_core *c, cm_action *out);

/* UI helpers */
static inline cm_stage cm_current_stage(const cm_core *c) {
  return (cm_stage)c->stage;
}

//! Seconds left in the current CHECKIN/COUNTDOWN stage (0 otherwise).
static inline uint32_t cm_stage_remaining_s(const cm_core *c, uint32_t now_ms) {
  uint32_t len_s;
  if (c->stage == CM_STAGE_CHECKIN) {
    len_s = c->cfg.checkin_ui_s;
  } else if (c->stage == CM_STAGE_COUNTDOWN) {
    len_s = c->stage_det == CM_DET_IMPACT ? c->cfg.countdown_impact_s
          : c->stage_det == CM_DET_SOS ? c->cfg.countdown_sos_s
                                       : c->cfg.countdown_s;
  } else {
    return 0;
  }
  uint32_t el = (now_ms - c->stage_start_ms) / 1000u;
  return el >= len_s ? 0 : len_s - el;
}

//! Re-sync the suspension deadline (shell wall-clock policy) - no-op
//! unless suspended.
static inline void cm_suspend_sync_remaining(cm_core *c, uint32_t remaining_s,
                                             uint32_t now_ms) {
  if (!c->suspended) return;
  c->now_ms = now_ms;
  c->suspend_until_ms = now_ms + remaining_s * 1000u;
}
static inline uint32_t cm_suspend_remaining_s(const cm_core *c, uint32_t now_ms) {
  if (!c->suspended) return 0;
  int32_t d = (int32_t)(c->suspend_until_ms - now_ms);
  return d > 0 ? (uint32_t)d / 1000u : 0;
}
uint32_t cm_checkin_due_in_s(const cm_core *c, uint32_t now_ms);

#ifdef __cplusplus
}
#endif
#endif /* CM_DETECTORS_H */
