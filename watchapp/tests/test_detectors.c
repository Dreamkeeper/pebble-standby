/*
 * Host unit tests for the detector core. Scenario-driven: simulated seconds
 * of accelerometer + HR data are fed in, emitted actions are asserted.
 *
 * Build (MSVC):  cl /W4 /std:c11 /I..\src\core test_detectors.c ..\src\core\detectors.c
 * Build (gcc):   gcc -Wall -Wextra -std=c11 -I../src/core -o test_runner \
 *                    test_detectors.c ../src/core/detectors.c
 */
#include <stdio.h>
#include <string.h>
#include "detectors.h"

static int g_failures = 0;
static int g_checks = 0;
static const char *g_test = "";

#define CHECK(cond) do { \
    g_checks++; \
    if (!(cond)) { \
      g_failures++; \
      printf("FAIL %s:%d [%s] %s\n", __FILE__, __LINE__, g_test, #cond); \
    } \
  } while (0)

/* ---- simulation harness ---- */

static cm_core core;
static uint32_t now_ms;
static uint8_t sim_hour = 12;

#define MAX_LOG 64
static cm_action log_actions[MAX_LOG];
static int log_count;

static void drain(void) {
  cm_action a;
  while (cm_next_action(&core, &a)) {
    if (log_count < MAX_LOG) log_actions[log_count++] = a;
  }
}

static void log_reset(void) { log_count = 0; }

static int count_type(cm_action_type t) {
  int n = 0;
  for (int i = 0; i < log_count; i++) if (log_actions[i].type == t) n++;
  return n;
}

static const cm_action *find_type(cm_action_type t) {
  for (int i = 0; i < log_count; i++)
    if (log_actions[i].type == t) return &log_actions[i];
  return 0;
}

static void feed_batch(int16_t z_mg) {
  cm_accel_sample s[25];
  for (int i = 0; i < 25; i++) {
    s[i].x = 0; s[i].y = 0; s[i].z = z_mg; s[i].did_vibrate = 0;
  }
  cm_accel_feed(&core, s, 25, now_ms);
}

/* one simulated second, lying still */
static void sec_still(void) {
  now_ms += 1000;
  feed_batch(-1000);
  cm_tick(&core, now_ms, sim_hour);
  drain();
}

/* one simulated second with deliberate movement */
static void sec_moving(void) {
  now_ms += 1000;
  feed_batch(-1000);
  feed_batch(-1300); /* jerk 300 mg > threshold */
  cm_tick(&core, now_ms, sim_hour);
  drain();
}

static void sec_still_hr(uint16_t bpm) {
  now_ms += 1000;
  feed_batch(-1000);
  cm_hr_feed(&core, bpm, now_ms);
  cm_tick(&core, now_ms, sim_hour);
  drain();
}

/* one simulated second with movement AND a pulse reading */
static void sec_moving_hr(uint16_t bpm) {
  now_ms += 1000;
  feed_batch(-1000);
  feed_batch(-1300);
  cm_hr_feed(&core, bpm, now_ms);
  cm_tick(&core, now_ms, sim_hour);
  drain();
}

/* still, worn: a fallen wearer keeps producing (jittering) readings */
static void secs_still_worn(int seconds) {
  for (int i = 0; i < seconds; i++) sec_still_hr((uint16_t)(70 + (i & 1)));
}

/* one second in which our own vibration motor ran (flagged samples) and
 * the case then rang on a hard surface (an unflagged jerk) */
static void sec_buzz_aftershock(void) {
  cm_accel_sample s[25];
  for (int i = 0; i < 25; i++) {
    s[i].x = 0; s[i].y = 0; s[i].z = -1000; s[i].did_vibrate = (i < 10) ? 1 : 0;
  }
  s[12].z = -1400; /* ringing: jerk 400 mg > threshold, motor already off */
  now_ms += 1000;
  cm_accel_feed(&core, s, 25, now_ms);
  cm_tick(&core, now_ms, sim_hour);
  drain();
}

static void mins_still(int minutes) { for (int i = 0; i < minutes * 60; i++) sec_still(); }
static void secs_still(int seconds) { for (int i = 0; i < seconds; i++) sec_still(); }

/* freefall then hard impact within one batch */
static void event_fall(void) {
  cm_accel_sample s[3];
  memset(s, 0, sizeof(s));
  s[0].z = -1000;
  s[1].z = -100;   /* mag 100 < freefall_below (300) */
  s[2].z = -3000;  /* mag 3000 > impact_above (2400) */
  now_ms += 1000;
  cm_accel_feed(&core, s, 3, now_ms);
  cm_tick(&core, now_ms, sim_hour);
  drain();
}

static cm_config test_cfg(void) {
  cm_config cfg;
  cm_config_defaults(&cfg);
  cfg.pulse_lost_after_s = 30; /* tight timings so tests read in seconds */
  cfg.pulse_hunt_s = 30;
  cfg.pulse_still_s = 20;
  cfg.pulse_snooze_min = 10;
  cfg.checkin_ui_s = 30;
  cfg.countdown_s = 30;
  cfg.countdown_impact_s = 20;
  cfg.notworn_after_min = 15;
  return cfg;
}

static void setup(const cm_config *cfg) {
  now_ms = 1000000;
  sim_hour = 12;
  log_reset();
  cm_init(&core, cfg, now_ms);
}

/* establish a "worn, alive" baseline: motion, then a still-but-alive
 * minute of JITTERING pulse — real HR is never flat (S4), and the last
 * value change must clear the removal window before scenarios begin so
 * a subsequent signal loss reads as arrest, not removal */
static void warmup(void) {
  for (int i = 0; i < 5; i++) { sec_moving(); }
  for (int i = 0; i < 60; i++) { sec_still_hr((uint16_t)(70 + (i & 1))); }
  log_reset();
}

/* ---- tests ---- */

static void test_defaults(void) {
  g_test = "defaults";
  cm_config cfg;
  cm_config_defaults(&cfg);
  CHECK(cfg.hr_available == 1);
  CHECK(cfg.pulse_lost_after_s == 150);
  CHECK(cfg.nonmotion_day_min == 40);
  CHECK(cfg.nonmotion_night_min == 90);
  CHECK(cfg.countdown_impact_s < cfg.countdown_s); /* impacts get a faster fuse */
  CHECK(cfg.notworn_after_min == 3);   /* removal nags fast, never contacts */
  CHECK(cfg.pulse_proof_min == 5);     /* live pulse = proof of life */
  CHECK(cfg.pulse_flat_after_s == 300); /* frozen value = stale (S4) */
  CHECK(cfg.pulse_hunt_s == 45);       /* burst spin-up ~23 s measured (S4) */
  CHECK(cfg.removal_window_s == 45);   /* motion-after-pulse = removal */
  CHECK(cfg.resume_grace_s == 60);     /* auto-resume arming delay */
  /* Scheduled check-in is opt-in; every passive detector is on by default. */
  CHECK(cfg.enabled[CM_DET_CHECKIN] == 0);
  for (int i = 0; i < CM_DET_COUNT; i++)
    if (i != CM_DET_CHECKIN) CHECK(cfg.enabled[i] == 1);
}

