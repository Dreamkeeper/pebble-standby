/*
 * Foreground app: status screen, alert ladder UI, suspension menu, phone link.
 *
 * In worker mode the app is normally closed; the worker launches it when an
 * alert needs vibration/UI/AppMessage. In persistent-foreground mode ("high
 * assurance") this app stays open and doubles as a watchface-style status
 * screen (YaForecasWatch2 rendering merge: TODO, M3).
 */
#include <pebble.h>
#include "../core/detectors.h"
#include "../core/protocol.h"

static uint8_t s_debug;
/* True when the worker launched us for an alert rather than the wearer
 * opening the app. Such a launch must return the watch to the watchface
 * as soon as the alert is over. */
static bool s_launched_by_worker;
/* Any launch the wearer did not perform themselves (worker alert OR a
 * phone-side relaunch: reconnect self-heal, worker re-arm, service
 * restart). Such a screen must hand itself back to the watchface as
 * soon as nothing needs the wearer — the field bug was phone-launched
 * apps bypassing the stale-launch guard and squatting forever. */
static bool s_auto_launched;
#define DLOG(...) do { \
    if (s_debug) APP_LOG(APP_LOG_LEVEL_DEBUG, __VA_ARGS__); \
  } while (0)

static Window *s_main_window;
static StatusBarLayer *s_status_bar;  /* guideline: long-running apps show time */
static TextLayer *s_status_layer;
static TextLayer *s_detail_layer;
static char s_status_buf[48];
static char s_detail_buf[64];
/* A not-worn nag owns the screen until the wearer reacts: the stale-launch
 * guard and the periodic status poll must not wipe or dismiss it. */
static bool s_nag_hold;
/* A nag that nobody answers must not squat the screen all night (field
 * 2026-09-09: 5 h open, two phone pushes a minute). The phone keeps the
 * notification; the watch returns to the watchface after this many s. */
static uint16_t s_nag_hold_ticks;
#define CM_NAG_HOLD_S 180

static const char *HINTS_TEXT =
    "SELECT check-in/resume\nUP suspend (hold: carry)\nDOWN hold SOS";

static Window *s_alert_window;
static TextLayer *s_alert_title;
static TextLayer *s_alert_count;   /* dominant countdown numerals */
static TextLayer *s_alert_body;
static char s_alert_count_buf[8];
static AppTimer *s_alert_timer;
static cm_action s_alert_action;
static uint16_t s_alert_seconds_left;
/* S1 latency drill: keep the app alive a few seconds after sending the
 * result so the outbox flushes before the stale-launch guard pops us. */
static uint8_t s_drill_hold_ticks;
#if CM_VIBE_DIAG
static uint8_t s_vd_show_ticks;
static char s_vd_buf[80];
static void vd_render(void) {
  uint32_t smp = persist_exists(PK_VIBE_DIAG_SAMPLES) ? (uint32_t)persist_read_int(PK_VIBE_DIAG_SAMPLES) : 0;
  uint32_t bur = persist_exists(PK_VIBE_DIAG_BURSTS) ? (uint32_t)persist_read_int(PK_VIBE_DIAG_BURSTS) : 0;
  time_t last = persist_exists(PK_VIBE_DIAG_LAST_T) ? (time_t)persist_read_int(PK_VIBE_DIAG_LAST_T) : 0;
  struct tm *lt = last ? localtime(&last) : NULL;
  snprintf(s_vd_buf, sizeof(s_vd_buf), "vibe flag: %lu smp\n%lu bursts, last %02d:%02d:%02d",
           (unsigned long)smp, (unsigned long)bur,
           lt ? lt->tm_hour : 0, lt ? lt->tm_min : 0, lt ? lt->tm_sec : 0);
  text_layer_set_text(s_detail_layer, s_vd_buf);
}
#endif
/* S4 sensor lab: the app must stay open to relay worker HR samples. */
static bool s_lab_hold;
/* Phone-launched apps are opened FOR something (lab, drill) that arrives
 * a few seconds later over AppMessage. The auto-launch guard must not
 * pop the app before the phone has had time to state its business —
 * the first status poll racing the lab-on message closed the app
 * instantly (field bug, round 9). */
static uint8_t s_phone_grace_ticks;

static void ensure_worker_running(void);
static uint16_t s_dbg_status_d1; /* bpm|heap from the last WMSG_STATUS */
/* Debug telemetry gets its own mid-screen layer so the button hints stay
 * visible (owner request: keep the tooltips even in debug mode). */
static TextLayer *s_diag_layer;
static char s_diag_buf[64];

static uint32_t app_now_ms(void) {
  time_t s; uint16_t ms;
  time_ms(&s, &ms);
  return (uint32_t)s * 1000u + ms;
}

/* ---------- phone link ---------- */

static void send_to_phone(uint8_t msg_type, const cm_action *a) {
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return;
  dict_write_uint8(out, MESSAGE_KEY_MSG_TYPE, msg_type);
  if (a) {
    dict_write_uint8(out, MESSAGE_KEY_DETECTOR, a->detector);
    dict_write_uint8(out, MESSAGE_KEY_CANCEL_REASON, a->reason);
    dict_write_uint16(out, MESSAGE_KEY_SECONDS, a->seconds);
    dict_write_uint16(out, MESSAGE_KEY_EPISODE, a->episode);
  }
  dict_write_uint8(out, MESSAGE_KEY_WATCH_BATTERY,
                   battery_state_service_peek().charge_percent);
  app_message_outbox_send();
  DLOG("tx pmsg=%u det=%u", msg_type, a ? a->detector : 0);
}

