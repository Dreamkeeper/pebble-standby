/* Standby (formerly Pebble Cryonics Monitor) — detector core implementation. See detectors.h. */
#include "detectors.h"

#if defined(_MSC_VER)
#define CM_NOINLINE __declspec(noinline)
#elif defined(__GNUC__)
#define CM_NOINLINE __attribute__((noinline))
#else
#define CM_NOINLINE
#endif

/* Wrap-safe elapsed time (valid for spans < 2^31 ms ~ 24 days). */
static uint32_t elapsed(uint32_t now, uint32_t since) { return now - since; }

static void emit(cm_core *c, uint8_t type, uint8_t det, uint8_t reason, uint16_t seconds) {
  if (c->q_len >= (uint8_t)(sizeof(c->q) / sizeof(c->q[0]))) {
    c->q_overflow = 1; /* shell surfaces this as a FAULT */
    return;
  }
  uint8_t idx = (uint8_t)((c->q_head + c->q_len) % (sizeof(c->q) / sizeof(c->q[0])));
  c->q[idx].type = type;
  c->q[idx].detector = det;
  c->q[idx].reason = reason;
  c->q[idx].seconds = seconds;
  /* Ladder actions carry the episode identity; everything else is
   * informational and carries 0 (delivery hardening, 2026-08-29). */
  c->q[idx].episode =
      (type == CM_ACT_CHECKIN_START || type == CM_ACT_COUNTDOWN_START ||
       type == CM_ACT_ALARM || type == CM_ACT_ALERT_CANCELLED)
          ? c->episode : 0;
  c->q_len++;
}

int cm_next_action(cm_core *c, cm_action *out) {
  if (c->q_len == 0) return 0;
  *out = c->q[c->q_head];
  c->q_head = (uint8_t)((c->q_head + 1) % (sizeof(c->q) / sizeof(c->q[0])));
  c->q_len--;
  return 1;
}

void cm_config_defaults(cm_config *cfg) {
  for (int i = 0; i < CM_DET_COUNT; i++) cfg->enabled[i] = 1;
  /* Scheduled check-in is opt-in: it is the only detector that demands
   * periodic attention from the wearer even when nothing is wrong. */
  cfg->enabled[CM_DET_CHECKIN] = 0;
  cfg->hr_available = 1;
  cfg->motion_jerk_mg = 60;

  cfg->pulse_lost_after_s = 150; /* must exceed 2x the normal HR sample period */
  cfg->pulse_flat_after_s = 300; /* frozen value = stale (S4 lab finding) */
  cfg->pulse_hunt_s = 45;        /* burst spin-up measured at ~23 s (S4) */
  cfg->pulse_still_s = 20;
  cfg->pulse_min_bpm = 25;
  cfg->pulse_worn_grace_min = 10;
  cfg->pulse_snooze_min = 10;

  cfg->freefall_below_mg = 300;
  cfg->impact_above_mg = 2400;
  cfg->freefall_window_ms = 1500;
  cfg->crash_above_mg = 3800;
  cfg->impact_settle_s = 5;
  cfg->impact_immobile_s = 60;

  cfg->nonmotion_day_min = 40;
  cfg->nonmotion_night_min = 90;
  cfg->night_start_hour = 23;
  cfg->night_end_hour = 7;

  cfg->notworn_after_min = 3;

  cfg->sensor_fault_after_min = 10;

  cfg->checkin_interval_min = 240;
  cfg->checkin_remind_min = 5;
  cfg->checkin_grace_min = 15;

  cfg->checkin_ui_s = 30;
  cfg->countdown_s = 30;
  cfg->countdown_impact_s = 20;
  cfg->countdown_sos_s = 5;

  cfg->resume_motion_s = 15;
  cfg->resume_grace_s = 60;
  cfg->resume_pulse_fresh_s = 150;

  cfg->pulse_proof_min = 5;
  cfg->removal_window_s = 45;
}

void cm_init(cm_core *c, const cm_config *cfg, uint32_t now_ms) {
  /* Zero everything, then set config + time baselines. */
  uint8_t *p = (uint8_t *)c;
  for (uint32_t i = 0; i < sizeof(*c); i++) p[i] = 0;
  c->cfg = *cfg;
  c->now_ms = now_ms;
  c->last_motion_ms = now_ms;
  c->last_sustained_ms = now_ms;
  c->last_pulse_ms = now_ms;
  c->last_bpm_change_ms = now_ms;
  c->checkin_due_ms = now_ms + (uint32_t)cfg->checkin_interval_min * 60000u;
  c->nonmotion_armed = 1;
}