static void test_impact_full_ladder(void) {
  g_test = "impact_full_ladder";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;   /* isolate: no HR feed in this scenario */
  cfg.enabled[CM_DET_NOTWORN] = 0;
  setup(&cfg);
  warmup();

  event_fall();
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0); /* nothing yet: immobility gate */

  /* settle (5 s) + immobility window (60 s) while lying still, worn */
  secs_still_worn(66);
  const cm_action *ci = find_type(CM_ACT_CHECKIN_START);
  CHECK(ci != 0);
  CHECK(ci && ci->detector == CM_DET_IMPACT);
  log_reset();

  /* CHECKIN stage times out (30 s) -> countdown */
  secs_still(31);
  const cm_action *cd = find_type(CM_ACT_COUNTDOWN_START);
  CHECK(cd != 0);
  CHECK(cd && cd->detector == CM_DET_IMPACT);
  CHECK(cd && cd->seconds == 20); /* impact fuse */
  log_reset();

  /* motion during COUNTDOWN must NOT cancel (explicit tap only) */
  sec_moving();
  CHECK(count_type(CM_ACT_ALERT_CANCELLED) == 0);

  /* countdown expires -> ALARM */
  secs_still(20);
  const cm_action *al = find_type(CM_ACT_ALARM);
  CHECK(al != 0);
  CHECK(al && al->detector == CM_DET_IMPACT);
  CHECK(cm_current_stage(&core) == CM_STAGE_ALARM);
  log_reset();

  /* user clears the latched alarm */
  cm_user_ok(&core, now_ms);
  drain();
  const cm_action *cc = find_type(CM_ACT_ALERT_CANCELLED);
  CHECK(cc != 0);
  CHECK(cc && cc->reason == CM_CANCEL_USER);
  CHECK(cm_current_stage(&core) == CM_STAGE_NONE);
}

static void test_impact_cancelled_by_motion(void) {
  g_test = "impact_cancelled_by_motion";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;
  cfg.enabled[CM_DET_NOTWORN] = 0;
  setup(&cfg);
  warmup();

  event_fall();
  secs_still(10);   /* past settle */
  sec_moving();     /* wearer moves deliberately */
  mins_still(3);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_ALARM) == 0);
}

/* Owner decision 2026-09-30: once the impact screen is up, only a button
 * press ends it. Eight field check-ins (09-23..09-30) had cancelled
 * themselves from their own buzz; a fall victim's twitch must not either. */
static void test_impact_checkin_needs_button(void) {
  g_test = "impact_checkin_needs_button";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;
  cfg.enabled[CM_DET_NOTWORN] = 0;
  setup(&cfg);
  warmup();

  event_fall();
  secs_still_worn(66);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);
  log_reset();

  sec_moving(); sec_moving(); sec_moving(); /* sustained motion: still not enough */
  CHECK(count_type(CM_ACT_ALERT_CANCELLED) == 0);
  CHECK(cm_current_stage(&core) == CM_STAGE_CHECKIN);

  cm_user_ok(&core, now_ms);
  drain();
  const cm_action *cc = find_type(CM_ACT_ALERT_CANCELLED);
  CHECK(cc != 0);
  CHECK(cc && cc->reason == CM_CANCEL_USER);
  CHECK(cm_current_stage(&core) == CM_STAGE_NONE);
}

/* Settings sync (watch-settings-sync): accept in range, refuse out of
 * range or unknown, echo the value in force, reschedule the check-in. */
static void test_apply_config(void) {
  g_test = "apply_config";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  CHECK(cm_config_get(&core, CM_CFG_NONMOTION_DAY_MIN) == 40);
  CHECK(cm_apply_config(&core, CM_CFG_NONMOTION_DAY_MIN, 60) == 1);
  CHECK(core.cfg.nonmotion_day_min == 60);
  CHECK(cm_apply_config(&core, CM_CFG_NONMOTION_DAY_MIN, 5) == 0);   /* below range */
  CHECK(core.cfg.nonmotion_day_min == 60);                           /* unchanged */
  CHECK(cm_apply_config(&core, 200, 1) == 0);                        /* unknown id */
  CHECK(cm_config_get(&core, 200) == 0);

  /* detector switch */
  CHECK(core.cfg.enabled[CM_DET_NONMOTION] == 1);
  CHECK(cm_apply_config(&core, CM_CFG_NONMOTION_ENABLED, 0) == 1);
  CHECK(core.cfg.enabled[CM_DET_NONMOTION] == 0);
  CHECK(cm_apply_config(&core, CM_CFG_NONMOTION_ENABLED, 2) == 0);

  /* enabling the check-in schedules it <interval> from now */
  CHECK(core.cfg.enabled[CM_DET_CHECKIN] == 0);
  CHECK(cm_apply_config(&core, CM_CFG_CHECKIN_INTERVAL_MIN, 60) == 1);
  CHECK(cm_apply_config(&core, CM_CFG_CHECKIN_ENABLED, 1) == 1);
  uint32_t due = cm_checkin_due_in_s(&core, now_ms);
  CHECK(due > 3590 && due <= 3600);
  CHECK(cm_apply_config(&core, CM_CFG_CHECKIN_INTERVAL_MIN, 120) == 1);
  due = cm_checkin_due_in_s(&core, now_ms);
  CHECK(due > 7190 && due <= 7200);

  /* ladder timing */
  CHECK(cm_apply_config(&core, CM_CFG_CHECKIN_UI_S, 5) == 0);
  CHECK(cm_apply_config(&core, CM_CFG_CHECKIN_UI_S, 45) == 1);
  CHECK(cm_config_get(&core, CM_CFG_CHECKIN_UI_S) == 45);
}

/* one second holding a single high-G sample: a shock if unguarded */
static void sec_shock(void) {
  now_ms += 1000;
  cm_accel_sample s[25];
  for (int i = 0; i < 25; i++) { s[i].x = 0; s[i].y = 0; s[i].z = -1000; s[i].did_vibrate = 0; }
  s[12].z = -4200; /* > crash_above_mg (3800) */
  cm_accel_feed(&core, s, 25, now_ms);
  cm_tick(&core, now_ms, sim_hour);
  drain();
}

