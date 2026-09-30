/*
 * Shared worker <-> app <-> phone protocol constants.
 * Included by src/c (app), worker_src/c (worker), and mirrored in the
 * Android companion (Protocol.kt — keep in sync).
 */
#ifndef CM_PROTOCOL_H
#define CM_PROTOCOL_H

/* Persist keys (worker and app share the app's persist storage). */
enum {
  PK_CONFIG = 1,          /* cm_config blob */
  PK_PENDING_ACTION = 2,  /* cm_action awaiting foreground app pickup */
  PK_MODE = 3,            /* 0 = worker mode, 1 = persistent foreground mode */
  PK_SUSPEND_UNTIL = 4,   /* epoch seconds; survives worker restart */
  PK_SUSPEND_AUTORESUME = 5,
  PK_DEBUG = 6,           /* 1 = extensive APP_LOG output (app + worker) */
  PK_DRILL_FIRE_MS = 7,   /* wall-clock ms when the worker fired the latency
                             drill (worker and app share the clock) */
  PK_DRILL_ARM_MS = 8,    /* wall-clock ms when the worker was armed; lets the
                             phone subtract ALL watch-side time (arm->result)
                             instead of guessing the countdown duration */
  PK_BUILD_ID = 9,        /* hash of the app build: a running worker survives
                             a sideload executing the OLD binary — the app
                             kills and relaunches it when the build changes */
  PK_PENDING_ACTION_T = 10, /* epoch seconds the pending action was parked:
                               the app discards stale parked actions instead
                               of replaying yesterday's nag after a reboot */
  PK_QMETRIC = 11,        /* 0/1: raw-quality metric available (diag fw) —
                             worker gates liveness on quality >= Acceptable */
  PK_VIBE_DIAG_SAMPLES = 13, /* CM_VIBE_DIAG builds only: did_vibrate-flagged
                                accel samples seen by the worker, total */
  PK_VIBE_DIAG_BURSTS = 14,  /* ... batches (seconds) holding >=1 flagged sample */
  PK_VIBE_DIAG_LAST_T = 15,  /* ... epoch seconds of the last flagged batch */
  PK_EPISODE_SEQ = 12     /* last minted ladder episode id — persisted so
                             episode identity survives worker restarts */
};

/* AppWorkerMessage types (uint8). data0/data1/data2 per type. */
enum {
  WMSG_ACTION = 1,        /* worker->app: data0=cm_action_type, data1=detector, data2=seconds */
  WMSG_USER_OK = 2,       /* app->worker */
  WMSG_SUSPEND = 3,       /* app->worker: data0=minutes, data1=auto_resume */
  WMSG_RESUME = 4,        /* app->worker */
  WMSG_SOS = 5,           /* app->worker */
  WMSG_STATUS_REQ = 6,    /* app->worker: request status push */
  WMSG_STATUS = 7,        /* worker->app v2: data0 = stage|det<<3|charging<<7
                             |bpm<<8; data1 = episode; data2 = stage-secs or
                             suspend-min (cap 255) | heap64<<8 */
  WMSG_SET_DEBUG = 8,     /* app->worker: data0 = 0/1 */
  WMSG_DRILL = 9,         /* app->worker: run the S1 latency drill — wait
                             CM_DRILL_DELAY_S, then fire a synthetic
                             worker_launch_app() alert */
  WMSG_HR_LAB = 10,       /* app->worker: data0 = 1/0 — S4 sensor lab:
                             burst HR sampling + silent detector hold */
  WMSG_HR_SAMPLE = 11,    /* worker->app (lab only, every 2 s):
                             data0 = raw peek bpm, data1 = free heap / 64 B,
                             data2 = seconds since last HR event */
  WMSG_SET_QMETRIC = 13,  /* app->worker: data0 = 0/1 — quality metric OK */
  WMSG_VIBE = 14,         /* app->worker: data0 = motor duration ms; sent
                             BEFORE every vibes_* call so the worker can
                             ignore its own buzz (cm_vibe_guard) */
  WMSG_DIAG = 12          /* worker->app (debug only, with each status):
                             data0 = s since last bpm CHANGE (cap 9999),
                             data1 = s since last motion (cap 9999),
                             data2 = CM_DIAG_* flag bits */
};