/* Sustained motion = jerk in CM_SUSTAIN_SECS distinct seconds within a
 * CM_SUSTAIN_WINDOW_MS window. A single jerk is a bump — a desk being
 * used, a bed partner turning, a vehicle — and must neither dismiss a
 * check-in nor stand a pulse hunt down (field 2026-09-09: a desk bump
 * cancelled a pulse-loss CHECKIN within a second). Compile-time for the
 * same reason as the cooldown below. */
#define CM_SUSTAIN_SECS 3
/* Our own vibration motor: samples taken while it runs are flagged by the
 * OS, but a watch on a hard surface keeps ringing after the motor stops
 * and those samples are not. Field 2026-09-09: the check-in's own buzzes
 * (every 5 s) registered as three motion-seconds within 10 s and
 * cancelled the check-in; the day before, the first buzz's aftershock
 * cancelled one within a second. Jerks this long after a flagged sample
 * are not motion for any detector. */
#define CM_VIBE_GUARD_MS 1500u
#define CM_SUSTAIN_WINDOW_MS 10000u

/* Quiet time after a not-worn hunt confirmed a live wrist (see
 * tick_notworn). Compile-time on purpose: cm_config is persisted and
 * pushed by the phone with a strict size check. */
#define CM_NOTWORN_HUNT_COOLDOWN_MS (10u * 60000u)

/* ---- integer sqrt (for accel magnitude) ---- */
static uint16_t isqrt32(uint32_t v) {
  uint32_t r = 0, bit = 1uL << 30;
  while (bit > v) bit >>= 2;
  while (bit) {
    if (v >= r + bit) { v -= r + bit; r = (r >> 1) + bit; }
    else r >>= 1;
    bit >>= 2;
  }
  return (uint16_t)r;
}

static uint32_t mag2_of(const cm_accel_sample *s) {
  int32_t x = s->x, y = s->y, z = s->z;
  return (uint32_t)(x * x + y * y + z * z);
}

static int pulse_alert_active(const cm_core *c) {
  return c->stage != CM_STAGE_NONE && c->stage_det == CM_DET_PULSE;
}

static void end_pulse_machinery(cm_core *c) {
  if (c->pulse_phase == 1 || pulse_alert_active(c)) emit(c, CM_ACT_HR_BURST_OFF, CM_DET_PULSE, 0, 0);
  c->pulse_phase = 0;
  c->hunt_purpose = 0;
}

static CM_NOINLINE void schedule_next_checkin(cm_core *c) {
  c->checkin_due_ms =
      c->now_ms + (uint32_t)c->cfg.checkin_interval_min * 60000u;
  c->checkin_reminded = 0;
}

static CM_NOINLINE void snooze_pulse(cm_core *c) {
  c->pulse_snooze_until_ms =
      c->now_ms + (uint32_t)c->cfg.pulse_snooze_min * 60000u;
  c->pulse_snoozed = 1;
}

/* Cancel any active alert (does not touch alarm latch unless from user/suspend). */
static void cancel_alert(cm_core *c, uint8_t reason) {
  if (c->stage == CM_STAGE_NONE) return;
  uint8_t det = c->stage_det;
  if (det == CM_DET_PULSE) end_pulse_machinery(c);
  c->stage = CM_STAGE_NONE;
  emit(c, CM_ACT_ALERT_CANCELLED, det, reason, 0);
  if (det == CM_DET_NONMOTION) c->nonmotion_armed = 0; /* re-arm on next motion */
  if (det == CM_DET_PULSE) {
    snooze_pulse(c);
  }
  if (det == CM_DET_CHECKIN) {
    /* answered/cancelled: schedule next round */
    schedule_next_checkin(c);
  }
}

/* A new episode begins whenever the ladder leaves NONE; a promotion
 * (CHECKIN -> COUNTDOWN -> ALARM) keeps its episode. Ids are a
 * shell-persisted sequence so they stay unique across restarts; 0 is
 * reserved for none/legacy. */
static void mint_episode(cm_core *c) {
  if (c->stage != CM_STAGE_NONE) return;
  if (++c->episode_seq == 0) c->episode_seq = 1;
  c->episode = c->episode_seq;
}

static void start_checkin_stage(cm_core *c, uint8_t det) {
  mint_episode(c);
  c->stage = CM_STAGE_CHECKIN;
  c->stage_det = det;
  c->stage_start_ms = c->now_ms;
  emit(c, CM_ACT_CHECKIN_START, det, 0, c->cfg.checkin_ui_s);
}