/* The shell announces each buzz (WMSG_VIBE) because the firmware's
 * did_vibrate flag dies for a worker after any app exit (2026-09-30):
 * for the motor duration + 1 s delivery + 1.5 s ringing, unflagged jerks
 * and high-G samples are neither motion nor a shock. */
static void test_announced_buzz_is_neither_motion_nor_shock(void) {
  g_test = "announced_buzz_is_neither_motion_nor_shock";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;
  cfg.enabled[CM_DET_NOTWORN] = 0;
  setup(&cfg);
  warmup();
  uint32_t motion_before = core.last_motion_ms;

  cm_vibe_guard(&core, 700, now_ms);          /* 700 ms double pulse */
  sec_moving(); sec_shock(); sec_moving();    /* 3 s: inside 700+1000+1500 */
  CHECK(core.last_motion_ms == motion_before); /* not motion */
  CHECK(core.impact_phase == 0);               /* not a shock */
  secs_still_worn(70);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);

  /* a later announcement never shortens an active guard */
  cm_vibe_guard(&core, 5000, now_ms);
  uint32_t until = core.vibe_guard_until_ms;
  cm_vibe_guard(&core, 100, now_ms);
  CHECK(core.vibe_guard_until_ms == until);
  secs_still(9);                                /* guard (5+2.5 s) expires */

  /* after the window, motion counts again */
  sec_moving();
  CHECK(core.last_motion_ms == now_ms);
}

/* The alarm clock: the worker guards from 15 s before the alarm to 120 s
 * after (field 2026-09-30 07:30: alarm vibration -> "hard shock" -> a
 * check-in once the wearer lay still). Same mechanism, long window. */
static void test_alarm_window_shock_is_not_a_fall(void) {
  g_test = "alarm_window_shock_is_not_a_fall";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;
  cfg.enabled[CM_DET_NOTWORN] = 0;
  setup(&cfg);
  warmup();

  cm_vibe_guard(&core, 135000, now_ms);        /* 15 s lead + 120 s tail */
  secs_still(15);
  sec_shock(); sec_shock(); sec_shock();       /* the alarm ringing */
  secs_still_worn(66);                         /* snoozed, lying still */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(core.impact_phase == 0);

  /* the same shock outside any window is still a candidate */
  secs_still(60);
  sec_shock();
  CHECK(core.impact_phase == 2);
}

static void test_pulse_loss_full_ladder(void) {
  g_test = "pulse_loss_full_ladder";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  /* pulse disappears while lying still */
  secs_still(31); /* pulse_lost_after 30 s (still >= 20 s satisfied) */
  const cm_action *hb = find_type(CM_ACT_HR_BURST_ON);
  CHECK(hb != 0);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0); /* hunt is silent */
  log_reset();

  secs_still(31); /* hunt (30 s) finds nothing */
  const cm_action *ci = find_type(CM_ACT_CHECKIN_START);
  CHECK(ci != 0);
  CHECK(ci && ci->detector == CM_DET_PULSE);
  log_reset();

  secs_still(31); /* CHECKIN times out */
  CHECK(count_type(CM_ACT_COUNTDOWN_START) == 1);
  log_reset();

  secs_still(31); /* countdown expires */
  CHECK(count_type(CM_ACT_ALARM) == 1);
  CHECK(count_type(CM_ACT_HR_BURST_OFF) == 1); /* burst released at alarm */
}

static void test_pulse_returns_during_hunt(void) {
  g_test = "pulse_returns_during_hunt";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  secs_still(31);
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 1);
  log_reset();

  sec_still_hr(72); /* pulse found: silent stand-down */
  CHECK(count_type(CM_ACT_HR_BURST_OFF) == 1);
  for (int i = 0; i < 60; i++) sec_still_hr(70); /* pulse stays present */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
}

static void test_pulse_checkin_dismissed_by_pulse(void) {
  g_test = "pulse_checkin_dismissed_by_pulse";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  secs_still(62); /* through hunt into CHECKIN */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);
  log_reset();

  sec_still_hr(68); /* pulse returns during CHECKIN */
  const cm_action *cc = find_type(CM_ACT_ALERT_CANCELLED);
  CHECK(cc != 0);
  CHECK(cc && cc->reason == CM_CANCEL_PULSE);
  CHECK(count_type(CM_ACT_HR_BURST_OFF) == 1);
  CHECK(cm_current_stage(&core) == CM_STAGE_NONE);
}

static void test_pulse_user_cancel_snoozes(void) {
  g_test = "pulse_user_cancel_snoozes";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  secs_still(62);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);
  cm_user_ok(&core, now_ms);
  drain();
  log_reset();

  /* still no pulse, still still — but snoozed for 10 min */
  mins_still(5);
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 0);
}

static void test_nonmotion_daytime(void) {
  g_test = "nonmotion_daytime";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;   /* isolate the non-motion detector */
  cfg.enabled[CM_DET_NOTWORN] = 0;
  cfg.hr_available = 0;            /* flint/gabbro: motion is the only signal */
  setup(&cfg);
  warmup();

  /* perfectly still on motion-only hardware (warmup already banked
   * 60 s of stillness — fire lands at the loop end, stage fresh) */
  mins_still(39);
  secs_still(5);
  const cm_action *ci = find_type(CM_ACT_CHECKIN_START);
  CHECK(ci != 0);
  CHECK(ci && ci->detector == CM_DET_NONMOTION);
  log_reset();

  sec_moving(); /* one bump does not dismiss */
  CHECK(count_type(CM_ACT_ALERT_CANCELLED) == 0);
  sec_moving(); sec_moving(); /* sustained motion dismisses */
  const cm_action *cc = find_type(CM_ACT_ALERT_CANCELLED);
  CHECK(cc != 0);
  CHECK(cc && cc->reason == CM_CANCEL_MOTION);
}

static void test_nonmotion_night_threshold(void) {
  g_test = "nonmotion_night_threshold";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;
  cfg.enabled[CM_DET_NOTWORN] = 0;
  cfg.hr_available = 0;
  setup(&cfg);
  warmup();
  sim_hour = 2; /* night */

  mins_still(60);  /* under the 90 min night limit */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);

  mins_still(31);  /* now past 90 min */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);
}

/* The wearer's scenario: sleeping / meditating / watching TV. Perfectly
 * still with a live pulse must NEVER ping on HR hardware — the pulse IS
 * the proof of life. */
static void test_still_with_pulse_stays_silent(void) {
  g_test = "still_with_pulse_stays_silent";
  cm_config cfg = test_cfg();
  cfg.pulse_lost_after_s = 150;  /* realistic default: 60 s samples are fresh */
  setup(&cfg);
  warmup();

  for (int i = 0; i < 50 * 60; i++) {   /* 50 min, well past day threshold */
    if (i % 60 == 0) sec_still_hr((uint16_t)(62 + ((i / 60) & 1)));
    else sec_still();
  }
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 0);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 0);
  CHECK(count_type(CM_ACT_ALARM) == 0);
}

