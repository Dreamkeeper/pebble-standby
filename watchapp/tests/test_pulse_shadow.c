/* Replay exactly the emery observer with a fake clock, sensor and log sink. */
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <time.h>
#include "../src/core/detectors.h"
typedef int DataLoggingSessionRef;
enum { HealthMetricHeartRateRawBPM, HealthAggregationSum, HealthServiceTimeScopeOnce };
static cm_core s_core;
static unsigned clock_ms, packed, logs;
static uint32_t mono_ms(void) { return clock_ms; }
static uint32_t health_service_aggregate_averaged(int m,int a,int b,int c,int d) {
  assert(m==HealthMetricHeartRateRawBPM && a==0x5342 && b==0x5343 &&
         c==HealthAggregationSum && d==HealthServiceTimeScopeOnce);
  return packed;
}
static void data_logging_log(int session, const void *record, int n) {
  assert(session && record && n==1); ++logs;
}
#include "../worker_src/c/pulse_shadow.inc"

static void sample(unsigned seq,unsigned bpm,unsigned conf,bool off,bool live) {
  packed=(1u<<30)|(seq<<16)|(off<<15)|(conf<<8)|bpm;
  clock_ms+=1000;
  shadow_observe(live);
}
int main(void) {
  cm_action a={.seconds=45,.detector=CM_DET_PULSE};
  shadow_log=1;
  s_core.last_bpm_value=60;
  s_core.cfg.pulse_min_bpm=25;
  shadow_start(&a);
  sample(16382,65,95,false,true);
  assert(sh.live==1 && sh.two==UINT16_MAX);
  sample(16382,65,95,false,false); // cached
  assert(sh.two==UINT16_MAX);
  sample(16383,65,95,true,false);
  sample(0,65,79,false,false);
  sample(1,65,95,false,false);
  sample(3,65,95,false,false); // missing intervening observation
  assert(sh.two==UINT16_MAX);
  sample(4,64,80,false,false);
  assert(sh.two==7 && sh.live==1);
  shadow_start(&a);
  sample(16383,60,99,false,false);
  sample(0,60,99,false,false); // two credible but no change
  assert(sh.two==UINT16_MAX);
  sample(1,61,99,false,false);
  assert(sh.two==3 && sh.live==UINT16_MAX);
  shadow_start(&a);
  clock_ms+=45000; shadow_observe(false);
  assert(!sh.active && sh.two==UINT16_MAX);
  assert(logs>0);
}
