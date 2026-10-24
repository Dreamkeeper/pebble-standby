# The watch's reports are observed, not configured

## Why

The "Pebble app mode" setting (auto / patched / stock) told the phone
whether its Pebble app forwards the worker's DataLogging records. The
stock Core app forwards them since PebbleKit2 1.3.0 (mobileapp#378,
merged 2026-09), there is no patched app any more, and the only fact
the setting stood for — have worker records ever arrived? — is observed
directly. Owner decision 2026-10-11: drop the setting, keep the
automatic behaviour, say what it means in the wearer's words.

## What changes

- `PebbleAppPolicy` keeps one input, `dlEverSeen`; the mode enum, the
  parser and the stored `pebble_app_mode` are removed.
- Settings → Pebble app shows the observed state ("The watch reports
  every minute" / "No reports from the watch yet, checking hourly") and
  keeps the sync-interval field; the Patched / Stock chips are gone.
- The notification, the soak report and the Debug line say "sync only"
  instead of "store app"; docs and READMEs follow.
- Also in this build: "Pause monitoring" on Home is a filled tonal
  button, and DESIGN §3.5 records the emphasis rule behind it.
