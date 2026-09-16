# Rename to Standby; position for "call my people, not an ambulance"

## Why

Moving to beta means recruiting testers beyond the (tiny) intersection
of Pebble owners and cryonicists. The name "Pebble Cryonics Monitor"
says *who*, not *what*: to a Pebble owner browsing the store it reads
"not for me". The alternative considered, "Lifesign Monitor", names a
mechanism and invites a clinical prediction the product must not make.

Per the segmentation canon the segment stays what it is — people whose
Core Job is "have the people I choose know within minutes if I become
unresponsive", with cryonicists as the first and most motivated
sub-segment (their standby organisation is the last escalation tier).
Only the communication widens: the name carries the Job, the cryonics
story stays the flagship rather than one item in a list of
applications.

## What changes

- Product name **Standby**; one-liner: "an unresponsiveness alarm for
  Pebble — if your pulse signal and movement stop, the people you
  choose are alerted within minutes; no automatic 911."
- User-visible strings renamed: Android app label and notifications,
  escalation texts, log/soak exports, server dashboard/e-mail/ntfy
  titles and bot text, watchapp display name, README and doc titles,
  OpenSpec context. GitHub repository renamed (old URLs redirect).
- **Machine identifiers kept**: Android `applicationId` and Kotlin
  package (`org.cryomonitor.companion`), watchapp UUID, server module,
  logger and container names, keystore paths, notification channel ids.
  A package-id change would be a fresh install (lost enrollment,
  contacts, soak history) for every current user for zero user-visible
  benefit.

## Impact

- Specs: `product-requirements` (ADDED "Product identity and
  positioning").
- Releases: watchapp 0.5.7, companion 0.6.4 (label), server 0.3.1
  (titles) — all cosmetic; installs update in place.
