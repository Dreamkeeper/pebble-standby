# Tasks

- [x] 1. Watch: `PMSG_SUSPEND_SET` (minutes, 0 = resume; auto-resume flag),
       the "until worn again" preset, labels. Watchapp 0.5.11 in `dist/`.
- [x] 2. Phone: `ACTION_PAUSE` / `ACTION_RESUME_PAUSE`, persisted pause and
       pending watch message, re-send at watchapp open, end on watch
       resume or horizon, offline window + cancel, `suspended_until` in
       the heartbeat, faults quiet while paused, `plannedPause` in the
       coverage facts, Pause sheet on Home. JVM tests.
- [x] 3. Server 0.3.4: offline window duration 0 cancels, bounds, state in
       the response, dashboard label; pytest. Deployed to the NAS.
- [x] 4. OpenSpec deltas: suspension, watch-phone-protocol,
       escalation-and-deadman.
- [ ] 5. Owner verification: pause "until tomorrow morning" with the watch
       connected (watch shows "Until worn again", dashboard "paused by
       wearer", Home "Paused until tomorrow 08:00"); put the watch on and
       move for a minute: Home back to Covered, dashboard badge gone.