static uint16_t countdown_len(const cm_core *c, uint8_t det) {
  if (det == CM_DET_IMPACT) return c->cfg.countdown_impact_s;
  if (det == CM_DET_SOS) return c->cfg.countdown_sos_s;
  return c->cfg.countdown_s;
}

static void start_countdown_stage(cm_core *c, uint8_t det) {
  mint_episode(c);
  c->stage = CM_STAGE_COUNTDOWN;
  c->stage_det = det;
  c->stage_start_ms = c->now_ms;
  emit(c, CM_ACT_COUNTDOWN_START, det, 0, countdown_len(c, det));
}

/* ---- motion ---- */
static void note_motion(cm_core *c) {
  c->last_motion_ms = c->now_ms;
  c->nonmotion_armed = 1;
  c->notworn_nagged = 0;
  if (c->motion_this_second) return; /* one motion-second per second */
  c->motion_this_second = 1;

  /* Sustained motion only: a single jerk is a bump, not a wearer. */
  if (elapsed(c->now_ms, c->motion_win_start_ms) > CM_SUSTAIN_WINDOW_MS) {
    c->motion_win_start_ms = c->now_ms;
    c->motion_win_secs = 0;
  }
  if (c->motion_win_secs < 255) c->motion_win_secs++;
  if (c->motion_win_secs < CM_SUSTAIN_SECS) return;
  c->last_sustained_ms = c->now_ms;

  /* Sustained motion auto-dismisses the CHECKIN stage — except scheduled
   * check-ins, which require a deliberate button press, and except SOS. */
  if (c->stage == CM_STAGE_CHECKIN &&
      c->stage_det != CM_DET_CHECKIN && c->stage_det != CM_DET_SOS) {
    cancel_alert(c, CM_CANCEL_MOTION);
  }
  /* Sustained motion during a pulse hunt: not still — stand down silently. */
  if (c->pulse_phase == 1) end_pulse_machinery(c);
  /* Motion in the post-impact settle window is handled in cm_tick via
   * last_motion_ms; nothing to do here. */
}

/* Shared by every transition that resumes detector timing. Outlining these
 * four stores is smaller than repeating them at five call sites. */
static CM_NOINLINE void reset_baselines(cm_core *c, uint32_t now_ms) {
  c->last_motion_ms = now_ms;
  c->last_sustained_ms = now_ms;
  c->motion_win_secs = 0;
  c->last_pulse_ms = now_ms;
  c->last_bpm_change_ms = now_ms;
  c->impact_phase = 0;
}

static CM_NOINLINE void begin_detector_hold(cm_core *c) {
  if (c->stage != CM_STAGE_NONE && c->stage != CM_STAGE_ALARM) {
    cancel_alert(c, CM_CANCEL_SUSPEND);
  }
  c->impact_phase = 0;
  c->pulse_phase = 0;
  c->hunt_purpose = 0;
}

void cm_accel_feed(cm_core *c, const cm_accel_sample *s, uint32_t n, uint32_t now_ms) {
  c->now_ms = now_ms;
  for (uint32_t i = 0; i < n; i++) {
    if (s[i].did_vibrate) {
      c->have_prev_mag = 0;
      c->vibe_guard_until_ms = now_ms + CM_VIBE_GUARD_MS;
      continue;
    }
    int ringing = (int32_t)(c->vibe_guard_until_ms - now_ms) > 0;
    uint32_t m2 = mag2_of(&s[i]);
    uint16_t mag = isqrt32(m2);

    /* movement = magnitude jerk between consecutive samples */
    if (c->have_prev_mag && !ringing) {
      uint16_t d = (mag > c->prev_mag) ? (uint16_t)(mag - c->prev_mag)
                                       : (uint16_t)(c->prev_mag - mag);
      if (d >= c->cfg.motion_jerk_mg) note_motion(c);
    }
    c->prev_mag = mag;
    c->have_prev_mag = 1;

    if (ringing) continue; /* nor is the ringing a fall or a shock */
    if (c->suspended || !c->cfg.enabled[CM_DET_IMPACT]) continue;
    if (c->stage != CM_STAGE_NONE) continue;

    /* impact detection */
    if (c->impact_phase < 2) {
      if (mag < c->cfg.freefall_below_mg) {
        c->impact_phase = 1;
        c->freefall_ms = now_ms;
      } else if (c->impact_phase == 1 && mag > c->cfg.impact_above_mg &&
                 elapsed(now_ms, c->freefall_ms) <= c->cfg.freefall_window_ms) {
        c->impact_phase = 2;               /* freefall -> impact */
        c->impact_ms = now_ms;
      } else if (mag > c->cfg.crash_above_mg) {
        c->impact_phase = 2;               /* single high-G shock */
        c->impact_ms = now_ms;
      } else if (c->impact_phase == 1 &&
                 elapsed(now_ms, c->freefall_ms) > c->cfg.freefall_window_ms) {
        c->impact_phase = 0;               /* freefall window expired */
      }
    }
  }
}

