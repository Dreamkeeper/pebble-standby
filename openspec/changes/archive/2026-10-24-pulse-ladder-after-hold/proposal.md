# The pulse ladder waits for a reading after a hold ends

## Why

Field 2026-10-12 07:30: the wearer suspended for a shower (UP), put the
watch back on and pressed SELECT to resume. The sensor produced no
reading for six minutes (wet wrist). When the alarm clock made the
wearer stand still, the pulse ladder hunted and asked "No pulse signal".
The cause is in the resume itself: it resets `last_pulse_ms` to "now" so
nothing fires instantly, which also makes the watch count as worn for
the next ten minutes although no reading has arrived. The suspension
spec already says that silence after an expiry belongs to the not-worn
nag, never to a contact alarm; the test only covered the first minute.

## What changes

- The core tracks whether a valid reading has arrived since the last
  hold ended (resume, expiry, unplug). Until it has, the pulse ladder
  stays disarmed; the not-worn and sensor nags cover the silence as they
  do for a watch never worn since restart.
- Watchapp 0.5.12; host test replays the morning.