/* Backstop band: the HR sensor silently stops reading mid-sleep. Once the
 * pulse is staler than pulse_proof_min (but inside the worn grace), the
 * accumulated stillness may ping. */
static void test_nonmotion_backstop_stale_pulse(void) {
  g_test = "nonmotion_backstop_stale_pulse";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;   /* isolate from the pulse ladder */
  cfg.enabled[CM_DET_NOTWORN] = 0;
  setup(&cfg);
  warmup();

  for (int i = 0; i < 36 * 60; i++) {   /* still, pulse alive: silent */
    if (i % 60 == 0) sec_still_hr((uint16_t)(60 + ((i / 60) & 1)));
    else sec_still();
  }
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);

  mins_still(4);                        /* pulse now stale, proof holds */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);

  mins_still(2);                        /* proof lapsed, worn grace not yet */
  const cm_action *ci = find_type(CM_ACT_CHECKIN_START);
  CHECK(ci != 0);
  CHECK(ci && ci->detector == CM_DET_NONMOTION);
}

static void test_notworn_nag_not_alarm(void) {
  g_test = "notworn_nag_not_alarm";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_NONMOTION] = 0;
  cfg.enabled[CM_DET_CHECKIN] = 0;
  setup(&cfg);
  warmup();

  /* watch comes off: pulse gone + still. Pulse ladder fires first; user cancels. */
  secs_still(62);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);
  cm_user_ok(&core, now_ms);
  drain();
  log_reset();

  /* 15+ min later: not-worn nag (to wearer only), and no new pulse ladder
   * (worn-grace has lapsed, so pulse-loss no longer applies) */
  mins_still(16);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 1);
  CHECK(count_type(CM_ACT_ALARM) == 0);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);

  /* nag fires once, not repeatedly */
  mins_still(10);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 1);
}

static void test_scheduled_checkin(void) {
  g_test = "scheduled_checkin";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;
  cfg.enabled[CM_DET_NONMOTION] = 0;
  cfg.enabled[CM_DET_NOTWORN] = 0;
  cfg.enabled[CM_DET_CHECKIN] = 1;  /* opt-in feature, enabled for this test */
  cfg.checkin_interval_min = 60;
  cfg.checkin_remind_min = 5;
  cfg.checkin_grace_min = 10;
  setup(&cfg);

  /* stay active so nothing else triggers */
  for (int i = 0; i < 54 * 60; i++) { if (i % 30 == 0) sec_moving(); else sec_still(); }
  CHECK(count_type(CM_ACT_CHECKIN_REMINDER) == 0);
  for (int i = 0; i < 2 * 60; i++) sec_still();
  CHECK(count_type(CM_ACT_CHECKIN_REMINDER) == 1); /* T-5 min reminder */
  log_reset();

  /* miss the deadline: due (60) + grace (10) */
  for (int i = 0; i < 15 * 60; i++) { if (i % 30 == 0) sec_moving(); else sec_still(); }
  const cm_action *ci = find_type(CM_ACT_CHECKIN_START);
  CHECK(ci != 0);
  CHECK(ci && ci->detector == CM_DET_CHECKIN);
  log_reset();

  /* motion must NOT dismiss a scheduled check-in — button only */
  sec_moving(); sec_moving(); sec_moving();
  CHECK(count_type(CM_ACT_ALERT_CANCELLED) == 0);
  cm_user_ok(&core, now_ms);
  drain();
  CHECK(count_type(CM_ACT_ALERT_CANCELLED) == 1);

  /* answering rescheduled the next round */
  CHECK(cm_checkin_due_in_s(&core, now_ms) > 59u * 60u);
}

static void test_suspension_blocks_and_autoresumes(void) {
  g_test = "suspension_blocks_and_autoresumes";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  cm_suspend(&core, 1800, 1, now_ms); /* 30 min, auto-resume on */
  drain();
  CHECK(count_type(CM_ACT_SUSPEND_STARTED) == 1);
  log_reset();

  /* watch on the shelf: no pulse, no motion, 20 min — total silence expected */
  mins_still(20);
  CHECK(log_count == 0);

  /* bag ride: motion alone must NOT resume — being carried off-wrist is
   * a valid suspend state (owner decision 2026-08-29) */
  for (int i = 0; i < 120; i++) sec_moving();
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 0);
  CHECK(cm_suspend_remaining_s(&core, now_ms) > 0);

  /* back on the wrist: sustained motion + changing bpm -> auto-resume */
  for (int i = 0; i < 20; i++) sec_moving_hr((uint16_t)(70 + (i & 1)));
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 1);
  CHECK(cm_suspend_remaining_s(&core, now_ms) == 0);
}

static void test_suspension_expiry(void) {
  g_test = "suspension_expiry";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  cm_suspend(&core, 60, 0, now_ms);
  drain();
  log_reset();

  secs_still(62);
  CHECK(count_type(CM_ACT_SUSPEND_EXPIRED) == 1);
  /* baselines were reset: no instant pulse/non-motion trigger */
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 0);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
}

/* Resume needs BOTH halves: readings without motion never resume (the
 * phantom-press case), and motion whose pulse evidence has gone stale
 * never resumes either (the bag-ride case). */
static void test_suspension_pulse_does_not_resume(void) {
  g_test = "suspension_pulse_does_not_resume";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  cm_suspend(&core, 3600, 1, now_ms);
  drain();
  log_reset();

  mins_still(10);
  for (int i = 0; i < 30; i++) sec_still_hr(75); /* phantom "pulse" on a shelf */
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 0);
  CHECK(cm_suspend_remaining_s(&core, now_ms) > 0);

  /* the phantom change ages past the fresh window; then motion alone */
  mins_still(4);
  for (int i = 0; i < 30; i++) sec_moving();
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 0);

  /* both together = wrist evidence */
  for (int i = 0; i < 20; i++) sec_moving_hr((uint16_t)(70 + (i & 1)));
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 1);
}

/* The T4 field bug: pressing "suspend" while still wearing the watch used
 * to auto-resume within a second (fresh pulse). The arming grace must
 * swallow all signals — including continuous motion — for resume_grace_s. */
static void test_suspension_grace_blocks_instant_resume(void) {
  g_test = "suspension_grace_blocks_instant_resume";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  cm_suspend(&core, 1800, 1, now_ms);
  drain();
  log_reset();

  /* wearer walks off with the watch on (motion + live pulse) */
  for (int i = 0; i < 50; i++) sec_moving_hr((uint16_t)(70 + (i & 1)));
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 0);

  /* grace over: 15 s worn run resumes */
  for (int i = 0; i < 30; i++) sec_moving_hr((uint16_t)(70 + (i & 1)));
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 1);
  CHECK(cm_suspend_remaining_s(&core, now_ms) == 0);
}