/* ---- acknowledged alarm delivery (hardening D2) ----
 * PRE_ALARM / ALARM / CANCEL are the messages that must arrive: they are
 * retried with backoff (1/2/4/8 s then 15 s) until the phone answers
 * PMSG_ALARM_ACK with the episode id, the episode ends, or the app
 * exits (the DataLogging channel then carries the ALARM). */
static cm_action s_pending_alarm;
static uint8_t s_pending_msg;      /* PMSG_* awaiting ACK; 0 = none */
static uint8_t s_pending_attempt;
static AppTimer *s_alarm_retry;

static void alarm_retry_cb(void *ctx);

static void schedule_alarm_retry(void) {
  static const uint16_t backoff_ms[4] = {1000, 2000, 4000, 8000};
  uint32_t d = s_pending_attempt < 4 ? backoff_ms[s_pending_attempt] : 15000;
  if (s_alarm_retry) app_timer_cancel(s_alarm_retry);
  s_alarm_retry = app_timer_register(d, alarm_retry_cb, NULL);
}

static void alarm_retry_cb(void *ctx) {
  s_alarm_retry = NULL;
  if (!s_pending_msg) return;
  s_pending_attempt++;
  DLOG("alarm retry #%u pmsg=%u ep=%u", s_pending_attempt, s_pending_msg,
       s_pending_alarm.episode);
  send_to_phone(s_pending_msg, &s_pending_alarm);
  schedule_alarm_retry();
}

static void send_alarm_to_phone(uint8_t msg_type, const cm_action *a) {
  s_pending_alarm = *a;
  s_pending_msg = msg_type;
  s_pending_attempt = 0;
  send_to_phone(msg_type, a);
  schedule_alarm_retry();
}

static void clear_pending_alarm(void) {
  s_pending_msg = 0;
  if (s_alarm_retry) { app_timer_cancel(s_alarm_retry); s_alarm_retry = NULL; }
}

static void set_debug(uint8_t on) {
  s_debug = on;
  persist_write_int(PK_DEBUG, on);
  AppWorkerMessage m = {.data0 = on};
  app_worker_send_message(WMSG_SET_DEBUG, &m);
  APP_LOG(APP_LOG_LEVEL_INFO, "debug %s", on ? "ON" : "off");
}

static void inbox_received(DictionaryIterator *iter, void *context) {
  Tuple *t = dict_find(iter, MESSAGE_KEY_MSG_TYPE);
  if (!t) return;
  DLOG("rx pmsg=%u", t->value->uint8);
  switch (t->value->uint8) {
    case PMSG_ALARM_ACK: {
      Tuple *v = dict_find(iter, MESSAGE_KEY_SECONDS);
      if (v && s_pending_msg && v->value->uint16 == s_pending_alarm.episode) {
        DLOG("alarm ep=%u ACKed by phone", s_pending_alarm.episode);
        clear_pending_alarm();
      }
      break;
    }
    case PMSG_SET_DEBUG: {
      Tuple *v = dict_find(iter, MESSAGE_KEY_SECONDS);
      set_debug(v && v->value->uint16 ? 1 : 0);
      break;
    }
    case PMSG_SET_QMETRIC: {
      Tuple *v = dict_find(iter, MESSAGE_KEY_SECONDS);
      AppWorkerMessage m = {.data0 = (uint16_t)(v && v->value->uint16 ? 1 : 0)};
      app_worker_send_message(WMSG_SET_QMETRIC, &m);
      break;
    }
    case PMSG_CONFIG: {
      Tuple *blob = dict_find(iter, MESSAGE_KEY_CFG_BLOB);
      if (blob && blob->length == sizeof(cm_config)) {
        persist_write_data(PK_CONFIG, blob->value->data, sizeof(cm_config));
        /* worker reloads config on restart; TODO: live reload message */
        send_to_phone(PMSG_CONFIG_ACK, NULL);
      }
      break;
    }
    case PMSG_USER_OK_REMOTE: {
      AppWorkerMessage m = {0};
      app_worker_send_message(WMSG_USER_OK, &m);
      break;
    }
    case PMSG_HR_LAB: {
      Tuple *v = dict_find(iter, MESSAGE_KEY_SECONDS);
      /* 0 = off, 1 = lab on, 2 = lab on + raw-quality peeks (only valid
       * on the hr-quality-diag firmware; the phone gates this). */
      uint16_t mode = v ? v->value->uint16 : 0;
      bool on = mode != 0;
      s_lab_hold = on;
      AppWorkerMessage m = {.data0 = mode};
      app_worker_send_message(WMSG_HR_LAB, &m);
      if (on) {
        text_layer_set_text(s_status_layer, "Sensor lab");
        text_layer_set_text(s_detail_layer,
                            "Follow the phone's\ninstructions");
      } else {
        text_layer_set_text(s_status_layer, "Monitoring");
        text_layer_set_text(s_detail_layer, HINTS_TEXT);
        /* auto-launch guard takes the screen back on the next poll */
      }
      APP_LOG(APP_LOG_LEVEL_INFO, "sensor lab %s", on ? "ON" : "off");
      break;
    }
    case PMSG_DRILL: {
      /* Arm the worker, then get out of the way: the measured launch must
       * be a genuine cold start, not a foreground handoff. */
      AppWorkerMessage m = {0};
      app_worker_send_message(WMSG_DRILL, &m);
      APP_LOG(APP_LOG_LEVEL_INFO, "latency drill: armed worker, exiting");
      window_stack_pop_all(false);
      break;
    }
    default: break;
  }
}

