from app.deadman import DeadmanConfig, DeadmanMonitor, PhoneState

CFG = DeadmanConfig(heartbeat_interval_s=300, late_after_missed=2,
                    silent_after_s=1800, low_battery_extra_s=3600)


def make():
    return DeadmanMonitor(CFG)


def test_ok_while_heartbeats_flow():
    m = make()
    for i in range(10):
        t = i * 300.0
        m.heartbeat(t, battery_pct=80)
        assert m.evaluate(t + 1) == PhoneState.OK


def test_late_then_silent():
    m = make()
    m.heartbeat(0.0, battery_pct=80)
    assert m.evaluate(500) == PhoneState.OK          # one missed beat: fine
    assert m.evaluate(700) == PhoneState.LATE        # 2 intervals missed
    assert m.evaluate(1799) == PhoneState.LATE
    assert m.evaluate(1800) == PhoneState.SILENT     # grace exhausted


def test_low_battery_notice_extends_grace():
    m = make()
    m.heartbeat(0.0, battery_pct=15)
    m.low_battery_notice(0.0)
    assert m.evaluate(1800) == PhoneState.LATE       # would be SILENT without notice
    assert m.evaluate(1800 + 3600 - 1) == PhoneState.LATE
    assert m.evaluate(1800 + 3600) == PhoneState.SILENT


def test_battery_recovery_clears_notice():
    m = make()
    m.heartbeat(0.0, battery_pct=15)
    m.low_battery_notice(0.0)
    m.heartbeat(300.0, battery_pct=90)               # charged again
    assert m.low_battery_notice_t is None
    assert m.evaluate(300 + 1800) == PhoneState.SILENT  # normal grace applies


def test_declared_offline_window_suppresses():
    m = make()
    m.heartbeat(0.0, battery_pct=80)
    m.declare_offline(100.0, duration_s=7200)        # airplane mode, 2 h
    assert m.evaluate(3000) == PhoneState.OFFLINE_DECLARED
    assert m.evaluate(7200 + 99) == PhoneState.OFFLINE_DECLARED
    # window over: grace restarts from the window end, no instant SILENT
    assert m.evaluate(7301) == PhoneState.OK
    assert m.evaluate(7301 + 1800) == PhoneState.SILENT


def test_never_seen_phone_is_not_an_emergency():
    m = make()
    assert m.evaluate(1e9) == PhoneState.OK


def test_offline_window_cancel_restores_normal_rules():
    m = make()
    m.heartbeat(0.0, battery_pct=80)
    m.declare_offline(10.0, 6 * 3600)
    assert m.evaluate(3600) == PhoneState.OFFLINE_DECLARED
    m.declare_offline(3600, 0)          # the wearer resumed early
    assert m.offline_until_t is None
    m.heartbeat(3600, battery_pct=80)
    assert m.evaluate(3601) == PhoneState.OK
    assert m.evaluate(3600 + 1800) == PhoneState.SILENT   # back to the usual grace
