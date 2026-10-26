# Delta: web-dashboard

## ADDED Requirements

### Requirement: Login throttling never lets a stranger lock an operator out
The login throttle SHALL key the per-account counter on the account and
the calling address together, and the per-address counter on the address;
failures recorded for an account from other addresses SHALL NOT block the
operator's correct password from their own address. Twenty failures for
one account in a window from any addresses SHALL be audited as
`login_guessing_suspected`. Password verification SHALL run off the
event loop.

#### Scenario: A stranger's failures
- **WHEN** another address records five failures for an operator's
  username
- **THEN** that address is throttled and the operator still logs in from
  their own address

### Requirement: Operator usernames are routable
A username SHALL match `[A-Za-z0-9][A-Za-z0-9._-]{0,31}` at creation, and
the operators page SHALL URL-encode names in action paths, so every
account can be disabled, demoted and reset through the dashboard.

#### Scenario: A slash in the name
- **WHEN** an admin creates an operator named `ops/x`
- **THEN** the form is refused with the username rule and no row is created

### Requirement: The admin API's cookie path requires CSRF on mutations
When an `/api/v1/admin/*` mutation is authenticated by the operator
session cookie, the request SHALL carry the session's CSRF token in the
`X-CSRF` header; reads need the cookie only.

#### Scenario: Cookie-only POST
- **WHEN** a logged-in admin's browser posts to disable a wearer with no
  `X-CSRF` header
- **THEN** the server answers 403 and the wearer stays enabled