/* ---------- alert window ---------- */

static const char *detector_name(uint8_t det) {
  switch (det) {
    case CM_DET_PULSE:     return "No pulse signal";
    case CM_DET_IMPACT:    return "Hard impact detected";
    case CM_DET_NONMOTION: return "No movement detected";
    case CM_DET_CHECKIN:   return "Check-in due";
    case CM_DET_SOS:       return "SOS";
    default:               return "Alert";
  }
}

/* Every vibration is announced to the worker first (WMSG_VIBE) so the
 * detectors ignore the motor and the case ringing: the firmware's
 * did_vibrate flag dies for a worker after any app exit (2026-09-30). */
static void buzz_announce(uint32_t ms) {
  AppWorkerMessage m = {.data0 = (uint16_t)(ms > 65000u ? 65000u : ms)};
  app_worker_send_message(WMSG_VIBE, &m);
}
static void buzz_short(void) { buzz_announce(300); vibes_short_pulse(); }
static void buzz_double(void) { buzz_announce(700); vibes_double_pulse(); }
static void buzz_pattern(const uint32_t *seg, uint32_t n) {
  uint32_t total = 0;
  for (uint32_t i = 0; i < n; i++) total += seg[i];
  buzz_announce(total);
  vibes_enqueue_custom_pattern((VibePattern){.durations = seg, .num_segments = n});
}

static void alert_vibe(void) {
  if (s_alert_action.type == CM_ACT_COUNTDOWN_START) {
    static const uint32_t seg[] = {400, 200, 400, 200, 400};
    buzz_pattern(seg, ARRAY_LENGTH(seg));
  } else {
    buzz_double();
  }
}

static void alert_tick(void *data) {
  s_alert_timer = NULL;
  if (!s_alert_window) return;

  if (s_alert_seconds_left > 0) s_alert_seconds_left--;
  snprintf(s_alert_count_buf, sizeof(s_alert_count_buf), "%u",
           (unsigned)s_alert_seconds_left);
  text_layer_set_text(s_alert_count, s_alert_count_buf);
  text_layer_set_text(s_alert_body,
                      s_alert_action.type == CM_ACT_COUNTDOWN_START
                          ? "SELECT if you are OK"
                          : "Are you OK? Press SELECT");

  /* escalate vibration every few seconds; the core decides stage changes */
  if (s_alert_seconds_left % 5 == 0) alert_vibe();
  s_alert_timer = app_timer_register(1000, alert_tick, NULL);
}

/* Semantic color (guideline: red only for genuine error/alarm states):
 * amber while asking, red once the countdown to alarm is running. */
static void alert_apply_style(void) {
  bool critical = s_alert_action.type == CM_ACT_COUNTDOWN_START ||
                  s_alert_action.type == CM_ACT_ALARM;
#if defined(PBL_COLOR)
  GColor bg = critical ? GColorRed : GColorChromeYellow;
  GColor fg = critical ? GColorWhite : GColorBlack;
#else
  (void)critical;  /* monochrome: hierarchy carries the urgency instead */
  GColor bg = GColorWhite;
  GColor fg = GColorBlack;
#endif
  window_set_background_color(s_alert_window, bg);
  TextLayer *layers[] = {s_alert_title, s_alert_count, s_alert_body};
  for (unsigned i = 0; i < ARRAY_LENGTH(layers); i++) {
    text_layer_set_background_color(layers[i], GColorClear);
    text_layer_set_text_color(layers[i], fg);
  }
}

static AppTimer *s_cancel_timer;
static uint8_t s_cancel_attempts;

static void cancel_retry_cb(void *ctx) {
  s_cancel_timer = NULL;
  if (!s_alert_window) { s_cancel_attempts = 0; return; } /* echo arrived */
  if (s_cancel_attempts >= 5) {
    text_layer_set_text(s_alert_body, "CANCEL FAILED\nPress again");
    s_cancel_attempts = 0;
    return;
  }
  AppWorkerMessage m = {0};
  app_worker_send_message(WMSG_USER_OK, &m);
  s_cancel_attempts++;
  s_cancel_timer = app_timer_register(700, cancel_retry_cb, NULL);
}

static void alert_select(ClickRecognizerRef ref, void *ctx) {
  /* The UI clears ONLY on the worker's ALERT_CANCELLED echo (hardening
   * D5): a cancel that never reached the worker must not look
   * successful while the ladder marches on. */
  vibes_cancel();
  text_layer_set_text(s_alert_body, "Cancelling...");
  AppWorkerMessage m = {0};
  app_worker_send_message(WMSG_USER_OK, &m);
  s_cancel_attempts = 1;
  if (s_cancel_timer) app_timer_cancel(s_cancel_timer);
  s_cancel_timer = app_timer_register(700, cancel_retry_cb, NULL);
}