/* Sensor died while worn (field 2026-08-29: the HRM failed to start on
 * 2 of 3 consecutive boots): a moving wearer with a flat bpm gets the
 * sensor-fault nag — not "Not worn?", never the ladder. */
static void test_sensor_fault_fires_when_moving_without_pulse(void) {
  g_test = "sensor_fault_fires_when_moving_without_pulse";
  cm_config cfg = test_cfg();
  cfg.sensor_fault_after_min = 4;
  setup(&cfg);
  warmup();

  for (int i = 0; i < 3 * 60; i++) sec_moving();
  CHECK(count_type(CM_ACT_SENSOR_FAULT) == 0); /* under threshold */

  for (int i = 0; i < 2 * 60; i++) sec_moving();
  CHECK(count_type(CM_ACT_SENSOR_FAULT) == 1);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 0);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_ALARM) == 0);

  /* once per episode: motion never re-arms it */
  for (int i = 0; i < 5 * 60; i++) sec_moving();
  CHECK(count_type(CM_ACT_SENSOR_FAULT) == 1);

  /* a live pulse re-arms; a fresh flat episode fires again */
  for (int i = 0; i < 30; i++) sec_moving_hr((uint16_t)(70 + (i & 1)));
  for (int i = 0; i < 5 * 60; i++) sec_moving();
  CHECK(count_type(CM_ACT_SENSOR_FAULT) == 2);
}

/* Delivery hardening (2026-08-29): ladder episodes carry a durable id
 * minted from a shell-persisted sequence; promotions keep it, cancel
 * echoes it, informational actions carry 0. */
static void test_episode_identity_and_carryover(void) {
  g_test = "episode_identity_and_carryover";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();
  core.episode_seq = 41; /* shell seed from persist */

  cm_manual_sos(&core, now_ms); drain();
  const cm_action *cd = find_type(CM_ACT_COUNTDOWN_START);
  CHECK(cd && cd->episode == 42);
  secs_still(7); /* SOS fuse (5 s) -> ALARM, same episode */
  const cm_action *al = find_type(CM_ACT_ALARM);
  CHECK(al && al->episode == 42);
  cm_user_ok(&core, now_ms); drain();
  const cm_action *cx = find_type(CM_ACT_ALERT_CANCELLED);
  CHECK(cx && cx->episode == 42);

  log_reset();
  cm_manual_sos(&core, now_ms); drain();
  cd = find_type(CM_ACT_COUNTDOWN_START);
  CHECK(cd && cd->episode == 43); /* new episode, new id */
  cm_user_ok(&core, now_ms); drain();

  log_reset();
  cm_set_charging(&core, 1, now_ms); drain();
  const cm_action *ch = find_type(CM_ACT_CHARGING_STARTED);
  CHECK(ch && ch->episode == 0); /* informational: no episode */
  cm_set_charging(&core, 0, now_ms); drain();
}

static void test_stage_remaining_seconds(void) {
  g_test = "stage_remaining_seconds";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();
  CHECK(cm_stage_remaining_s(&core, now_ms) == 0); /* idle */
  cm_manual_sos(&core, now_ms); drain();          /* SOS fuse 5 s */
  CHECK(cm_stage_remaining_s(&core, now_ms) == 5);
  secs_still(2);
  CHECK(cm_stage_remaining_s(&core, now_ms) == 3);
  cm_user_ok(&core, now_ms); drain();
  CHECK(cm_stage_remaining_s(&core, now_ms) == 0);
}

static void test_sensor_fault_holds_during_suspension(void) {
  g_test = "sensor_fault_holds_during_suspension";
  cm_config cfg = test_cfg();
  cfg.sensor_fault_after_min = 4;
  setup(&cfg);
  warmup();

  cm_suspend(&core, 3600, 0, now_ms);
  drain();
  log_reset();
  for (int i = 0; i < 6 * 60; i++) sec_moving();
  CHECK(count_type(CM_ACT_SENSOR_FAULT) == 0);
}

/* Removal signature: motion right after the last pulse, then stillness —
 * this must go to the not-worn nag, never the alarm ladder. */
static void test_removal_goes_to_nag_not_ladder(void) {
  g_test = "removal_goes_to_nag_not_ladder";
  cm_config cfg = test_cfg();
  cfg.notworn_after_min = 3;
  setup(&cfg);
  warmup();

  for (int i = 0; i < 10; i++) sec_moving(); /* unbuckle, set on the table */
  mins_still(2);
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 0);   /* no silent hunt */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0); /* no ladder */
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 0);   /* not yet: under 3 min */

  mins_still(2);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 1);   /* nag at ~3 min */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_ALARM) == 0);

  mins_still(10);                               /* once per episode */
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 1);
  CHECK(count_type(CM_ACT_ALARM) == 0);
}

/* On the charger = deliberate off-wrist: total silence while plugged,
 * fresh baselines on unplug — the T4-family behavior, but automatic. */
static void test_charging_hold(void) {
  g_test = "charging_hold";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  cm_set_charging(&core, 1, now_ms);
  drain();
  CHECK(count_type(CM_ACT_CHARGING_STARTED) == 1);
  log_reset();

  mins_still(60); /* no pulse, no motion, on charger: nothing may fire */
  CHECK(log_count == 0);

  cm_set_charging(&core, 0, now_ms);
  drain();
  CHECK(count_type(CM_ACT_CHARGING_ENDED) == 1);
  log_reset();

  /* baselines reset: wearer puts the watch back on — no instant triggers
   * (an unplugged-and-abandoned watch still earns the ladder later, by
   * the same arrest-vs-removal rules as any other signal loss) */
  for (int i = 0; i < 120; i++) {
    if (i % 20 == 0) sec_still_hr(66); else sec_still();
  }
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 0);
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 0);
}

/* Docking mid-alert behaves like a suspension: pre-alarm stages cancel,
 * a latched ALARM survives. */
