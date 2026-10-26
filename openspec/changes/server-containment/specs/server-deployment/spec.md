# Delta: server-deployment

## ADDED Requirements

### Requirement: Listeners default to loopback and the Telegram poller self-heals
The base compose SHALL bind the application and the bundled ntfy to the
host's loopback address so that only the reverse proxy reaches them; the
VPS Caddyfile SHALL answer 404 under the placeholder ntfy name when
`CM_NTFY_DOMAIN` is unset. The Telegram poller SHALL log and skip a
failing Bot API call for one update and SHALL advance its offset past
the batch, so one blocked chat cannot stop later acknowledgements.

#### Scenario: A blocked chat in the batch
- **WHEN** the onboarding reply to one chat fails with 403 and the same
  batch holds an acknowledge callback
- **THEN** the acknowledgement is recorded and the stored offset moves
  past the batch
