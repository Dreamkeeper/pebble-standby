# Tasks

- [x] 1. Core: `pulse_since_hold`, cleared when baselines reset, set by a
       valid reading, gates the pulse ladder; host test. Watchapp 0.5.12.
- [x] 2. Spec delta (suspension, MODIFIED "Expiry re-arms cleanly").
- [x] 3. Owner verification (2026-10-24: SELECT-resume off-wrist, 4.5 min with no reading, no check-in): suspend, take the watch off, resume with SELECT
       before putting it on, wear it: no "No pulse signal" before the first
       reading; the sensor nag after ten minutes if the strap is loose.