void cm_hr_feed(cm_core *c, uint16_t bpm, uint32_t now_ms) {
  c->now_ms = now_ms;
  if (!c->cfg.hr_available) return;
  if (bpm < c->cfg.pulse_min_bpm) return;
  /* A valid READING is evidence the watch is worn-ish (grace window). */
  c->last_pulse_ms = now_ms;
  c->ever_pulse = 1;
  /* S4 field finding (2026-08-27, Time 2): off-body the firmware keeps
   * serving the LAST computed bpm with fresh events — bit-identical for
   * many minutes across flat/face-down/fabric. A living wearer's raw
   * bpm always jitters. So LIVENESS is a CHANGING value; only a change
   * clears snoozes and nags, ends hunts, or dismisses check-ins. The
   * 1 Hz hunt is the arbiter: alive jitters within seconds, a frozen
   * feed stays flat and the ladder proceeds. */
  if (bpm != c->last_bpm_value) {
    c->last_bpm_value = bpm;
    c->last_bpm_change_ms = now_ms;
    c->pulse_snoozed = 0;
    c->notworn_nagged = 0;
    c->sensor_nagged = 0;
    if (c->pulse_phase == 1 && c->hunt_purpose == 1) {
      /* The 1 Hz arbiter saw a live wrist: buy quiet time before the
       * next not-worn hunt so a steady sleeper is not re-hunted every
       * few minutes (field 2026-09-09). */
      c->notworn_hunt_next_ms = now_ms + CM_NOTWORN_HUNT_COOLDOWN_MS;
    }
    if (c->pulse_phase == 1) end_pulse_machinery(c);
    if (c->stage == CM_STAGE_CHECKIN && c->stage_det == CM_DET_PULSE) {
      cancel_alert(c, CM_CANCEL_PULSE);
    }
  }
}

static int is_night(const cm_core *c) {
  uint8_t s = c->cfg.night_start_hour, e = c->cfg.night_end_hour;
  if (s == e) return 0;
  if (s < e) return c->hour >= s && c->hour < e;
  return c->hour >= s || c->hour < e; /* window crosses midnight */
}

static int worn_recently(const cm_core *c) {
  if (c->cfg.hr_available) {
    return c->ever_pulse &&
           elapsed(c->now_ms, c->last_pulse_ms) <=
               (uint32_t)c->cfg.pulse_worn_grace_min * 60000u;
  }
  return 1; /* no wear sensor: assume worn (documented limitation) */
}

static void tick_suspension(cm_core *c) {
  if (!c->suspended) return;

  if ((int32_t)(c->suspend_until_ms - c->now_ms) <= 0) {
    c->suspended = 0;
    /* fresh baselines: no instant triggers on resume */
    reset_baselines(c, c->now_ms);
    c->sensor_nagged = 0; /* post-suspension blindness is a new episode */
    emit(c, CM_ACT_SUSPEND_EXPIRED, 0, 0, 0);
    return;
  }

  if (c->suspend_auto_resume) {
    /* Arming delay: the wearer is usually still wearing (or handling) the
     * watch in the first moments of a suspension — those signals must not
     * resume it. */
    if (elapsed(c->now_ms, c->suspend_start_ms) <
        (uint32_t)c->cfg.resume_grace_s * 1000u) {
      c->suspend_motion_run_s = 0;
      return;
    }
    if (c->motion_this_second) {
      if (c->suspend_motion_run_s < 60000) c->suspend_motion_run_s++;
    } else c->suspend_motion_run_s = 0;

    if (c->suspend_motion_run_s >= c->cfg.resume_motion_s) {
      /* Motion alone is not wrist evidence on HR hardware: a suspended
       * watch carried in a bag moves for minutes, and being carried
       * off-wrist is a valid suspend reason (2026-08-29). Require a
       * LIVE pulse too — a bpm CHANGE during this suspension (past the
       * grace) and still fresh. Liveness is a changing value (S4): the
       * off-wrist-fixed firmware reads 0 off-body and a frozen reading
       * never changes, so a bag ride cannot fake this half. Readings
       * without motion still never resume (the old phantom-press rule).
       * If the sensor is dead, the suspend timer stays the backstop. */
      int pulse_ok = 1;
      if (c->cfg.hr_available) {
        uint32_t armed_ms =
            c->suspend_start_ms + (uint32_t)c->cfg.resume_grace_s * 1000u;
        pulse_ok = (int32_t)(c->last_bpm_change_ms - armed_ms) >= 0 &&
                   elapsed(c->now_ms, c->last_bpm_change_ms) <=
                       (uint32_t)c->cfg.resume_pulse_fresh_s * 1000u;
      }
      if (pulse_ok) {
        c->suspended = 0;
        reset_baselines(c, c->now_ms);
        emit(c, CM_ACT_AUTO_RESUMED, 0, 0, 0);
      }
    }
  }
}

