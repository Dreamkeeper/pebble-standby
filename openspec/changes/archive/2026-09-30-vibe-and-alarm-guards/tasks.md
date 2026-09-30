# Tasks

- [x] 1. Core: `cm_vibe_guard()`, impact excluded from motion dismissal;
       host tests (announced buzz blocks motion and shocks; impact
       check-in needs a button; pulse check-in still motion-dismissed).
- [x] 2. Protocol `WMSG_VIBE`; app announces before every `vibes_*`
       call through `buzz_*` helpers.
- [x] 3. Worker: handle `WMSG_VIBE`; alarm-clock peek every 10 s with
       the 15 s / 120 s window.
- [x] 4. Version 0.5.8, build with SDK 4.33.1, `dist/` pbw, README,
       SOAK-TEST note; memory.
- [ ] 5. Owner verification: sideload 0.5.8; tomorrow's alarm produces
       no impact check-in; a deliberate desk shock followed by stillness
       opens a check-in that only SELECT dismisses.
