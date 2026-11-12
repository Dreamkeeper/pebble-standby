## Context
Current recovery requires a changed BPM accepted by cm_hr_feed. Legacy public reads can repeat a cached sample and quality band 2 represents scores from 80. Only a coherent raw snapshot can distinguish callbacks from new measurements.

## Decisions
Firmware patch (firmware.patch against 0659dc817) publishes one 32-bit snapshot under the existing activity lock on each BPM event, even rejected events. Bits 0–7 BPM, 8–14 confidence, 15 off-wrist, 16–29 wrapping sensor sequence, 30 valid. Sequence identifies acquisition; elapsed time in the worker is observation time to the nearest second. No wall-clock freshness inference. The fork reserves the otherwise unsupported raw BPM Sum query for the fixed interval 0x5342..0x5343, scope Once. Earlier firmware returns zero for that combination. No probe of unknown metric IDs.
 
Build the worker with CM_DEFINES=CM_PULSE_SHADOW=1 (SDK 4.33.1). On emery only, capture baseline BPM and discard the cache present at burst start. Two adjacent, previously unseen BPM events must both be on-wrist, confidence >=80 and at least the current minimum BPM; at least one must differ from baseline. Repeated reads cannot confirm, gaps break a pair, sequence wrap is handled modulo 16384. Capture actual live pulse recovery from the change in core hunt state across cm_hr_feed, including the existing quality-gate policy as configured. Stop on interruption or deadline. Following a live recovery, observe any naturally arriving samples up to the original deadline without prolonging the burst. Log delivery is best effort: missing records and post-stop sparse samples limit conclusions.
 
## Validation and rollout
Use the isolated host replay and the detector suite, verify slot manifests and signed APK, then follow docs/PULSE-SHADOW-FIELD.md. Companion first, diagnostic firmware for the active hardware, then PBW. Reinstall the earlier PBW to stop collection.

## Firmware source for the published bundles
Start with upstream PebbleOS tag v4.46.0 and its pinned submodules. Apply firmware-base.patch first (the Standby patches through 0659dc817), then firmware.patch (the diagnostic changes through 115381203). Build the normal release for obelix@pvt with CONFIG_FIRMWARE_SLOT set to 0 and 1 in separate build directories. A rebuild from applied patches can have a different Git version string and timestamp.
