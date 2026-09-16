# Design

## D1. Rename the surface, keep the plumbing

Everything a person reads changes: app label, notification titles,
escalation and bot texts, dashboard, watchapp display name, README,
doc titles, OpenSpec context, dist filenames, repository name. Nothing
a machine keys on changes: Android `applicationId`/package, watchapp
UUID, server module/logger/container names, keystore paths,
notification channel ids, SharedPreferences keys. Rationale: a
package-id change is a fresh install for every current user (lost
enrollment, contacts, settings, soak history) and a signing/Play
Protect re-evaluation, for no user-visible benefit; "org.cryomonitor"
is invisible outside developer tools.

## D2. Cryonics stays the flagship story

The segment is unchanged. The README leads with the Job and the
one-liner, then says who it was built for first and why that matters
(minutes to reach a standby team), then generalises. The name carries
the outcome ("Standby": someone is standing by; also the cryonics
term), not a mechanism, and makes no vital-sign or medical claim.

## D3. Repository

`gh repo rename pebble-standby`: GitHub redirects the old URL and
`git` remotes, so upstream PR descriptions and forum links keep
working; the `gh` CLI updates the local remote.
