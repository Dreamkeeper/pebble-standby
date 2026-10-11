"""Dead-man logic: is the wearer's phone alive?

Pure state machine — no IO, injected clock — so it is unit-testable and
auditable. The FastAPI layer feeds it heartbeats; a periodic task calls
``evaluate()`` and acts on the returned transitions.

Severity model (docs/PLAN.md): a silent phone is an ADVISORY to Tier 1,
distinct from a confirmed watch alarm. Declared offline windows
(airplane/subway) and a low-battery notice suppress or soften it.
"""
from __future__ import annotations

import enum
from dataclasses import dataclass, field


class PhoneState(enum.Enum):
    OK = "ok"
    LATE = "late"          # missed heartbeats, within grace: re-ping quietly
    SILENT = "silent"      # grace exhausted: advisory escalation
    OFFLINE_DECLARED = "offline_declared"  # user told us; no escalation until window ends


@dataclass
class DeadmanConfig:
    heartbeat_interval_s: int = 300
    late_after_missed: int = 2       # heartbeats missed before LATE
    silent_after_s: int = 1800       # seconds without heartbeat before SILENT
    low_battery_extra_s: int = 3600  # extra grace after a dying-battery notice


@dataclass
class DeadmanMonitor:
    cfg: DeadmanConfig = field(default_factory=DeadmanConfig)
    last_heartbeat_t: float | None = None
    low_battery_notice_t: float | None = None
    offline_until_t: float | None = None
    state: PhoneState = PhoneState.OK

    def heartbeat(self, t: float, battery_pct: int | None = None) -> None:
        self.last_heartbeat_t = t
        if battery_pct is not None and battery_pct > 20:
            self.low_battery_notice_t = None  # battery recovered
        self.state = PhoneState.OK

    def low_battery_notice(self, t: float) -> None:
        """Phone warns: battery dying, expect silence soon."""
        self.low_battery_notice_t = t

    def declare_offline(self, t: float, duration_s: int) -> None:
        """User-declared window (airplane, subway, a day without the watch);
        a duration of 0 cancels it (the wearer resumed early)."""
        self.offline_until_t = t + duration_s if duration_s > 0 else None

    def _silent_threshold_s(self) -> int:
        extra = (self.cfg.low_battery_extra_s
                 if self.low_battery_notice_t is not None else 0)
        return self.cfg.silent_after_s + extra

    def evaluate(self, t: float) -> PhoneState:
        """Recompute state at time ``t``. Caller reacts to transitions."""
        if self.offline_until_t is not None and t < self.offline_until_t:
            self.state = PhoneState.OFFLINE_DECLARED
            return self.state
        if self.offline_until_t is not None and t >= self.offline_until_t:
            # window over: give one interval of grace for the phone to return
            self.offline_until_t = None
            self.last_heartbeat_t = max(self.last_heartbeat_t or t, t)

        if self.last_heartbeat_t is None:
            self.state = PhoneState.OK  # never seen: onboarding, not an emergency
            return self.state

        silence = t - self.last_heartbeat_t
        if silence >= self._silent_threshold_s():
            self.state = PhoneState.SILENT
        elif silence >= self.cfg.heartbeat_interval_s * self.cfg.late_after_missed:
            self.state = PhoneState.LATE
        else:
            self.state = PhoneState.OK
        return self.state