static void alert_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, alert_select);
  /* BACK deliberately not subscribed: it dismisses the window but the
   * ladder keeps running in the worker — dismissing UI is not "I'm OK". */
}

static void alert_window_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  /* guideline hierarchy: 28pt title, dominant numeric countdown, 18pt hint */
  s_alert_title = text_layer_create(GRect(0, 4, b.size.w, 62));
  text_layer_set_font(s_alert_title,
                      fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  text_layer_set_text_alignment(s_alert_title, GTextAlignmentCenter);
  text_layer_set_text(s_alert_title, detector_name(s_alert_action.detector));
  layer_add_child(root, text_layer_get_layer(s_alert_title));

  s_alert_count = text_layer_create(GRect(0, b.size.h / 2 - 24, b.size.w, 52));
  text_layer_set_font(s_alert_count,
                      fonts_get_system_font(FONT_KEY_LECO_42_NUMBERS));
  text_layer_set_text_alignment(s_alert_count, GTextAlignmentCenter);
  layer_add_child(root, text_layer_get_layer(s_alert_count));

  s_alert_body = text_layer_create(GRect(0, b.size.h - 46, b.size.w, 42));
  text_layer_set_font(s_alert_body, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_alignment(s_alert_body, GTextAlignmentCenter);
  layer_add_child(root, text_layer_get_layer(s_alert_body));

  alert_apply_style();
  s_alert_seconds_left = s_alert_action.seconds;
  alert_vibe();
  alert_tick(NULL);
}

static void alert_window_unload(Window *w) {
  if (s_alert_timer) { app_timer_cancel(s_alert_timer); s_alert_timer = NULL; }
  text_layer_destroy(s_alert_title);
  text_layer_destroy(s_alert_count);
  text_layer_destroy(s_alert_body);
  window_destroy(s_alert_window);
  s_alert_window = NULL;
}

static void show_alert(const cm_action *a) {
  s_alert_action = *a;
  if (a->type == CM_ACT_COUNTDOWN_START) send_alarm_to_phone(PMSG_PRE_ALARM, a);
  if (a->type == CM_ACT_ALARM) send_alarm_to_phone(PMSG_ALARM, a);
  if (a->type == CM_ACT_ALERT_CANCELLED) {
    if (s_cancel_timer) { app_timer_cancel(s_cancel_timer); s_cancel_timer = NULL; }
    s_cancel_attempts = 0;
    send_alarm_to_phone(PMSG_CANCEL, a); /* retried too: a lost cancel
                                            leaves contacts escalating */
    vibes_cancel();
    if (s_alert_window) window_stack_remove(s_alert_window, true);
    /* Nothing left to show: hand the screen back to the watchface. */
    if (s_auto_launched) window_stack_pop_all(false);
    return;
  }
  if (!s_alert_window) {
    s_alert_window = window_create();
    window_set_click_config_provider(s_alert_window, alert_click_config);
    window_set_window_handlers(s_alert_window, (WindowHandlers){
        .load = alert_window_load, .unload = alert_window_unload});
    window_stack_push(s_alert_window, true);
  } else {
    s_alert_seconds_left = a->seconds;
    text_layer_set_text(s_alert_title, detector_name(a->detector));
    alert_apply_style();  /* stage may have escalated: amber -> red */
  }
}

static void handle_action(const cm_action *a) {
  DLOG("handle_action type=%u det=%u sec=%u", a->type, a->detector, a->seconds);
  switch (a->type) {
    case CM_ACT_CHECKIN_START:
    case CM_ACT_COUNTDOWN_START:
    case CM_ACT_ALARM:
    case CM_ACT_ALERT_CANCELLED:
      show_alert(a);
      break;
    case CM_ACT_NOTWORN_NAG:
      s_nag_hold = true;
      s_nag_hold_ticks = CM_NAG_HOLD_S;
      buzz_double();
      text_layer_set_text(s_status_layer, "Not worn?");
      text_layer_set_text(s_detail_layer, "Re-wear the watch,\nor UP to suspend");
      send_to_phone(PMSG_NOTWORN, a);
      break;
    case CM_ACT_SENSOR_FAULT:
      s_nag_hold = true;
      s_nag_hold_ticks = CM_NAG_HOLD_S;
      buzz_double();
      text_layer_set_text(s_status_layer, "No pulse signal");
      text_layer_set_text(s_detail_layer,
                          "Sensor dead, or carried\noff-wrist? Reboot the\nwatch, or UP to suspend");
      send_to_phone(PMSG_SENSOR_FAULT, a);
      break;
    case CM_ACT_CHECKIN_REMINDER:
      buzz_short();  /* TODO show "check-in due in N min" */
      break;
    case CM_ACT_LATENCY_DRILL: {
      /* S1: worker stamped arm + fire times on our shared wall clock.
       * launch = fire -> here (the true cold path). watch_total =
       * arm -> result handoff; the phone subtracts it from its round
       * trip to get pure BT transport, instead of guessing how long
       * the worker's tick-aligned countdown actually took. */
      uint32_t fire = (uint32_t)persist_read_int(PK_DRILL_FIRE_MS);
      uint32_t arm = (uint32_t)persist_read_int(PK_DRILL_ARM_MS);
      persist_delete(PK_DRILL_FIRE_MS);
      persist_delete(PK_DRILL_ARM_MS);
      uint32_t now = app_now_ms();
      uint32_t delta = fire ? now - fire : 0;
      uint32_t watch_total = arm ? now - arm : 0;
      buzz_short();
      DictionaryIterator *out;
      if (app_message_outbox_begin(&out) == APP_MSG_OK) {
        dict_write_uint8(out, MESSAGE_KEY_MSG_TYPE, PMSG_DRILL_RESULT);
        dict_write_uint16(out, MESSAGE_KEY_SECONDS,
                          (uint16_t)(delta > 65000 ? 65000 : delta));
        dict_write_uint16(out, MESSAGE_KEY_HEARTBEAT_SEQ,
                          (uint16_t)(watch_total > 65000 ? 65000 : watch_total));
        app_message_outbox_send();
      }
      s_drill_hold_ticks = 5;
      snprintf(s_detail_buf, sizeof(s_detail_buf),
               "Latency drill:\nlaunch %u ms", (unsigned)delta);
      text_layer_set_text(s_detail_layer, s_detail_buf);
      APP_LOG(APP_LOG_LEVEL_INFO, "latency drill: launch=%u ms", (unsigned)delta);
      break;
    }
    case CM_ACT_SUSPEND_STARTED:
      snprintf(s_status_buf, sizeof(s_status_buf), "Suspended %u min",
               (unsigned)(a->seconds / 60u));
      text_layer_set_text(s_status_layer, s_status_buf);
      send_to_phone(PMSG_SUSPENDED, a); /* SECONDS = duration */
      break;
    case CM_ACT_SUSPEND_EXPIRED:
    case CM_ACT_AUTO_RESUMED:
      buzz_double();
      s_nag_hold = false;
      text_layer_set_text(s_status_layer, "Monitoring");
      text_layer_set_text(s_detail_layer, HINTS_TEXT);
      send_to_phone(PMSG_SUSPENDED, a); /* SECONDS = 0 -> ended */
      break;
    case CM_ACT_CHARGING_STARTED:
      s_nag_hold = false; /* on the charger IS the answer to "not worn?" */
      text_layer_set_text(s_status_layer, "Charging");
      text_layer_set_text(s_detail_layer, "Monitoring paused\nwhile on charger");
      send_to_phone(PMSG_CHARGING, a); /* SECONDS = 1 */
      break;
    case CM_ACT_CHARGING_ENDED:
      buzz_short();
      text_layer_set_text(s_status_layer, "Monitoring");
      text_layer_set_text(s_detail_layer, HINTS_TEXT);
      send_to_phone(PMSG_CHARGING, a); /* SECONDS = 0 */
      break;
    default: break;
  }
}

static void pickup_pending_action(void) {
  if (!persist_exists(PK_PENDING_ACTION)) return;
  cm_action a;
  int32_t ok = persist_read_data(PK_PENDING_ACTION, &a, sizeof(a)) == sizeof(a);
  /* Parked actions expire: an app launch that arrives long after the
   * worker parked the action (relaunch race, reboot in between) must
   * not replay a stale alert (field bug 2026-08-29: yesterday's
   * "Not worn?" nag delivered onto a fresh boot). */
  time_t parked = persist_exists(PK_PENDING_ACTION_T)
      ? (time_t)persist_read_int(PK_PENDING_ACTION_T) : 0;
  persist_delete(PK_PENDING_ACTION);
  persist_delete(PK_PENDING_ACTION_T);
  if (!ok) return;
  /* Ladder actions never expire by age (hardening D4): the worker state
   * is the truth and status reconciliation corrects staleness within a
   * second. Informational nags keep the 60 s guard. */
  int ladder = a.type == CM_ACT_CHECKIN_START ||
               a.type == CM_ACT_COUNTDOWN_START || a.type == CM_ACT_ALARM;
  if (!ladder && (parked == 0 || time(NULL) - parked > 60)) return;
  handle_action(&a);
}

/* ---------- worker messages (while app is open) ---------- */

static void worker_message_handler(uint16_t type, AppWorkerMessage *m) {
  if (type == WMSG_ACTION) {
    cm_action a = {.type = (uint8_t)m->data0,
                   .detector = (uint8_t)m->data1,
                   .seconds = m->data2};
    persist_delete(PK_PENDING_ACTION); /* we got it live */
    persist_delete(PK_PENDING_ACTION_T);
    handle_action(&a);
  } else if (type == WMSG_HR_SAMPLE) {
    /* Relay the lab sample to the phone and mirror it on the watch. */
    DictionaryIterator *out;
    if (app_message_outbox_begin(&out) == APP_MSG_OK) {
      dict_write_uint8(out, MESSAGE_KEY_MSG_TYPE, PMSG_HR_SAMPLE);
      dict_write_uint16(out, MESSAGE_KEY_SECONDS, m->data0);
      dict_write_uint16(out, MESSAGE_KEY_HEARTBEAT_SEQ, m->data2);
      dict_write_uint8(out, MESSAGE_KEY_DETECTOR, (uint8_t)m->data1);
      app_message_outbox_send();
    }
    if (s_lab_hold) {
      /* data0 = bpm | quality_enc<<8; data2 = age | filtered<<8 */
      static const char *q_names[] = {"OW", "W", "P", "A", "G", "E"};
      unsigned q = (unsigned)(m->data0 >> 8);
      snprintf(s_detail_buf, sizeof(s_detail_buf),
               "bpm %u q:%s filt %u\nage %us · heap %uB",
               (unsigned)(m->data0 & 0xFF), q <= 5 ? q_names[q] : "-",
               (unsigned)(m->data2 >> 8), (unsigned)(m->data2 & 0xFF),
               (unsigned)(m->data1 * 64u));
      text_layer_set_text(s_detail_layer, s_detail_buf);
    }
  } else if (type == WMSG_DIAG) {
    /* Field-debug view: the exact ages and flags the not-worn and
     * pulse gates run on — on its own layer, hints stay visible. */
    if (s_debug && !s_lab_hold && s_diag_layer) {
      char f[8]; int n = 0;
      if (m->data2 & CM_DIAG_CHARGING)   f[n++] = 'C';
      if (m->data2 & CM_DIAG_LAB_HOLD)   f[n++] = 'L';
      if (m->data2 & CM_DIAG_HUNTING)    f[n++] = 'H';
      if (m->data2 & CM_DIAG_NAGGED)     f[n++] = 'N';
      if (m->data2 & CM_DIAG_EVER_PULSE) f[n++] = 'P';
      if (m->data2 & CM_DIAG_SUSPENDED)  f[n++] = 'S';
      f[n] = 0;
      snprintf(s_diag_buf, sizeof(s_diag_buf),
               "bpm %u · hp %uB · %s\nch %us · mo %us",
               (unsigned)(s_dbg_status_d1 & 0xFF),
               (unsigned)((s_dbg_status_d1 >> 8) * 64u),
               f[0] ? f : "-", (unsigned)m->data0, (unsigned)m->data1);
      text_layer_set_text(s_diag_layer, s_diag_buf);
      layer_set_hidden(text_layer_get_layer(s_diag_layer), false);
    }
  } else if (type == WMSG_STATUS) {
    /* v2 pack: data0 = stage|det<<3|charging<<7|bpm<<8; data1 = episode;
     * data2 = stage-secs-or-suspend-min | heap64<<8 (hardening D4). */
    uint8_t stage = (uint8_t)(m->data0 & 0x7u);
    uint8_t st_det = (uint8_t)((m->data0 >> 3) & 0x7u);
    uint8_t charging = (uint8_t)((m->data0 >> 7) & 0x1u);
    uint16_t episode = m->data1;
    uint8_t low = (uint8_t)(m->data2 & 0xFFu);
    if (s_debug && !s_lab_hold) {
      /* Cache bpm|heap; the WMSG_DIAG that follows renders the line. */
      s_dbg_status_d1 =
          (uint16_t)(((m->data0 >> 8) & 0xFFu) | (m->data2 & 0xFF00u));
    }
    /* Keep the big status line truthful: the worker owns the state, the
     * app just displays it (a stale "Suspended 30 min" after auto-resume
     * was exactly the failure mode this prevents). */
    if (!s_nag_hold && !s_lab_hold) {
      if (stage == CM_STAGE_NONE && low > 0) {
        snprintf(s_status_buf, sizeof(s_status_buf), "Suspended %u min",
                 (unsigned)low);
        text_layer_set_text(s_status_layer, s_status_buf);
      } else if (charging) {
        text_layer_set_text(s_status_layer, "Charging");
      } else if (stage == CM_STAGE_NONE) {
        text_layer_set_text(s_status_layer, "Monitoring");
      }
    }
    /* Reconciliation (hardening D4): an active ladder stage with no
     * alert window means a lost handoff — rebuild the UI and re-notify
     * the phone (which dedups by episode). A cleared stage with a
     * lingering window means the cancel echo was lost — close it. */
    if (stage != CM_STAGE_NONE && !s_alert_window && !s_lab_hold) {
      cm_action ra = {
        .type = stage == CM_STAGE_CHECKIN ? CM_ACT_CHECKIN_START
              : stage == CM_STAGE_COUNTDOWN ? CM_ACT_COUNTDOWN_START
              : CM_ACT_ALARM,
        .detector = st_det, .reason = 0, .episode = episode,
        .seconds = low,
      };
      DLOG("reconcile: worker stage=%u det=%u ep=%u", stage, st_det, episode);
      handle_action(&ra);
    } else if (stage == CM_STAGE_NONE && s_alert_window) {
      DLOG("reconcile: worker idle, closing stale alert UI");
      vibes_cancel();
      clear_pending_alarm();
      if (s_cancel_timer) { app_timer_cancel(s_cancel_timer); s_cancel_timer = NULL; }
      window_stack_remove(s_alert_window, true);
    }
    /* Auto-launch guard: this screen was opened by the worker or the
     * phone, not the wearer. Once no ladder stage needs attention, hand
     * the screen back to the watchface — suspension and charging are
     * ambient states (visible on the phone) and do not justify keeping
     * a screen the wearer never asked for. A nag launch is NOT stale:
     * it deliberately has no ladder stage. */
    if (s_auto_launched && !s_nag_hold && !s_lab_hold &&
        s_drill_hold_ticks == 0 && s_phone_grace_ticks == 0 &&
        stage == CM_STAGE_NONE) {
      DLOG("stale worker launch: no active stage, returning to watchface");
      vibes_cancel();
      if (s_alert_window) window_stack_remove(s_alert_window, true);
      window_stack_pop_all(false);
    }
  }
}

/* ---------- suspension menu ---------- */

static void suspend_minutes(uint16_t minutes, bool auto_resume) {
  s_nag_hold = false;
  text_layer_set_text(s_detail_layer, HINTS_TEXT);
  AppWorkerMessage m = {.data0 = minutes, .data1 = auto_resume ? 1 : 0};
  app_worker_send_message(WMSG_SUSPEND, &m);
  /* label confirmed by the worker's SUSPEND_STARTED action + status polls */
  snprintf(s_status_buf, sizeof(s_status_buf),
           auto_resume ? "Suspended %u min" : "Carry %u min", minutes);
  text_layer_set_text(s_status_layer, s_status_buf);
}

/* TODO(M1): replace with a real MenuLayer incl. custom duration.
 * Skeleton: UP cycles presets. */
static void up_click(ClickRecognizerRef ref, void *ctx) {
  static const uint16_t presets[] = {30, 60, 120};
  static int idx = 0;
  suspend_minutes(presets[idx], true);
  idx = (idx + 1) % 3;
}

/* Carry mode: timer-only suspension for deliberate off-wrist transport.
 * Holding the watch puts real skin on the optical sensor — a hand reads
 * as a pulse just like a wrist does (field finding 2026-08-29), so
 * auto-resume cannot be trusted while the watch is hand-carried. The
 * wearer knows why they suspended; give them the timer-only choice. */
static void up_long_click(ClickRecognizerRef ref, void *ctx) {
  suspend_minutes(120, false);
}

static void select_click(ClickRecognizerRef ref, void *ctx) {
  s_nag_hold = false;
  text_layer_set_text(s_detail_layer, HINTS_TEXT);
  AppWorkerMessage m = {0};
  app_worker_send_message(WMSG_USER_OK, &m); /* manual check-in / resume */
  text_layer_set_text(s_status_layer, "Checked in");
}

static void down_long_click(ClickRecognizerRef ref, void *ctx) {
  AppWorkerMessage m = {0};
  app_worker_send_message(WMSG_SOS, &m);
}

static void main_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click);
  window_single_click_subscribe(BUTTON_ID_UP, up_click);
  window_long_click_subscribe(BUTTON_ID_UP, 700, up_long_click, NULL);
  window_long_click_subscribe(BUTTON_ID_DOWN, 700, down_long_click, NULL);
}