static void tick_ladder(cm_core *c) {
  if (c->stage == CM_STAGE_CHECKIN &&
      elapsed(c->now_ms, c->stage_start_ms) >= (uint32_t)c->cfg.checkin_ui_s * 1000u) {
    start_countdown_stage(c, c->stage_det);
  } else if (c->stage == CM_STAGE_COUNTDOWN &&
             elapsed(c->now_ms, c->stage_start_ms) >=
                 (uint32_t)countdown_len(c, c->stage_det) * 1000u) {
    c->stage = CM_STAGE_ALARM;
    c->stage_start_ms = c->now_ms;
    if (c->stage_det == CM_DET_PULSE) emit(c, CM_ACT_HR_BURST_OFF, CM_DET_PULSE, 0, 0);
    emit(c, CM_ACT_ALARM, c->stage_det, 0, 0);
  }
}

/* Removal vs. arrest: a dead wearer does not move after the pulse stops;
 * removing a watch necessarily moves it. The reference moment is the
 * last VALUE CHANGE (readings may continue frozen after removal — S4):
 * handling motion near that moment, then stillness, is the removal
 * signature — such an episode belongs to the not-worn nag, not the
 * alarm ladder. Evaluated only at hunt-trigger time. */
static int removal_suspected(const cm_core *c) {
  uint32_t w = (uint32_t)c->cfg.removal_window_s * 1000u;
  /* Handling a watch is sustained motion; a bump at the loss moment is
   * not a removal signature (it used to route a collapse to the nag). */
  int32_t d = (int32_t)(c->last_sustained_ms - c->last_bpm_change_ms);
  return d >= 0 ? (uint32_t)d <= w : (uint32_t)(-d) <= w;
}

static void tick_pulse(cm_core *c) {
  if (!c->cfg.enabled[CM_DET_PULSE] || !c->cfg.hr_available || !c->ever_pulse) return;
  if (c->stage != CM_STAGE_NONE || c->suspended) return;
  if (c->pulse_snoozed && (int32_t)(c->pulse_snooze_until_ms - c->now_ms) > 0) return;

  uint32_t since_pulse = elapsed(c->now_ms, c->last_pulse_ms);
  uint32_t since_change = elapsed(c->now_ms, c->last_bpm_change_ms);
  /* Stillness for the ladder = no SUSTAINED motion (bumps do not count). */
  uint32_t since_motion = elapsed(c->now_ms, c->last_sustained_ms);

  if (c->pulse_phase == 0) {
    /* Two loss signatures: readings STOP (lost), or readings continue
     * but the value froze (flat — off-body/arrest per S4). */
    int lost = since_pulse >= (uint32_t)c->cfg.pulse_lost_after_s * 1000u;
    int flat = since_change >= (uint32_t)c->cfg.pulse_flat_after_s * 1000u;
    if (worn_recently(c) && (lost || flat) &&
        since_motion >= (uint32_t)c->cfg.pulse_still_s * 1000u) {
      if (removal_suspected(c)) return; /* not-worn nag owns this episode */
      c->pulse_phase = 1;
      c->hunt_start_ms = c->now_ms;
      emit(c, CM_ACT_HR_BURST_ON, CM_DET_PULSE, 0, c->cfg.pulse_hunt_s);
    }
  } else if (c->pulse_phase == 1 && c->hunt_purpose == 0) {
    if (elapsed(c->now_ms, c->hunt_start_ms) >= (uint32_t)c->cfg.pulse_hunt_s * 1000u) {
      /* hunted, still nothing: escalate (burst stays on so a returning
       * pulse can still auto-dismiss the CHECKIN stage) */
      c->pulse_phase = 0;
      start_checkin_stage(c, CM_DET_PULSE);
    }
  }
}

