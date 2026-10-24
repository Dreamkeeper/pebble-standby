# Standby beta: tester kit

Standby is a watch that asks "Are you OK?" when something looks wrong,
counts down, and then tells the people you chose. It never calls an
ambulance by itself. This page is everything a beta tester needs: what
to buy, how to set it up, what to do each day, what to send back, and
what Standby cannot do.

Read the last section first. If the limits are a problem for you, do
not buy a watch.

## 1. What it cannot do

- **It is not a medical device** and does not detect cardiac arrest or
  any condition. It notices four things: the pulse signal gone while you
  lie still, a hard impact followed by stillness, no movement for a long
  time, and a missed scheduled check-in. Each one first asks you on the
  wrist.
- **Nobody knows how often it would catch a real event.** It has caught
  none, because none has happened to a wearer. What we can measure is
  the other side: how often it speaks when nothing is wrong. That record
  is published per wearer (section 6) and is the point of the beta.
- **The phone must be on, charged, and within Bluetooth range** (about a
  room away). The watch cannot reach anyone on its own. If the phone
  goes silent, the server notices within minutes and tells your
  contacts that the phone is unreachable, which is not the same as
  telling them you are.
- **It will ask you questions you did not need.** Currently about one a
  day for the one long-term wearer, falling as causes are found. You
  dismiss a question by moving, or with one button press. Every false
  question you report makes the next version quieter.
- **Android only.** There is no iPhone companion.

## 2. Hardware and accounts

| Item | Notes |
|---|---|
| Pebble watch with a heart-rate sensor: **Pebble Time 2** or **Pebble 2 HR** | Pebble 2 Duo and Pebble Round 2 run the app too, but without the pulse detector; impact, stillness and check-ins only. |
| Android phone, Android 8 or newer | Tested on Android 16 (Xiaomi HyperOS). Xiaomi, Huawei and Samsung need extra "let it run" permissions; the app tests them for you. |
| The Pebble mobile app from Core Devices | Current version from the Play Store. Pair the watch with it first. |
| Telegram on your responders' phones | Alerts go out as Telegram messages with an acknowledge button. Email and ntfy also work. |
| A Standby server | For the beta you get an enrolment code for Dmitry's server. Self-hosting is documented in `docs/DEPLOY-VPS.md` and `docs/DEPLOY-SYNOLOGY.md` if you prefer your own. |

Two or three responders who agree to answer a Telegram message at
night. Family first. A standby organisation can be a later tier.

## 3. Install (about 20 minutes)

1. **Watch.** Open the `.pbw` from `dist/` (latest `standby-v*.pbw`) on
   the phone; the Pebble app offers to install it. Open Standby on the
   watch once. The watchface returns; monitoring runs in the background
   from then on.
2. **Companion.** Install the latest `standby-companion-*.apk` from
   `dist/` (allow installs from your browser or file manager when
   asked). Open it.
3. **Enrol.** Enter the server address and the one-time enrolment code
   you were given. The app says "connected". It never shows your token.
4. **Contacts.** Add each responder with a name and their Telegram chat
   id (the app explains how they get it from the bot). Put yourself in
   as the self-copy so you see what they see.
5. **Let it run.** The permissions page lists what the phone must allow:
   run without battery limits, show the alarm over the lock screen,
   exact timers, notifications, and on Xiaomi also pop-ups in the
   background and start after reboot. Tap **Test** on each row; the app
   checks the permission for real, not by asking the system what it
   claims.
6. **Fire drill.** From Contacts, run the test alert once. Every
   responder should receive the message and one should press
   acknowledge. Do this once more at your normal bedtime during the
   first week: "does it wake a human through Do Not Disturb" is part of
   the test.

## 4. Living with it

- Wear the watch day and night. Charging is a pause; the app knows.
- Taking it off for a shower or sport: press the watch's pause, or
  **Pause** on the phone's Home screen for longer activities. "Until worn
  again" ends the pause by itself when the watch sees you move with a
  pulse.
- When the watch vibrates and asks "Are you OK?": moving dismisses most
  questions; an impact question needs a button press. If you do not
  answer, a countdown follows on the watch and the phone; only then are
  contacts told. Cancel on the phone retracts everything and asks you
  why, in one tap.
- Once a day, two minutes: glance at Home. If it asked you anything,
  note the time and what you were doing (asleep, shower, desk, car).

## 5. What to send back, and how

- **Every question or alarm you did not need:** the time, and one line
  on what you were doing. A message is enough.
- **A diagnostics bundle when asked.** The server can ask your phone for
  logs; your phone shows a notification and sends nothing until you tap
  **Send**. The bundle holds the watch's minute records (pulse present
  or not, movement, battery, stage) and the app's log, with nothing
  from the rest of your phone. You can also share logs yourself from
  Settings → Diagnostics.
- **Anything that felt wrong:** a question that woke you, a pause that
  did not end, a phone that said "watch not reached" while the watch
  was on your wrist, an alert a responder did not get.

## 6. The quiet record

Every tester gets a table like this, generated from their own logs with
`tools/quiet_record.py` and refreshed as the beta runs. This is the
first wearer's, as of 2026-10-24:

| Month | Days with logs | Minute records | Worker days | Nights watched | Questions on the wrist | Countdowns on the phone | Alarms | Sent to contacts |
|---|---|---|---|---|---|---|---|---|
| 2026-08 | 13 | 4943 | 3.4 | 3 | 22 | 0 | 0 | 0 |
| 2026-09 | 30 | 43161 | 30.0 | 30 | 33 | 0 | 0 | 0 |
| 2026-10 | 24 | 34165 | 23.7 | 24 | 17 | 2 | 0 | 0 |
| **Total** | 67 | 82269 | 57.1 | 57 | 72 | 2 | 0 | 0 |

"Questions on the wrist" are the check-ins the wearer saw and
dismissed. "Countdowns on the phone" are the ones that got as far as
the phone's alarm screen before being cancelled. "Sent to contacts" is
the number that matters most to a responder, and the number the beta
exists to keep at zero while the watch still asks when it should.

## 7. Privacy

The server stores your name, your contacts, a heartbeat from the phone
every few minutes (battery, whether the watch is reached, whether you
are paused), and the events: questions, countdowns, alarms, and how
they ended. It does not receive your pulse values or movement unless you
send a diagnostics bundle, and bundles expire on the server after a set
number of days. The companion never backs up its data to Google or to
another phone. Your token lives only on your phone.

## 8. Commitment

Four weeks of wearing the watch at night, the phone within range, one
fire drill in week one, and a message when something was wrong. In
return you get the record above for yourself, and a say in what the
watch asks and when.
