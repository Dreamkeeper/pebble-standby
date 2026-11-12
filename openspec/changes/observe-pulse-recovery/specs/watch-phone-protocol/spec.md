## ADDED Requirements

### Requirement: Observe pulse recovery without operating the alarm
A diagnostic build SHALL record paired observations during pulse-loss and not-worn hunts without changing sensor subscriptions, timing, detector inputs or outputs. The companion SHALL export the records through shared logs only. Tag 0xC203 has a 16-byte little-endian record: start epoch u32, packed sample u32, elapsed seconds u16, event u8, detector u8, first live recovery second u16, first two-sample recovery second u16. Missing decision is 65535. Existing heartbeat and alarm formats remain unchanged.

#### Scenario: Two credible acquisitions
- **WHEN** two adjacent distinct sensor events meet the confidence, wear, BPM and change conditions during a hunt
- **THEN** record the shadow recovery time once and continue the existing live policy.

#### Scenario: Cache repeat or sequence gap
- **WHEN** callbacks repeat a sample, skip an event or carry an unavailable snapshot
- **THEN** do not count a duplicate or infer an unobserved pair.

#### Scenario: Live recovery or interruption
- **WHEN** live recovery ends the burst early or monitoring is interrupted
- **THEN** report observed decisions only, without extending sampling, dismissing a prompt or issuing an alarm.