static void tick_impact(cm_core *c) {
  if (c->impact_phase != 2 || c->suspended || c->stage != CM_STAGE_NONE) return;
  /* Off-wrist evidence (pulse frozen/absent beyond the flat window)
   * suppresses the impact ladder: a table tremor is not a fall (field
   * event 2026-08-27 — a bump check-inned a watch lying on the table),
   * and a fallen WEARER is covered by the pulse ladder's flat trigger.
   * Non-HR hardware keeps impact armed (assumed worn, documented). */
  if (c->cfg.hr_available && c->ever_pulse &&
      elapsed(c->now_ms, c->last_bpm_change_ms) >=
          (uint32_t)c->cfg.pulse_flat_after_s * 1000u) {
    c->impact_phase = 0;
    return;
  }

  uint32_t settle_end = c->impact_ms + (uint32_t)c->cfg.impact_settle_s * 1000u;
  if ((int32_t)(c->now_ms - settle_end) < 0) return; /* still settling */

  if ((int32_t)(c->last_motion_ms - settle_end) >= 0) {
    c->impact_phase = 0; /* deliberate motion after settle: silent cancel */
    return;
  }
  if (elapsed(c->now_ms, settle_end) >= (uint32_t)c->cfg.impact_immobile_s * 1000u) {
    c->impact_phase = 0;
    /* Not one valid reading since the shock (HR hardware, previously
     * worn): the watch is off the wrist — a set-down on a desk registers
     * as a shock (field 2026-09-09) — or the sensor lost contact, and
     * either way the pulse ladder / not-worn nag own what follows. A
     * fallen WEARER keeps producing readings through the immobility
     * window (the idle cadence is 60 s). */
    if (c->cfg.hr_available && c->ever_pulse &&
        (int32_t)(c->last_pulse_ms - c->impact_ms) < 0) return;
    start_checkin_stage(c, CM_DET_IMPACT);
  }
}

static void tick_nonmotion(cm_core *c) {
  if (!c->cfg.enabled[CM_DET_NONMOTION] || c->suspended) return;
  if (c->stage != CM_STAGE_NONE || !c->nonmotion_armed) return;
  if (!worn_recently(c)) return; /* off-wrist is the not-worn detector's job */
  /* A live pulse is proof of life: stillness alone (sleep, meditation,
   * TV) must never ping the wearer on HR hardware. "Live" means the
   * value still CHANGES (a frozen reading proves nothing — S4).
   * Non-motion remains only as the backstop for a silently failing
   * sensor inside the worn-grace band. */
  if (c->cfg.hr_available && c->ever_pulse &&
      elapsed(c->now_ms, c->last_bpm_change_ms) <
          (uint32_t)c->cfg.pulse_proof_min * 60000u) return;

  uint16_t mins = is_night(c) ? c->cfg.nonmotion_night_min : c->cfg.nonmotion_day_min;
  if (elapsed(c->now_ms, c->last_motion_ms) >= (uint32_t)mins * 60000u) {
    c->nonmotion_armed = 0;
    start_checkin_stage(c, CM_DET_NONMOTION);
  }
}

static void tick_checkin(cm_core *c) {
  if (!c->cfg.enabled[CM_DET_CHECKIN] || c->suspended) return;
  if (c->stage != CM_STAGE_NONE) return;

  uint32_t remind_at =
      c->checkin_due_ms - (uint32_t)c->cfg.checkin_remind_min * 60000u;
  if (!c->checkin_reminded && (int32_t)(c->now_ms - remind_at) >= 0) {
    c->checkin_reminded = 1;
    uint32_t until_due = (int32_t)(c->checkin_due_ms - c->now_ms) > 0
                             ? (c->checkin_due_ms - c->now_ms) / 1000u : 0;
    emit(c, CM_ACT_CHECKIN_REMINDER, CM_DET_CHECKIN, 0, (uint16_t)until_due);
  }
  uint32_t deadline = c->checkin_due_ms + (uint32_t)c->cfg.checkin_grace_min * 60000u;
  if ((int32_t)(c->now_ms - deadline) >= 0) {
    start_checkin_stage(c, CM_DET_CHECKIN);
  }
}