static void test_charging_cancels_checkin_not_alarm(void) {
  g_test = "charging_cancels_checkin_not_alarm";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  secs_still(62); /* pulse ladder reaches CHECKIN */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);
  cm_set_charging(&core, 1, now_ms);
  drain();
  const cm_action *cc = find_type(CM_ACT_ALERT_CANCELLED);
  CHECK(cc != 0);
  CHECK(cc && cc->reason == CM_CANCEL_SUSPEND);
  CHECK(cm_current_stage(&core) == CM_STAGE_NONE);

  /* latched alarm: charging must NOT clear it */
  cm_config cfg2 = test_cfg();
  setup(&cfg2);
  warmup();
  cm_manual_sos(&core, now_ms);
  secs_still(7); /* SOS fuse (5 s) expires -> ALARM latches */
  CHECK(cm_current_stage(&core) == CM_STAGE_ALARM);
  log_reset();
  cm_set_charging(&core, 1, now_ms);
  drain();
  CHECK(cm_current_stage(&core) == CM_STAGE_ALARM);
  CHECK(count_type(CM_ACT_ALERT_CANCELLED) == 0);
}

/* Simulate the S4 field finding: after removal (or arrest) the firmware
 * keeps serving the last bpm with fresh events, bit-identical forever. */
static void sec_still_hr_frozen(uint16_t bpm) { sec_still_hr(bpm); }

/* S4 scenario: watch removed (handling motion near the freeze moment),
 * then frozen-82 readings continue — must go to the NAG, not the ladder,
 * and the frozen feed must not postpone the nag. */
static void test_frozen_pulse_removal_nags(void) {
  g_test = "frozen_pulse_removal_nags";
  cm_config cfg = test_cfg();
  cfg.notworn_after_min = 3;
  cfg.pulse_flat_after_s = 300;
  setup(&cfg);
  warmup();

  for (int i = 0; i < 10; i++) sec_moving();     /* unbuckle, set down */
  for (int i = 0; i < 4 * 60; i++) {             /* frozen feed continues */
    if (i % 2 == 0) sec_still_hr_frozen(82); else sec_still();
  }
  /* The nag now arbitrates with a silent 1 Hz hunt first (2026-09-09);
   * a frozen feed stays flat through it, so the nag still fires - once. */
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 1);
  CHECK(find_type(CM_ACT_HR_BURST_ON)->detector == CM_DET_NOTWORN);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);  /* no ladder */
  CHECK(count_type(CM_ACT_ALARM) == 0);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 1);    /* nag despite readings */
}

/* S4 scenario: arrest signature — wearer long still, the value freezes
 * while events keep coming, no motion near the freeze. The flat trigger
 * hunts; the hunt stays flat; the ladder must run. */
static void test_frozen_pulse_still_wearer_alarms(void) {
  g_test = "frozen_pulse_still_wearer_alarms";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_NONMOTION] = 0;  /* isolate the pulse path */
  cfg.enabled[CM_DET_NOTWORN] = 0;
  cfg.pulse_flat_after_s = 120;       /* compressed for the test */
  setup(&cfg);
  warmup();

  /* value freezes at 76 but readings keep arriving every 2 s */
  for (int i = 0; i < 121; i++) {
    if (i % 2 == 0) sec_still_hr_frozen(76); else sec_still();
  }
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 1);    /* flat -> silent hunt */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  log_reset();

  for (int i = 0; i < 31; i++) {                 /* hunt: still frozen */
    if (i % 2 == 0) sec_still_hr_frozen(76); else sec_still();
  }
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);  /* hunt failed: ladder */
  log_reset();

  sec_still_hr(78); /* a CHANGING value dismisses the check-in */
  const cm_action *cc = find_type(CM_ACT_ALERT_CANCELLED);
  CHECK(cc != 0);
  CHECK(cc && cc->reason == CM_CANCEL_PULSE);
}

/* Normal resting jitter (the worn_still lab stage: 74..81, changing
 * every few samples) must never trigger anything. */
static void test_jittering_rest_stays_silent(void) {
  g_test = "jittering_rest_stays_silent";
  cm_config cfg = test_cfg();
  cfg.pulse_flat_after_s = 120;
  cfg.pulse_lost_after_s = 150;  /* realistic: 60 s samples stay fresh */
  setup(&cfg);
  warmup();

  for (int i = 0; i < 20 * 60; i++) {  /* 20 min still, pulse jitters */
    if (i % 60 == 0) sec_still_hr((uint16_t)(76 + ((i / 60) % 3)));
    else sec_still();
  }
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 0);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 0);
}

/* Field event 2026-08-27: a table tremor ran the impact ladder on a
 * watch lying off-wrist. With the pulse frozen beyond the flat window,
 * the impact ladder must stand down — tremors are not falls. */
static void test_impact_suppressed_offwrist(void) {
  g_test = "impact_suppressed_offwrist";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;    /* isolate the impact detector */
  cfg.enabled[CM_DET_NOTWORN] = 0;
  cfg.enabled[CM_DET_NONMOTION] = 0;
  cfg.pulse_flat_after_s = 120;
  setup(&cfg);
  warmup();

  for (int i = 0; i < 3 * 60; i++) {  /* off-wrist: frozen feed */
    if (i % 2 == 0) sec_still_hr(82); else sec_still();
  }
  event_fall();                        /* table tremor */
  secs_still(66);                      /* settle + immobility pass */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_ALARM) == 0);
}

/* Worker restarts while the watch lies off-wrist (build update on the
 * nightstand): no reading ever arrives, and the wearer must still be
 * told monitoring is blind — ever_pulse must not gate the nag. */
static void test_never_worn_since_restart_still_nags(void) {
  g_test = "never_worn_since_restart_still_nags";
  cm_config cfg = test_cfg();
  cfg.notworn_after_min = 3;
  setup(&cfg);           /* NO warmup: fresh restart, zero readings */

  mins_still(4);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 1);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);  /* never-worn: no ladder */
  CHECK(count_type(CM_ACT_ALARM) == 0);

  mins_still(10);        /* once per episode */
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 1);
}

/* S4 sensor lab: silent detector hold — the guided test deliberately
 * removes the watch, and nothing may fire during or right after. */
static void test_lab_hold_is_silent(void) {
  g_test = "lab_hold_is_silent";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  cm_set_lab_hold(&core, 1, now_ms);
  drain();
  log_reset();
  mins_still(15); /* strap loose, table, face-down... total silence */
  CHECK(log_count == 0);

  cm_set_lab_hold(&core, 0, now_ms);
  drain();
  log_reset();
  for (int i = 0; i < 120; i++) {  /* wearer puts it back on */
    if (i % 20 == 0) sec_still_hr(68); else sec_still();
  }
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 0);
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 0);
}

static void test_manual_sos(void) {
  g_test = "manual_sos";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  cm_manual_sos(&core, now_ms);
  drain();
  const cm_action *cd = find_type(CM_ACT_COUNTDOWN_START);
  CHECK(cd != 0);
  CHECK(cd && cd->detector == CM_DET_SOS);
  CHECK(cd && cd->seconds == 5);
  log_reset();

  secs_still(6);
  CHECK(count_type(CM_ACT_ALARM) == 1);
}

