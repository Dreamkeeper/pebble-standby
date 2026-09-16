"""Outbound channels (design D5): address-based, synchronous transports.

Each channel delivers one message to one address and returns True only
when the transport accepted it. Callers run these in a thread
(``asyncio.to_thread``) — the pump must never block on SMTP. Transport
ACK != human ACK; human ACKs arrive via /api/v1/ack or the Telegram
long-poll.

Rendering lives here too so every channel says the same thing and the
TEST prefix is applied in exactly one place (design D7).
"""
from __future__ import annotations

import json
import smtplib
import urllib.parse
import urllib.request
from email.message import EmailMessage

TIMEOUT_S = 15


def render_message(wearer_name: str, kind: str, detector: str,
                   location: str, escalation_id: str,
                   to_wearer: bool = False) -> str:
    prefix = "[TEST] " if kind == "test" else ""
    if kind == "phone_silent":
        body = (f"{wearer_name}'s phone has gone silent — no heartbeat "
                f"within the grace period. This is an ADVISORY, not a "
                f"confirmed alarm. Please try to reach {wearer_name}.")
    else:
        body = (f"ALERT: {wearer_name} may be unresponsive "
                f"(detector: {detector}). Please respond now.")
    if location:
        body += f"\nLocation: https://maps.google.com/?q={location}"
    body += f"\nRef: {escalation_id}"
    if to_wearer:
        body = (f"[copy to wearer] Your contacts are being alerted.\n{body}"
                if kind != "test" else
                f"[copy to wearer] {prefix}Your contacts received a test.\n{body}")
    return prefix + body


class TelegramChannel:
    name = "telegram"

    def __init__(self, bot_token: str):
        self.bot_token = bot_token

    @property
    def configured(self) -> bool:
        return bool(self.bot_token)

    def deliver(self, address: str, text: str, ack_url: str | None,
                ack_token: str | None) -> bool:
        payload: dict = {"chat_id": address, "text": text}
        if ack_token:
            payload["reply_markup"] = {"inline_keyboard": [[
                {"text": "✅ I acknowledge — I'm on it",
                 "callback_data": f"ack:{ack_token}"}]]}
        return _post_json(
            f"https://api.telegram.org/bot{self.bot_token}/sendMessage",
            payload)


class NtfyChannel:
    name = "ntfy"

    def __init__(self, base_url: str):
        self.base_url = (base_url or "").rstrip("/")

    @property
    def configured(self) -> bool:
        return bool(self.base_url)

    def deliver(self, address: str, text: str, ack_url: str | None,
                ack_token: str | None) -> bool:
        headers = {"Priority": "urgent", "Tags": "rotating_light",
                   "Title": "Standby"}
        if ack_url:
            # http action: one tap fires the POST from the notification —
            # the ack endpoint no longer mutates on GET (link scanners).
            headers["Actions"] = (f"http, Acknowledge, {ack_url}, "
                                  f"method=POST, clear=true")
        req = urllib.request.Request(
            f"{self.base_url}/{urllib.parse.quote(address)}",
            data=text.encode(), headers=headers)
        return _urlopen_ok(req)


class EmailChannel:
    name = "email"

    def __init__(self, host: str, port: int, sender: str,
                 username: str = "", password: str = ""):
        self.host, self.port, self.sender = host, port, sender
        self.username, self.password = username, password

    @property
    def configured(self) -> bool:
        return bool(self.host and self.sender)

    def deliver(self, address: str, text: str, ack_url: str | None,
                ack_token: str | None) -> bool:
        msg = EmailMessage()
        msg["From"] = self.sender
        msg["To"] = address
        msg["Subject"] = ("[TEST] Standby" if text.startswith("[TEST]")
                          else "[ALERT] Standby")
        content = text
        if ack_url:
            content += f"\n\nAcknowledge: {ack_url}"
        msg.set_content(content)
        try:
            with smtplib.SMTP(self.host, self.port, timeout=TIMEOUT_S) as s:
                if self.username:
                    s.starttls()
                    s.login(self.username, self.password)
                s.send_message(msg)
            return True
        except OSError:
            return False


def build_channels(env: dict) -> dict[str, object]:
    """name -> channel, configured ones only."""
    out: dict[str, object] = {}
    tg = TelegramChannel(env.get("CM_TELEGRAM_BOT_TOKEN", ""))
    if tg.configured:
        out[tg.name] = tg
    ntfy = NtfyChannel(env.get("CM_NTFY_URL", ""))
    if ntfy.configured:
        out[ntfy.name] = ntfy
    email = EmailChannel(env.get("CM_SMTP_HOST", ""),
                         int(env.get("CM_SMTP_PORT", "587") or 587),
                         env.get("CM_SMTP_FROM", ""),
                         env.get("CM_SMTP_USER", ""),
                         env.get("CM_SMTP_PASSWORD", ""))
    if email.configured:
        out[email.name] = email
    return out


def _post_json(url: str, payload: dict) -> bool:
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    return _urlopen_ok(req)


def _urlopen_ok(req: urllib.request.Request) -> bool:
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            return 200 <= resp.status < 300
    except OSError:
        return False
