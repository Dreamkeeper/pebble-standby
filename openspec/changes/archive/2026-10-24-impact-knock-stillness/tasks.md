# Tasks

- [x] 1. Core: `shock_immobile_s` (default 120), `impact_bare` set by the
       crash branch, `tick_impact` picks the window; `CM_CFG_SHOCK_IMMOBILE_S`
       = 21 in the settings table; host tests (bare shock waits, freefall
       keeps 60 s, movement inside the longer window stays silent, knob
       range). Watchapp 0.5.10 in `dist/`.
- [x] 2. Companion: `WatchConfig.Field.SHOCK_IMMOBILE_S`, knob on the Hard
       impact page, row value line; `toSend(afterOpen)` re-sends changed
       fields at every watchapp open.
- [x] 3. Docs: DESIGN §4.5 row, watchapp README table, spec delta.
- [x] 4. Owner verification (2026-10-24: knock, still, "Are you OK?" at 2 min 3 s, SELECT): install 0.5.10, open Hard impact on the phone
       and confirm the knock knob acknowledges; knock the desk and stay
       still for a minute, no check-in before two.