/* ---------- main window ---------- */

static void main_window_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);

  /* guideline: long-running apps keep the time visible */
  s_status_bar = status_bar_layer_create();
  status_bar_layer_set_colors(s_status_bar,
      PBL_IF_COLOR_ELSE(GColorDarkGreen, GColorWhite),
      PBL_IF_COLOR_ELSE(GColorWhite, GColorBlack));
  layer_add_child(root, status_bar_layer_get_layer(s_status_bar));

  window_set_background_color(w,
      PBL_IF_COLOR_ELSE(GColorDarkGreen, GColorWhite));
  GColor fg = PBL_IF_COLOR_ELSE(GColorWhite, GColorBlack);

  s_status_layer = text_layer_create(
      GRect(0, STATUS_BAR_LAYER_HEIGHT + 14, b.size.w, 64));
  text_layer_set_font(s_status_layer,
                      fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  text_layer_set_text_alignment(s_status_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_status_layer, GColorClear);
  text_layer_set_text_color(s_status_layer, fg);
  text_layer_set_text(s_status_layer, "Monitoring");
  layer_add_child(root, text_layer_get_layer(s_status_layer));

  s_diag_layer = text_layer_create(
      GRect(0, STATUS_BAR_LAYER_HEIGHT + 78, b.size.w, 44));
  text_layer_set_font(s_diag_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_alignment(s_diag_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_diag_layer, GColorClear);
  text_layer_set_text_color(s_diag_layer, fg);
  layer_set_hidden(text_layer_get_layer(s_diag_layer), true);
  layer_add_child(root, text_layer_get_layer(s_diag_layer));

  s_detail_layer = text_layer_create(GRect(0, b.size.h - 62, b.size.w, 58));
  text_layer_set_font(s_detail_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_alignment(s_detail_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_detail_layer, GColorClear);
  text_layer_set_text_color(s_detail_layer, fg);
  text_layer_set_text(s_detail_layer, HINTS_TEXT);
  layer_add_child(root, text_layer_get_layer(s_detail_layer));
}

static void main_window_unload(Window *w) {
  status_bar_layer_destroy(s_status_bar);
  text_layer_destroy(s_status_layer);
  text_layer_destroy(s_diag_layer);
  s_diag_layer = NULL;
  text_layer_destroy(s_detail_layer);
}

/* While the app is open it heartbeats the phone directly. (In worker mode
 * with the app closed, phone-side liveness relies on DataLogging records +
 * BT connection events — documented v0.1 limitation.) */
static void app_tick(struct tm *tick_time, TimeUnits changed) {
  if (s_nag_hold && s_nag_hold_ticks && --s_nag_hold_ticks == 0) {
    s_nag_hold = false; /* the auto-launch guard hands the screen back */
    text_layer_set_text(s_detail_layer, HINTS_TEXT);
  }
  static uint16_t s_hb_seq = 0;
  if (s_drill_hold_ticks) s_drill_hold_ticks--;
#if CM_VIBE_DIAG
  if (s_vd_show_ticks) { s_vd_show_ticks--; vd_render(); }
#endif
  if (s_phone_grace_ticks) s_phone_grace_ticks--;
  /* Poll worker state so the status line stays truthful (suspension
   * countdown, auto-resume, ladder stage) — cheap worker IPC, no radio. */
  if (tick_time->tm_sec % 5 == 0) {
    AppWorkerMessage m = {0};
    app_worker_send_message(WMSG_STATUS_REQ, &m);
  }
  /* Covers the kill->launch race after a build-change worker restart,
   * and generally re-arms a dead worker while the app is open. */
  if (tick_time->tm_sec % 10 == 0) ensure_worker_running();
  if (tick_time->tm_sec == 0) { /* once a minute */
    DictionaryIterator *out;
    if (app_message_outbox_begin(&out) == APP_MSG_OK) {
      dict_write_uint8(out, MESSAGE_KEY_MSG_TYPE, PMSG_HEARTBEAT);
      dict_write_uint16(out, MESSAGE_KEY_HEARTBEAT_SEQ, ++s_hb_seq);
      dict_write_uint8(out, MESSAGE_KEY_WATCH_BATTERY,
                       battery_state_service_peek().charge_percent);
      app_message_outbox_send();
    }
  }
}

static void ensure_worker_running(void) {
  if (!app_worker_is_running()) {
    AppWorkerResult r = app_worker_launch();
    APP_LOG(APP_LOG_LEVEL_INFO, "worker launch: %d", (int)r);
  }
}

static uint32_t build_id(void) {
  const char *s = __DATE__ " " __TIME__;
  uint32_t h = 5381;
  while (*s) h = h * 33u + (uint8_t)*s++;
  return h;
}

/* Sideloading a new .pbw does NOT restart a running worker: it keeps
 * executing the OLD binary until killed (field finding 2026-08-27 — the
 * v0.4.0 worker survived the v0.4.1 install and ate the not-worn nag).
 * Detect a build change and force a worker restart; the periodic
 * ensure_worker_running() in app_tick covers the kill/launch race. */
static void ensure_worker_current(void) {
  uint32_t id = build_id();
  uint32_t stored = persist_exists(PK_BUILD_ID)
      ? (uint32_t)persist_read_int(PK_BUILD_ID) : 0;
  if (stored != id && app_worker_is_running()) {
    APP_LOG(APP_LOG_LEVEL_INFO, "new build %lu (was %lu): restarting worker",
            (unsigned long)id, (unsigned long)stored);
    app_worker_kill();
  }
  persist_write_int(PK_BUILD_ID, (int32_t)id);
  ensure_worker_running();
}

static void init(void) {
  s_debug = persist_exists(PK_DEBUG) ? (uint8_t)persist_read_int(PK_DEBUG) : 0;
  s_main_window = window_create();
  window_set_click_config_provider(s_main_window, main_click_config);
  window_set_window_handlers(s_main_window, (WindowHandlers){
      .load = main_window_load, .unload = main_window_unload});
  window_stack_push(s_main_window, true);
#if CM_VIBE_DIAG
  s_vd_show_ticks = 40;
  vd_render();
#endif

  app_message_register_inbox_received(inbox_received);
  app_message_open(256, 256);

  app_worker_message_subscribe(worker_message_handler);
  tick_timer_service_subscribe(SECOND_UNIT, app_tick);
  ensure_worker_current();

  /* Launched by the worker? Pick up the parked action immediately.
   * Phone launches (reconnect self-heal, worker re-arm) count as
   * auto-launches too: they exist to restart the worker, not to show
   * a screen — the guard returns to the watchface once status shows
   * nothing needs the wearer. */
  s_launched_by_worker = (launch_reason() == APP_LAUNCH_WORKER);
  s_auto_launched = s_launched_by_worker ||
                    launch_reason() == APP_LAUNCH_PHONE;
  if (launch_reason() == APP_LAUNCH_PHONE) s_phone_grace_ticks = 15;
  APP_LOG(APP_LOG_LEVEL_INFO, "launch reason=%d auto=%d",
          (int)launch_reason(), (int)s_auto_launched);
  if (s_launched_by_worker) {
    pickup_pending_action();
  }
  AppWorkerMessage m = {0};
  app_worker_send_message(WMSG_STATUS_REQ, &m);
}

static void deinit(void) {
  app_worker_message_unsubscribe();
  window_destroy(s_main_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
