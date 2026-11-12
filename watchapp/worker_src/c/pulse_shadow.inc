// Diagnostic build only. The observer never emits detector actions or changes HR cadence.
static DataLoggingSessionRef shadow_log;
static struct {
  uint32_t start, epoch, sample;
  uint16_t live, two, duration;
  uint8_t active, purpose, baseline, prior_ok, changed;
} sh;

static uint32_t shadow_sample(void) {
  return (uint32_t)health_service_aggregate_averaged(HealthMetricHeartRateRawBPM,
      0x5342, 0x5343, HealthAggregationSum, HealthServiceTimeScopeOnce);
}

static void shadow_record(uint8_t kind) {
  struct __attribute__((packed)) {
    uint32_t epoch, sample;
    uint16_t elapsed;
    uint8_t kind, purpose;
    uint16_t live, two;
  } r = {sh.epoch, sh.sample, (mono_ms()-sh.start)/1000, kind, sh.purpose, sh.live, sh.two};
  if (shadow_log) data_logging_log(shadow_log, &r, 1);
}

static void shadow_end(uint8_t reason) {
  if (sh.active) shadow_record(reason);
  sh.active = 0;
}

static void shadow_start(const cm_action *a) {
  shadow_end(4);
  sh.start = mono_ms();
  sh.epoch = time(NULL);
  sh.live = sh.two = UINT16_MAX;
  sh.duration = a->seconds;
  sh.purpose = a->detector;
  sh.baseline = s_core.last_bpm_value;
  sh.changed = sh.prior_ok = 0;
  sh.sample = shadow_sample(); // Entry snapshot cannot count towards recovery.
  sh.active = 1;
  shadow_record(0);
}

static void shadow_observe(bool live) {
  if (!sh.active) return;
  uint32_t age = (mono_ms()-sh.start)/1000;
  if (age >= sh.duration) { shadow_end(2); return; }
  if (live) sh.live = age;
  uint32_t v = shadow_sample();
  if (v >> 30 != 1 || v == sh.sample) {
    if (live) shadow_record(1);
    return;
  }
  uint8_t bpm = v;
  bool ok = !(v & 0x8000) && ((v >> 8) & 127) >= 80 && bpm >= s_core.cfg.pulse_min_bpm;
  bool adjacent = (((v >> 16) - (sh.sample >> 16)) & 0x3fff) == 1;
  bool changed = bpm != sh.baseline;
  if (ok && adjacent && sh.prior_ok && (sh.changed || changed) && sh.two == UINT16_MAX)
    sh.two = age;
  sh.prior_ok = ok;
  sh.changed = changed;
  sh.sample = v;
  shadow_record(1);
}