static void tick_notworn(cm_core *c) {
  /* Deliberately NOT gated on ever_pulse: a worker that restarts while
   * the watch lies off-wrist (build update, reboot on the nightstand)
   * may never see a single reading — that watch is exactly the one
   * whose wearer must be told monitoring is blind. One nag per
   * episode; motion or a live pulse re-arms it. */
  if (!c->cfg.enabled[CM_DET_NOTWORN] || !c->cfg.hr_available) return;
  if (c->suspended || c->notworn_nagged || c->stage != CM_STAGE_NONE) return;
  if (c->pulse_phase == 1) {
    if (c->hunt_purpose != 1) return; /* ladder hunt: let it conclude */
    if (elapsed(c->now_ms, c->hunt_start_ms) >= (uint32_t)c->cfg.pulse_hunt_s * 1000u) {
      /* Hunted at 1 Hz and the feed stayed flat or absent: off-wrist. */
      end_pulse_machinery(c);
      c->notworn_nagged = 1;
      emit(c, CM_ACT_NOTWORN_NAG, CM_DET_NOTWORN, 0, 0);
    }
    return;
  }

  /* Off-wrist evidence = no LIVE pulse (frozen readings do not count —
   * S4) and no motion for the threshold. At the 60 s idle cadence that
   * evidence is ambiguous: a sleeping wearer's steady bpm repeated three
   * times too (field 2026-09-09: 67/67/67, 16 min still, nag at 02:05).
   * So arbitrate with the same 1 Hz hunt the ladder uses — a live wrist
   * changes within seconds and re-arms silently; a nightstand stays
   * flat and gets its nag 45 s later than before. */
  uint32_t th = (uint32_t)c->cfg.notworn_after_min * 60000u;
  if (elapsed(c->now_ms, c->last_bpm_change_ms) >= th &&
      elapsed(c->now_ms, c->last_motion_ms) >= th) {
    if ((int32_t)(c->notworn_hunt_next_ms - c->now_ms) > 0) return; /* confirmed alive recently */
    c->pulse_phase = 1;
    c->hunt_purpose = 1;
    c->hunt_start_ms = c->now_ms;
    emit(c, CM_ACT_HR_BURST_ON, CM_DET_NOTWORN, 0, c->cfg.pulse_hunt_s);
  }
}

/* "No pulse signal but motion continues": the moving-wearer twin of the
 * not-worn nag. Either the HR sensor died while worn (field 2026-08-29:
 * the HRM failed to start on 2 of 3 consecutive boots) or the watch is
 * being carried off-wrist. Both mean pulse monitoring is blind; neither
 * is an emergency, so this nags the wearer and faults the phone — never
 * contacts. A still wearer never reaches here (the pulse hunt/ladder or
 * the not-worn nag owns that episode); motion never re-arms this (it
 * does not disprove a dead sensor) — only a bpm change does. */
static void tick_sensorfault(cm_core *c) {
  if (!c->cfg.enabled[CM_DET_SENSOR] || !c->cfg.hr_available) return;
  if (c->sensor_nagged || c->stage != CM_STAGE_NONE) return;
  if (c->pulse_phase != 0) return;

  uint32_t flat_th = (uint32_t)c->cfg.sensor_fault_after_min * 60000u;
  uint32_t motion_th = (uint32_t)c->cfg.notworn_after_min * 60000u;
  if (elapsed(c->now_ms, c->last_bpm_change_ms) >= flat_th &&
      elapsed(c->now_ms, c->last_motion_ms) < motion_th) {
    c->sensor_nagged = 1;
    emit(c, CM_ACT_SENSOR_FAULT, CM_DET_SENSOR, 0, 0);
  }
}

void cm_tick(cm_core *c, uint32_t now_ms, uint8_t local_hour) {
  c->now_ms = now_ms;
  c->hour = local_hour;

  tick_suspension(c);
  tick_ladder(c);
  if (!c->suspended && !c->charging && !c->lab_hold) {
    tick_impact(c);
    tick_pulse(c);
    tick_nonmotion(c);
    tick_checkin(c);
    tick_notworn(c);
    tick_sensorfault(c);
  }
  c->motion_this_second = 0;
}