static void test_hr_unavailable_hardware(void) {
  g_test = "hr_unavailable_hardware";
  cm_config cfg = test_cfg();
  cfg.hr_available = 0; /* flint / gabbro */
  setup(&cfg);
  for (int i = 0; i < 5; i++) sec_moving();
  log_reset();

  /* no pulse machinery may ever fire */
  mins_still(10);
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 0);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 0);

  /* but non-motion still works (assumed worn) */
  mins_still(31); /* total > 40 min */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);
}


/* SELECT while suspended = explicit check-in: ends the suspension in any
 * mode (auto-resume or timer-only carry) immediately; a latched ALARM
 * takes precedence so the first press cancels it and the suspension
 * continues (owner request 2026-09-07, with the 5-min sensor cadence). */
static void test_manual_resume_by_select(void) {
  g_test = "manual_resume_by_select";
  cm_config cfg = test_cfg();
  setup(&cfg);
  warmup();

  /* auto-resume suspension: SELECT ends it at once, no instant triggers */
  cm_suspend(&core, 1800, 1, now_ms);
  drain(); log_reset();
  mins_still(5);
  cm_user_ok(&core, now_ms); drain();
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 1);
  CHECK(cm_suspend_remaining_s(&core, now_ms) == 0);
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 0);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  secs_still(30);
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 0); /* baselines were reset */

  /* timer-only carry mode: SELECT still resumes (explicit, not heuristic) */
  cm_suspend(&core, 7200, 0, now_ms);
  drain(); log_reset();
  for (int i = 0; i < 120; i++) sec_moving_hr((uint16_t)(70 + (i & 1)));
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 0); /* carry: wear signals ignored */
  cm_user_ok(&core, now_ms); drain();
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 1);
  CHECK(cm_suspend_remaining_s(&core, now_ms) == 0);

  /* latched alarm survives docking/suspension: first press cancels the
   * alarm and keeps the suspension, second press resumes */
  cm_manual_sos(&core, now_ms); drain();
  secs_still(40);
  CHECK(cm_current_stage(&core) == CM_STAGE_ALARM);
  cm_suspend(&core, 1800, 1, now_ms); drain();
  CHECK(cm_current_stage(&core) == CM_STAGE_ALARM);
  log_reset();
  cm_user_ok(&core, now_ms); drain();
  CHECK(count_type(CM_ACT_ALERT_CANCELLED) == 1);
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 0);
  CHECK(cm_suspend_remaining_s(&core, now_ms) > 0);
  cm_user_ok(&core, now_ms); drain();
  CHECK(count_type(CM_ACT_AUTO_RESUMED) == 1);
  CHECK(cm_suspend_remaining_s(&core, now_ms) == 0);
}


/* Field 2026-09-09 02:05: deep sleep, 16 min without motion, raw bpm
 * 67/67/67 at the 60 s cadence -> "Not worn?" woke the wearer. The nag
 * must arbitrate with the 1 Hz hunt: a live wrist changes within
 * seconds (no nag, quiet cooldown), a nightstand stays flat (nag).
 * Sensor model: one reading per 60 s while idle; during a hunt the
 * burst streams at 1 Hz - jittering on a wrist, frozen on a table. */
static void sleep_seconds(int n, uint16_t base) {
  for (int i = 0; i < n; i++) {
    if (core.pulse_phase == 1) sec_still_hr((uint16_t)(base + (i & 1)));
    else if (i % 60 == 0) sec_still_hr(base);
    else sec_still();
  }
}
static void nightstand_seconds(int n, uint16_t frozen) {
  for (int i = 0; i < n; i++) {
    if (core.pulse_phase == 1 || i % 60 == 0) sec_still_hr_frozen(frozen);
    else sec_still();
  }
}
static void test_sleeping_flat_bpm_hunts_before_nag(void) {
  g_test = "sleeping_flat_bpm_hunts_before_nag";
  cm_config cfg = test_cfg();
  cfg.notworn_after_min = 3;
  cfg.pulse_flat_after_s = 300;
  cfg.pulse_lost_after_s = 150;        /* production: > 2x the 60 s cadence */
  cfg.enabled[CM_DET_NONMOTION] = 0;   /* isolate the pulse/not-worn paths */
  cfg.enabled[CM_DET_CHECKIN] = 0;
  setup(&cfg);
  warmup();
  log_reset();

  /* asleep, steady 67: the first thing to fire is the silent not-worn
   * arbiter at 3 min - never the nag */
  sleep_seconds(200, 67);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 0);
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 1);
  CHECK(find_type(CM_ACT_HR_BURST_ON)->detector == CM_DET_NOTWORN);
  CHECK(count_type(CM_ACT_HR_BURST_OFF) == 1);         /* jitter ended it */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  log_reset();

  /* a whole steady-sleep stretch: silent throughout, hunts bounded */
  sleep_seconds(30 * 60, 67);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 0);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_ALARM) == 0);
  CHECK(count_type(CM_ACT_HR_BURST_ON) <= 10);         /* ~one per 5 min */
  log_reset();

  /* nightstand: set down (motion near the last change), then the feed
   * freezes for real - flat even at 1 Hz -> the nag, and no ladder */
  for (int i = 0; i < 10; i++) sec_moving();
  nightstand_seconds(15 * 60, 69);
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 1);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_ALARM) == 0);
  mins_still(10);                                      /* once per episode */
  CHECK(count_type(CM_ACT_NOTWORN_NAG) == 1);
}


/* Field 2026-09-09 11:46: a desk bump cancelled a pulse-loss CHECKIN one
 * second after it started, and repeated bumps kept standing the hunt
 * down. A single jerk is a bump — a desk, a bed partner turning, a
 * vehicle — not a wearer. Only sustained motion (jerk in 3 distinct
 * seconds within 10 s) dismisses a check-in or stands a hunt down. */
static void test_bumps_do_not_dismiss_pulse_ladder(void) {
  g_test = "bumps_do_not_dismiss_pulse_ladder";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_NONMOTION] = 0;
  cfg.enabled[CM_DET_NOTWORN] = 0;
  cfg.enabled[CM_DET_CHECKIN] = 0;
  setup(&cfg);
  warmup();
  log_reset();

  /* pulse stops; the surface is bumped every 15 s (bed partner) */
  int secs = 0;
  while (count_type(CM_ACT_CHECKIN_START) == 0 && secs < 400) {
    if (secs % 15 == 7) sec_moving(); else sec_still();
    secs++;
  }
  CHECK(count_type(CM_ACT_HR_BURST_ON) >= 1);        /* hunt ran despite bumps */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);      /* ... and concluded */
  log_reset();

  /* bumps during CHECKIN do not dismiss it: the ladder proceeds */
  for (int i = 0; i < 40; i++) { if (i % 15 == 7) sec_moving(); else sec_still(); }
  CHECK(count_type(CM_ACT_ALERT_CANCELLED) == 0);
  CHECK(count_type(CM_ACT_COUNTDOWN_START) == 1);
  for (int i = 0; i < 40; i++) { if (i % 15 == 7) sec_moving(); else sec_still(); }
  CHECK(count_type(CM_ACT_ALARM) == 1);
}

