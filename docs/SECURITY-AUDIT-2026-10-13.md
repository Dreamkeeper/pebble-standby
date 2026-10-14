# Security audit, 2026-10-13 (harden-ci stage 1)

Method: the 16 steps of [harden-ci](https://github.com/SecH0us3/harden-ci),
applied as a checklist by Claude. Nothing was changed during the audit;
every statement below is backed by a command run on 2026-10-13 or a file
in the repository. ❓ marks what could not be verified from here. The
dashboard header fix shipped the same day (server 0.3.5) is noted where it
closes a finding.

## 1. Visibility

**The repository is public:** the code, the full git history, issues, and
anything in `dist/` are available to anyone on the internet. Commits
carry the author's name and e-mail. Secret scanning and push protection
are on at GitHub; one past alert (a Telegram token sample in a test) is
resolved as "used in tests". No secret shape was found in the history by a
pattern pass (Telegram, AWS, GitHub, OpenAI, Slack tokens, private keys).

## 2. Main branch protection

Working mode: **personal repository**. One collaborator with write access,
all 123 commits by one author.

| Requirement | Status |
|---|---|
| 1. No direct push to `main`, changes through PRs | ❌ no branch rules at all (`rulesets` empty, `protection` 404) |
| 2. Review by another person | – one developer |
| 3. Approvals reset on new commits | – one developer |
| 4. Required blocking checks | ❌ there is no CI to require |
| 5. Rules apply to administrators, no bypass | ❌ no rules |
| 6. No force push, no branch deletion | ❌ no rules |
| 7. Signed commits, unsigned rejected | ❌ the last 12 commits are unsigned (`%G?` = N) |

Compensating measures for one developer: AI review of PRs – not in place
(no PRs are used); self-review of the diff in the PR interface – not in
place; account protection – ❓ (question 3); deployment wait timer – not
applicable (manual deploy from this laptop); auto-merge for bots – not
applicable. **When a second person with write access appears, turn on
required review with approval reset the same day.**

## 3. Application inventory

| Path | Kind | Language, package manager | Built by | CI job |
|---|---|---|---|---|
| `watchapp/` | Pebble Time 2 watchapp (worker + app) | C, Pebble SDK 4.33.1 in WSL | `pebble build` → `.pbw` sideloaded via the Pebble app | none |
| `android/` | Android companion | Kotlin, Gradle 9.6.1 (global install, no wrapper), AGP 9.3.1 | `gradle assembleSideloadRelease` → signed APK sideloaded | none |
| `server/` | Web API + operator dashboard | Python 3.12, pip (`requirements.txt`, version ranges) | `Dockerfile` + `docker-compose.yml` on the NAS; `docker-compose.vps.yml` + Caddy variant | none |
| `tools/` | Diagnostics scripts | Python | run by hand | none |
| `openspec/`, `docs/` | Specs and documentation | Markdown | – | none |

## 4. Threat model, in short

- **Server** (`cm.dkvasnikov.ru`, internet-facing behind the Synology
  reverse proxy with TLS). Handles the wearer's liveness, alarm state,
  contacts' Telegram ids and e-mails, operator accounts, and the
  diagnostics bundles the wearer sends (redacted on the phone). Entry
  points: the wearer API (bearer token), the operator dashboard (session
  cookie, CSRF), Telegram polling (outbound). A compromise means false or
  suppressed alarms and exposure of contacts' details. Runtime: Python
  3.12.14 on Debian 13 slim, FastAPI 0.141.1, Starlette 1.6.0, uvicorn
  0.52.3, all current.
- **Android companion.** Holds the server token and, optionally, a
  fallback Telegram bot token; talks to the server and to the watch via
  the Pebble app. Sideloaded, signed with the owner's key. A malicious APK
  in `dist/` would reach whoever installs from the repo.
- **Watchapp.** No network of its own; receives settings from the phone.
  Risk is wrong alarms, not data.
- **Build pipeline.** There is none; the laptop is the pipeline. A
  compromised GitHub account or laptop can change what is shipped.

## 5. Summary matrix

| Application | Unit tests | SAST | Dependencies | Secrets | Pinning | Image: no root | Image: scan | Image: sign + SBOM | IaC | API + DAST | Production settings |
|---|---|---|---|---|---|---|---|---|---|---|---|
| watchapp | ⚠️ 262 checks, local only | ❌ | – (SDK only) | ⚠️ GitHub only | ⚠️ SDK by version, no hash | – | – | – | – | – | ✅ release build |
| android | ⚠️ 52 tests, local only | ❌ | ⚠️ versions pinned, no lock/verification, no scan | ⚠️ GitHub only | ⚠️ no wrapper, no hashes | – | – | – | – | – | ⚠️ cleartext permitted by design (q. 5) |
| server | ⚠️ 94 tests, local only | ❌ | ⚠️ ranges, no lock, OSV clean today | ⚠️ GitHub only | ❌ | ❌ runs as root | ❌ | ❌ | ❌ compose not scanned | ⚠️ docs off since 0.3.5; no DAST | ✅ |

## 6. Findings

### Critical

1. **No CI at all** (steps 1, 4). The three test suites exist and pass
   locally, but nothing runs them on a push or PR, so nothing stops a
   broken or tampered build. *Fix:* a GitHub Actions workflow with three
   jobs (watch host tests, Gradle unit tests, pytest), required on `main`.

### High

2. **No branch protection** (step 7, requirements 1, 5, 6). Anyone with
   the account's token can push to `main` directly. *Fix (user):* a
   ruleset on `main`: no direct pushes, no force push, no deletion, apply
   to admins, required checks once CI exists.
3. **Commits are not signed** (step 7, requirement 7). The author line is
   plain text. *Fix (user):* SSH or GPG signing on both machines, the
   public key on GitHub, then "require signed commits" in the ruleset.
4. **No static analysis** (step 5). CodeQL default setup is available for
   this public repo and already lists c-cpp, java-kotlin and python, but is
   not configured. *Fix:* enable CodeQL default setup (GitHub, free for
   public repos); Ruff with `S` rules for the server in CI.
5. **Server dependencies are ranges, not a lock** (step 6). `fastapi>=0.115`
   and friends resolve to whatever PyPI has at build time; the NAS image
   and the laptop venv already differ (Starlette 1.6.0 vs 1.3.1). *Fix:*
   `requirements.txt` pinned with hashes (`pip-compile --generate-hashes`),
   `pip install --require-hashes` in the Dockerfile, Dependabot alerts on.
6. **Container runs as root on an unpinned base image** (steps 10, 11).
   `FROM python:3.12-slim`, no `USER`, no `.dockerignore`; the compose
   file runs `binwiederhier/ntfy` with no tag (floating `latest`). *Fix:*
   see the pinning list in §8; add a numeric `USER`, `read_only`,
   `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, memory
   limits.
7. **No image scanning or signing** (step 11). The image is built on the
   NAS from the current tree; nobody scans the result. *Fix:* a Trivy scan
   in CI on the Dockerfile and the built image; signing is out of scale
   for a self-hosted single-node deploy (see accepted risks in §9).
8. **Executable binaries in the repository** (step 16): six files in
   `dist/` (APKs, a PBW, two PebbleOS firmware images), 31 MB. A reviewer
   cannot check what is inside them. *Fix:* publish through GitHub
   Releases with checksums; keep `dist/` for the current pair at most, or
   remove it.
9. **Account protection** ❓ (step 16). Cannot be checked from here;
   question 3.

### Medium

10. **No DAST** (step 14) for an internet-facing API. Low effort option:
    an OWASP ZAP API scan against a local container, driven by the
    OpenAPI file exported once from the app (`CM_API_DOCS=1`), not from
    production.
11. **No SBOM** (step 11) for the image; no license check (step 16). The
    project is GPL-3.0; the direct dependencies are MIT/BSD/Apache. Low
    risk, listed for completeness.
12. **Android cleartext HTTP is permitted** by
    `network_security_config.xml` for tailnet deployments. The owner's
    deployment uses HTTPS. *Fix:* restrict cleartext to the Tailscale
    range (`100.64.0.0/10`) or drop it; question 5.
13. **Gradle is not pinned in the repo.** No wrapper, no
    `distributionSha256Sum`, no dependency verification metadata; the
    build depends on a global Gradle 9.6.1. *Fix:* commit the wrapper with
    the checksum; enable Gradle dependency verification.
14. **Compose run settings** (step 11): no `read_only`, `cap_drop`,
    `no-new-privileges`, or memory limits for `monitor` and `ntfy`.
15. **API description was served in production** (step 14): `/openapi.json`
    and `/docs` answered on the live host. **Closed 2026-10-13** by server
    0.3.5 (off unless `CM_API_DOCS=1`).
16. **No security response headers** (not a harden-ci step; dashboard
    posture). **Closed 2026-10-13**: CSP without eval, nosniff, frame
    denial, referrer policy, COOP, permissions policy, `no-store` on
    dashboard pages; HSTS confirmed from the DSM proxy.

### Low

17. `SECURITY.md` missing; private vulnerability reporting off.
18. Dependabot alerts off (platform feature, group 1).
19. CodeQL default setup not configured (counted under finding 4).
20. OpenSSF Scorecard: the project is not indexed; run
    `scorecard --repo github.com/Dreamkeeper/pebble-standby` locally once
    CI exists to cross-check this report.

## 7. Vulnerable dependencies

None found. OSV batch query on 2026-10-13 against the 22 packages
installed in the production container (PyPI) and the 10 direct Android
dependencies (Maven): 0 of 32 with known vulnerabilities. Transitive
Android dependencies were not resolved (needs a Gradle dependency report;
❓).

## 8. References that are not pinned

Versions and digests read from the registries on 2026-10-13, each at
least 7 days old:

| File | Line | Current | Suggested |
|---|---|---|---|
| `server/Dockerfile` | 1 | `python:3.12-slim` (resolves to 3.12.15-slim, published 2026-10-09, 4 days old) | `python:3.12.14-slim@sha256:f77ac9e44ae96ef2c90b8053ea08c31f8be030f824196b0ae4db6d462c84e51f` (2026-09-25) |
| `server/docker-compose.yml` | 62 | `binwiederhier/ntfy` (no tag; v2.30.0 is 1 day old, v2.29.0 6 days) | `binwiederhier/ntfy:v2.28.0@sha256:6ef4b819f722fccdc036af611c4774cfdc2de821ab74fdd48bbf4c9d6f8973da` (2026-08-27) |
| `server/docker-compose.vps.yml` | 33 | `caddy:2` | `caddy:2.11.7@sha256:f2a1290d0463aad60660d4ec134943f183ee2a5f6c3eb7bf32dd984f2f020772` (2026-10-03) |
| `server/requirements.txt` | 1–5 | `>=` ranges | exact versions with `--hash` (pip-compile) |
| `android/` | – | global Gradle 9.6.1, no wrapper | committed wrapper with `distributionSha256Sum` |
| `android/app/build.gradle.kts` | 87–107 | exact versions, no hashes | Gradle dependency verification (`verification-metadata.xml`) |

GitHub Actions do not exist yet; when the workflow is written, every
action goes in by full commit SHA with the version in a comment.

## 9. Questions for the owner

1. Is the repository public on purpose? (Affects nothing in the matrix;
   determines the wording of finding 8 and whether `dist/` stays.)
2. Is it right that nobody else has write access, and will that change?
   (Determines whether required review goes into the ruleset now.)
3. GitHub account: is a passkey or hardware key the second factor, are
   SMS and e-mail-only recovery off, and is the linked e-mail protected
   the same way? (Finding 9.)
4. Is the Caddy / VPS compose variant deployed anywhere, or is the NAS
   the only server? (Decides whether `docker-compose.vps.yml` and the
   Caddyfile are in scope for pinning and run settings.)
5. Does any deployment still reach the server over plain HTTP on the
   tailnet? (Finding 12: drop cleartext or scope it.)
6. Image signing and SBOM attestation (findings 7 and 11): accept as a
   known gap for a single self-hosted node, or do them anyway?

## 10. Fix plan

1. **CI workflow** with the three test jobs and a Trivy scan of the
   Dockerfile and image; actions pinned by SHA; `permissions: contents:
   read`. — Claude (PR), then the owner makes the checks required.
2. **Ruleset on `main`:** no direct push, no force push, no deletion,
   admins included, required checks from step 1, signed commits after
   step 3. — Owner (Settings → Rules → Rulesets).
3. **Commit signing** on `phoenixm-1` and `DKFramework`, key uploaded to
   GitHub. — Owner; Claude can write the git config lines.
4. **Server supply chain:** pinned, hashed requirements; `USER 10001`,
   `.dockerignore`, pinned base image; compose run settings; ntfy and
   Caddy pinned. — Claude (PR), then a NAS redeploy.
5. **Platform features:** Dependabot alerts and security updates with a
   weekly schedule and grouping; CodeQL default setup; private
   vulnerability reporting; `SECURITY.md`. — Owner for the switches,
   Claude for the files.
6. **Binaries:** move `dist/` to GitHub Releases with SHA-256 sums;
   update README links. — Claude (PR), owner confirms what to keep.
7. **Android:** commit the Gradle wrapper with checksum; dependency
   verification; decide on cleartext (question 5). — Claude.
8. **DAST once:** ZAP API scan against a local container from an
   exported OpenAPI file; findings into the next change. — Claude.
9. Accepted-risk decisions on signing and SBOM (question 6). — Owner.

## 11. Stage 2 results (2026-10-14)

Owner decision: no manual review step. Everything that runs on its own
was done; everything that needs a human gate is an accepted risk.

**Done**

- CI (`.github/workflows/tests.yml`): the three test suites and a Trivy
  scan of the server image run on every push to `main` and on PRs; test
  counts asserted; actions pinned by SHA; token read-only. Findings 1, 4
  (Trivy on the image; CodeQL left off), 7 (scan), 20 (Scorecard can run
  now).
- Server 0.3.6 on the NAS: hashed lock, pinned base image with Debian
  security upgrade at build, non-root user 10001, read-only root
  filesystem, dropped capabilities, no new privileges, memory limit;
  ntfy pinned by digest. Findings 5, 6, 14 and the pinning list.
- GitHub: Dependabot alerts and security updates on, private
  vulnerability reporting on. Findings 17 (half), 18.
- Dashboard headers and API docs off (findings 15, 16) shipped 2026-10-13.

**Accepted risks (owner, 2026-10-14: "no manual review")**

- No branch protection, PRs or signed commits (findings 2, 3). CI on
  `main` is the net; a failing run reaches the owner by e-mail.
- No image signing, SBOM, DAST, license check, IaC scan (7 in part, 10,
  11). One self-hosted node that builds its own image.
- Binaries stay in `dist/` (8) until Releases are set up.
- Account protection (9): TOTP today, passkey advised (owner, 2026-10-14).
- Android cleartext (12): **closed 2026-10-14**, companion preview 8 accepts plain http only on the Tailscale range, *.ts.net, RFC 1918 ranges, loopback and local names (`ServerUrlPolicy`, enforced in both URL inputs and in the client).

**Before → after**

| Application | Unit tests | SAST | Dependencies | Secrets | Pinning | Image: no root | Image: scan | Sign + SBOM | IaC | API + DAST | Prod settings |
|---|---|---|---|---|---|---|---|---|---|---|---|
| watchapp | ⚠️ → ✅ | ❌ | – | ⚠️ | ⚠️ | – | – | – | – | – | ✅ |
| android | ⚠️ → ✅ | ❌ | ⚠️ (Dependabot alerts on) | ⚠️ | ⚠️ | – | – | – | – | – | ⚠️ |
| server | ⚠️ → ✅ | ❌ | ⚠️ → ✅ hashed lock + alerts | ⚠️ | ❌ → ✅ | ❌ → ✅ | ❌ → ✅ | ❌ (accepted) | ❌ (accepted) | ⚠️ docs off, no DAST (accepted) | ✅ |

The first 0.3.6 deploy cost six minutes of downtime: DSM presents its
ACL-managed directories to containers with POSIX mode 000, so the
non-root process could read neither the code nor the data directory
until both were chmod-ed. Both fixes are in the Dockerfile and the
deploy guide.