void cm_user_ok(cm_core *c, uint32_t now_ms) {
  c->now_ms = now_ms;
  /* A button press is proof of life. */
  c->last_motion_ms = now_ms;
  c->nonmotion_armed = 1;

  if (c->stage == CM_STAGE_ALARM) {
    uint8_t det = c->stage_det;
    c->stage = CM_STAGE_NONE;
    emit(c, CM_ACT_ALERT_CANCELLED, det, CM_CANCEL_USER, 0);
    if (det == CM_DET_PULSE) {
      snooze_pulse(c);
    }
    if (det == CM_DET_CHECKIN) {
      schedule_next_checkin(c);
    }
    return;
  }
  if (c->stage != CM_STAGE_NONE) {
    cancel_alert(c, CM_CANCEL_USER);
    return;
  }
  /* Suspended (any mode, incl. timer-only carry): the press is an
   * explicit check-in — end the suspension now rather than waiting for
   * the timer or the (slow, 5-min-cadence) auto-resume. A latched ALARM
   * took precedence above: the first press cancels it, the suspension
   * continues, the next press resumes (owner request 2026-09-07). */
  if (c->suspended) {
    cm_resume(c, now_ms);
    if (c->cfg.enabled[CM_DET_CHECKIN]) schedule_next_checkin(c);
    return;
  }
  /* No alert active: treat as an early scheduled check-in. */
  if (c->cfg.enabled[CM_DET_CHECKIN]) {
    schedule_next_checkin(c);
  }
}

void cm_manual_sos(cm_core *c, uint32_t now_ms) {
  c->now_ms = now_ms;
  if (c->stage == CM_STAGE_ALARM) return;
  if (c->stage != CM_STAGE_NONE) cancel_alert(c, CM_CANCEL_USER);
  start_countdown_stage(c, CM_DET_SOS);
}

void cm_suspend(cm_core *c, uint32_t seconds, uint8_t auto_resume, uint32_t now_ms) {
  c->now_ms = now_ms;
  if (c->stage != CM_STAGE_NONE && c->stage != CM_STAGE_ALARM) {
    cancel_alert(c, CM_CANCEL_SUSPEND);
  }
  c->suspended = 1;
  c->suspend_start_ms = now_ms;
  c->suspend_until_ms = now_ms + seconds * 1000u;
  c->suspend_auto_resume = auto_resume;
  c->suspend_motion_run_s = 0;
  c->impact_phase = 0;
  c->pulse_phase = 0;
  emit(c, CM_ACT_SUSPEND_STARTED, 0, 0,
       (uint16_t)(seconds > 65535u ? 65535u : seconds));
}

void cm_set_charging(cm_core *c, int charging, uint32_t now_ms) {
  uint8_t on = charging ? 1 : 0;
  if (on == c->charging) return;
  c->now_ms = now_ms;
  c->charging = on;
  if (on) {
    /* Putting the watch on the charger is a deliberate act: treat it as
     * an implicit suspension. Pre-alarm stages cancel like a suspension
     * would; a latched ALARM stays latched — charging must never clear
     * an alarm someone may already be responding to. */
    begin_detector_hold(c);
    emit(c, CM_ACT_CHARGING_STARTED, 0, 0, 1);
  } else {
    /* fresh baselines: the stillness and pulse-absence accumulated on
     * the charger must not fire the instant it comes off */
    reset_baselines(c, now_ms);
    c->notworn_nagged = 0;
    c->sensor_nagged = 0;
    c->nonmotion_armed = 1;
    emit(c, CM_ACT_CHARGING_ENDED, 0, 0, 0);
  }
}

/* Guided sensor test (M0 S4): the wearer will deliberately loosen the
 * strap and put the watch on a table — the detectors must sit this out.
 * Same rules as the charging hold, but silent: the lab UI on the phone
 * is already narrating, and no state change should reach contacts. */
void cm_set_lab_hold(cm_core *c, int hold, uint32_t now_ms) {
  uint8_t on = hold ? 1 : 0;
  if (on == c->lab_hold) return;
  c->now_ms = now_ms;
  c->lab_hold = on;
  if (on) {
    begin_detector_hold(c);
  } else {
    reset_baselines(c, now_ms);
    c->notworn_nagged = 0;
    c->sensor_nagged = 0;
    c->nonmotion_armed = 1;
  }
}

void cm_resume(cm_core *c, uint32_t now_ms) {
  c->now_ms = now_ms;
  if (!c->suspended) return;
  c->suspended = 0;
  reset_baselines(c, now_ms);
  c->sensor_nagged = 0;
  emit(c, CM_ACT_AUTO_RESUMED, 0, 0, 0);
}

uint32_t cm_checkin_due_in_s(const cm_core *c, uint32_t now_ms) {
  int32_t d = (int32_t)(c->checkin_due_ms - now_ms);
  return d > 0 ? (uint32_t)d / 1000u : 0;
}
