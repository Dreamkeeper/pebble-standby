# End-to-end test procedure

Run top to bottom after any change to the watchapp, companion, or server.
Total time ~15 minutes plus one optional 35-minute soak. Prerequisites:
current .pbw and APK installed, phone enrolled, at least one contact with
Telegram, dashboard reachable.

Reading the phone notification: `watch ✓ 87% · synced 4m ago · server ✓`
- **watch ✓ / LINK DOWN** — live Bluetooth truth from the Pebble app.
- **synced N ago** — when the *watchapp* last spoke. It only can while
  open (the background worker cannot send messages — platform limit,
  documented in the protocol spec). A large value with `watch ✓` is
  normal in worker mode, NOT a fault. This resets when you open the
  watchapp (T1) and after any alert reaches the phone.
- **server ✓** — last heartbeat accepted by the server.

## T1 — Watch ↔ phone link (2 min)

1. Open Standby on the watch.
2. Within ~1 min the phone notification shows `synced …s ago` fresh, and
   watch battery appears.
3. Close the watchapp (BACK). The watchface returns. Expected: `synced`
   age now grows until the next alert or app-open — that is correct.

**Pass:** fresh sync while open; no FAULT notification while `watch ✓`.

## T2 — Full alarm chain: watch SOS → Telegram ACK (5 min)

1. On the watch, open the app and long-press DOWN (SOS). A 5 s countdown
   starts on the watch.
2. Let it expire. Expected within ~15 s:
   - phone: **siren sounds from the app itself** (no UI needed), plus:
     screen off/locked → the red alarm screen takes over; screen on and
     unlocked → an urgent "ALARM" heads-up notification (Android forbids
     apps stealing the screen you are using — tap it to open the alarm
     screen; the siren is already sounding either way),
   - Telegram: alert message with the ✅ acknowledge button,
   - Telegram: your `[copy to wearer]` self-notification,
   - dashboard fleet view: wearer top-ranked, `ESCALATING` — and it
     appears **without reloading**: fleet and wearer pages self-refresh
     every 15 s.
3. Press the Telegram ✅ button. Expected: popup confirmation AND the
   message edits itself — button gone, `✅ ACKNOWLEDGED` appended. The
   dashboard flips to `acknowledged` on its own within 15 s.
4. On the phone alarm screen press **I'M OK — CANCEL**, pick a cause.
   Expected: siren stops, watch alert clears, escalation resolves
   (dashboard shows no active escalation; Audit shows ack + resolve).

**Pass:** every step; nothing requires SSH, curl, or a manual page reload.

> Xiaomi/HyperOS: for the lock-screen takeover, use the in-app button
> **"Allow alarm over the lock screen"** (main screen → Setup) — it
> opens the right permission page; enable *"Show on lock screen"* and
> *"Display pop-up windows while running in the background"*. The siren
> and heads-up work without them.

## T3 — Ghost-launch regression (3 min)

**The watch must stay ON your wrist for this test.** Taking it off does
NOT trigger the alarm ladder — an off-wrist watch routes to the
*not-worn* path (T3b below), which deliberately never alerts contacts
(a watch on the nightstand must not page your response group).

1. Keep the watch worn and sit still until a non-motion or pulse
   check-in fires ("Are you OK?" with vibration), OR provoke pulse-loss:
   keep the watch on but slide a finger under the sensor for ~3 minutes
   while sitting still (blocks the optical sensor; stillness + no pulse
   signal starts the silent hunt, then the check-in).
2. When the check-in vibrates, move your wrist deliberately.
3. Expected: alert dismisses itself AND the watch returns to your
   watchface. The app must NOT remain foreground showing "Monitoring".

**Pass:** watchface visible after implicit cancellation, no ghost screen.

## T3b — Not-worn nag (≈4 min)

1. Take the watch off normally (unbuckle, set it on a table). Do not
   suspend. The handling motion right after your last pulse reading is
   what tells the watch "removed, not arrested" — so no alarm ladder.
2. After **~3–4 minutes**: the watch vibrates and shows "Not worn?",
   and the phone posts a FAULT-channel notification saying monitoring
   is blind. No Telegram, no escalation.
3. Put the watch back on (or press UP to suspend properly). The nag
   clears on the next pulse reading.

**Pass:** nag within ~4 min on both watch and phone; contacts never
hear about it.

## T3c — Still with a live pulse stays silent (passive, evening-length)

Wear the watch and be still for 45+ minutes (TV, reading). Expected:
**nothing** — no check-in, no vibration. A visible pulse is proof of
life; stillness alone never pings on HR hardware.

## T4 — Suspension (3 min)

1. Watchapp → UP button: suspend 30 min. Expected within ~10 s: the
   phone notification gains a `SUSPENDED 30m ·` prefix; the watch shows
   "Suspended 30 min" and holds it a full minute before "29" (minutes
   round up). Nothing you do in the **first 60 s** can resume it — the
   arming grace absorbs strap handling and table placement.
2. Take the watch off for ~2 min, put it back on and **walk for at
   least 15–20 seconds continuously**. Auto-resume is
   accelerometer-only: pulse readings never resume (the optical sensor
   phantom-reads against surfaces).
3. Expected: double-pulse vibration; watch status returns to
   "Monitoring"; the phone's `SUSPENDED` prefix disappears.

## T5 — Phone-silent advisory (optional 35-min soak)

1. Enable airplane mode on the phone WITHOUT declaring an offline window.
2. After ~30 min the server marks the wearer SILENT and escalates an
   ADVISORY (Telegram to contacts, clearly worded as advisory).
3. Disable airplane mode; acknowledge/resolve from Telegram or the
   dashboard.

**Pass:** advisory wording (not a confirmed alarm), single advisory (no
duplicates), recovery after reconnect.

## T6 — Fire drill from each surface (2 min)

Run a TEST drill from: the app (Contacts → Fire drill), the dashboard
(wearer page → Fire drill). Both must deliver `[TEST]`-tagged messages
and be resolvable from the dashboard with a typed reason.

## Known-limitation checklist (expected "failures")

- `synced` age grows while the watchapp is closed — by design until the
  DataLogging path (M0 spike S5) lands.
- No watch-side alert if the worker was evicted by another background
  app — check `Settings → Background App` on the watch after installing
  other apps.
- Email/ntfy channels silently skip unless configured in `.env`.
