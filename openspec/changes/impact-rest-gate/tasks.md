# Tasks

- [x] 1. Core: `impact_rest_min` (default 5), candidates dropped in
       `cm_accel_feed` when `last_sustained_ms` is older than it;
       `CM_CFG_IMPACT_REST_MIN` = 22 in the settings table; host tests
       (asleep then fall or knock stays silent, walking then fall still
       asks, a shorter rest does not gate, knob range). Watchapp 0.5.14.
- [x] 2. Companion: `WatchConfig.Field.IMPACT_REST_MIN`, knob on the Hard
       impact page, "what it detects" sentence. Preview 12.
- [x] 3. Spec delta on detector-ladder.
- [ ] 4. Owner verification: install 0.5.14 and preview 12; the Hard impact
       page shows "Ignored after resting for 5 min" acknowledged; a week of
       nights without an impact question; a knock after walking still asks.