/* WMSG_DIAG / heartbeat-record flag bits */
#define CM_DIAG_CHARGING   0x01
#define CM_DIAG_LAB_HOLD   0x02
#define CM_DIAG_HUNTING    0x04
#define CM_DIAG_NAGGED     0x08
#define CM_DIAG_EVER_PULSE 0x10
#define CM_DIAG_SUSPENDED  0x20

/* DataLogging heartbeat: tag 0xC202, 16-byte v3 record (v2 was 14 bytes,
 * v1 8 bytes under 0xC201; the phone parses all by size). v3 adds the
 * episode id and packs detector into the stage byte high nibble. */
#define CM_DL_TAG 0xC202

/* MSG_TYPE values for AppMessage to/from the phone. */
enum {
  PMSG_HEARTBEAT = 1,     /* watch->phone: periodic liveness + battery + bpm */
  PMSG_PRE_ALARM = 2,     /* watch->phone: countdown started (phone starts its own siren) */
  PMSG_ALARM = 3,         /* watch->phone: ladder exhausted — escalate */
  PMSG_CANCEL = 4,        /* watch->phone: alert cancelled (reason attached) */
  PMSG_SUSPENDED = 5,     /* watch->phone: suspension state changed */
  PMSG_CONFIG = 6,        /* phone->watch: cm_config blob push */
  PMSG_CONFIG_ACK = 7,    /* watch->phone */
  PMSG_USER_OK_REMOTE = 8,/* phone->watch: user cancelled on the phone */
  PMSG_SET_DEBUG = 9,     /* phone->watch: SECONDS key carries 0/1 */
  PMSG_NOTWORN = 10,      /* watch->phone: off-wrist nag (wearer-only, never contacts) */
  PMSG_DRILL = 11,        /* phone->watch: start the S1 latency drill */
  PMSG_DRILL_RESULT = 12, /* watch->phone: SECONDS = worker-fire -> app-alive ms */
  PMSG_CHARGING = 13,     /* watch->phone: SECONDS 1 = on charger (implicit
                             hold), 0 = unplugged (monitoring resumed) */
  PMSG_HR_LAB = 14,       /* phone->watch: SECONDS 1/0 — start/stop the S4
                             sensor lab (forwarded to the worker) */
  PMSG_HR_SAMPLE = 15,    /* watch->phone: SECONDS = raw bpm, HEARTBEAT_SEQ =
                             seconds since last HR event, DETECTOR = heap/64 */
  PMSG_SENSOR_FAULT = 16, /* watch->phone: no pulse signal while motion
                             continues — sensor dead or carried off-wrist
                             (wearer-only FAULT, never contacts) */
  PMSG_SET_QMETRIC = 17,  /* phone->watch: SECONDS 0/1 — the hr-quality-diag
                             firmware is installed, so the worker may gate
                             liveness on the raw-quality metric (lab data
                             2026-08-29: worn floor = Acceptable; all
                             off-body conditions read OffWrist) */
  PMSG_ALARM_ACK = 18     /* phone->watch: SECONDS = episode id — app-level
                             delivery ACK for PRE_ALARM/ALARM/CANCEL; the
                             watch retries until this arrives (D2) */
};

/* S1 latency drill: the worker waits this long after the app closes before
 * firing, so the measured launch is a genuine cold start. */
#define CM_DRILL_DELAY_S 10

/* Heartbeat cadence (watch -> phone) while connected. */
#define CM_HEARTBEAT_INTERVAL_S 60

/* Normal vs burst HR sampling period (seconds). */
#define CM_HR_PERIOD_NORMAL_S 60
/* While suspended or on the charger the sensor only feeds auto-resume /
 * expiry re-arm, so one sample per 5 min is enough (owner power request
 * 2026-09-07). Auto-resume may lag by up to one period; SELECT resumes
 * instantly. Restored to NORMAL on resume/expiry/unplug. */
#define CM_HR_PERIOD_HOLD_S 300
#define CM_HR_PERIOD_BURST_S 1

#endif /* CM_PROTOCOL_H */