static void test_sustained_motion_still_dismisses(void) {
  g_test = "sustained_motion_still_dismisses";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_NONMOTION] = 0;
  cfg.enabled[CM_DET_NOTWORN] = 0;
  cfg.enabled[CM_DET_CHECKIN] = 0;
  setup(&cfg);
  warmup();
  log_reset();

  int secs = 0;
  while (count_type(CM_ACT_CHECKIN_START) == 0 && secs < 400) { sec_still(); secs++; }
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);
  log_reset();
  sec_moving(); sec_moving();                        /* two seconds: not yet */
  CHECK(count_type(CM_ACT_ALERT_CANCELLED) == 0);
  sec_moving();                                      /* third second: wearer */
  const cm_action *cc = find_type(CM_ACT_ALERT_CANCELLED);
  CHECK(cc != 0);
  CHECK(cc && cc->reason == CM_CANCEL_MOTION);
  CHECK(cm_current_stage(&core) == CM_STAGE_NONE);

  /* bumps during a hunt do not stand it down; sustained motion does.
   * Re-establish a live wrist first (clears the post-cancel snooze and
   * the worn-recently grace), then let the pulse stop again. */
  /* 60 s of live readings: the dismissal motion is then outside the
   * removal window of the last value change, so the ladder (not the
   * nag) owns the next loss. */
  for (int i = 0; i < 60; i++) sec_still_hr((uint16_t)(70 + (i & 1)));
  log_reset();
  secs = 0;
  while (count_type(CM_ACT_HR_BURST_ON) == 0 && secs < 300) { sec_still(); secs++; }
  CHECK(count_type(CM_ACT_HR_BURST_ON) == 1);
  sec_moving(); sec_still(); sec_still();
  CHECK(count_type(CM_ACT_HR_BURST_OFF) == 0);      /* bump ignored */
  sec_moving(); sec_moving(); sec_moving();
  CHECK(count_type(CM_ACT_HR_BURST_OFF) == 1);      /* wearer: stand down */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
}


/* Field 2026-09-09 12:33: impact CHECKIN on a desk cancelled itself at
 * the third buzz — the case ringing after each 5 s vibration counted as
 * a motion-second. Own vibration (and its aftershock) is never motion. */
static void test_own_vibration_is_not_motion(void) {
  g_test = "own_vibration_is_not_motion";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;
  cfg.enabled[CM_DET_NOTWORN] = 0;
  setup(&cfg);
  warmup();
  secs_still(30);
  uint32_t before = core.last_motion_ms;
  sec_buzz_aftershock();
  CHECK(core.last_motion_ms == before);           /* ringing ignored */
  sec_moving();                                    /* 1 s later: still guarded */
  CHECK(core.last_motion_ms == before);
  sec_still();
  sec_moving();                                    /* 3 s later: real motion */
  CHECK(core.last_motion_ms != before);

  /* an impact CHECKIN survives its own buzzes on a hard desk */
  log_reset();
  event_fall();
  secs_still_worn(66);
  CHECK(count_type(CM_ACT_CHECKIN_START) == 1);
  log_reset();
  for (int i = 0; i < 31; i++) {
    if (i % 5 == 0) sec_buzz_aftershock(); else sec_still_hr((uint16_t)(70 + (i & 1)));
  }
  CHECK(count_type(CM_ACT_ALERT_CANCELLED) == 0);
  CHECK(count_type(CM_ACT_COUNTDOWN_START) == 1);
}

/* Setting a watch down on a desk reads as a shock; with no reading since
 * the shock it is not a fall — the not-worn path owns it. */
static void test_impact_on_unworn_watch_is_silent(void) {
  g_test = "impact_on_unworn_watch_is_silent";
  cm_config cfg = test_cfg();
  cfg.enabled[CM_DET_PULSE] = 0;
  cfg.enabled[CM_DET_NOTWORN] = 0;
  cfg.enabled[CM_DET_NONMOTION] = 0;
  setup(&cfg);
  warmup();
  log_reset();
  for (int i = 0; i < 3; i++) sec_moving();       /* handling */
  event_fall();                                    /* set down: shock */
  secs_still(66);                                  /* no readings: off wrist */
  CHECK(count_type(CM_ACT_CHECKIN_START) == 0);
  CHECK(count_type(CM_ACT_ALARM) == 0);
}

int main(void) {
  test_defaults();
  test_impact_full_ladder();
  test_impact_cancelled_by_motion();
  test_impact_checkin_needs_button();
  test_apply_config();
  test_announced_buzz_is_neither_motion_nor_shock();
  test_alarm_window_shock_is_not_a_fall();
  test_pulse_loss_full_ladder();
  test_pulse_returns_during_hunt();
  test_pulse_checkin_dismissed_by_pulse();
  test_pulse_user_cancel_snoozes();
  test_nonmotion_daytime();
  test_nonmotion_night_threshold();
  test_still_with_pulse_stays_silent();
  test_nonmotion_backstop_stale_pulse();
  test_notworn_nag_not_alarm();
  test_removal_goes_to_nag_not_ladder();
  test_scheduled_checkin();
  test_suspension_blocks_and_autoresumes();
  test_suspension_expiry();
  test_suspension_pulse_does_not_resume();
  test_suspension_grace_blocks_instant_resume();
  test_sensor_fault_fires_when_moving_without_pulse();
  test_sensor_fault_holds_during_suspension();
  test_episode_identity_and_carryover();
  test_stage_remaining_seconds();
  test_charging_hold();
  test_charging_cancels_checkin_not_alarm();
  test_frozen_pulse_removal_nags();
  test_frozen_pulse_still_wearer_alarms();
  test_jittering_rest_stays_silent();
  test_impact_suppressed_offwrist();
  test_never_worn_since_restart_still_nags();
  test_lab_hold_is_silent();
  test_manual_sos();
  test_hr_unavailable_hardware();
  test_manual_resume_by_select();
  test_sleeping_flat_bpm_hunts_before_nag();
  test_bumps_do_not_dismiss_pulse_ladder();
  test_sustained_motion_still_dismisses();
  test_own_vibration_is_not_motion();
  test_impact_on_unworn_watch_is_silent();

  printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures ? 1 : 0;
}
