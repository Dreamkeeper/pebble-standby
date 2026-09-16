"""Telegram acknowledgement receipt via getUpdates long-polling (design D4).

No inbound webhook: this task polls the Bot API, filters callback
queries whose data is ``ack:<token>``, hands the token to the same ACK
handler the HTTP endpoint uses, and answers the callback so the contact
sees confirmation. The update offset persists in the store so restarts
neither replay nor drop updates.
"""
from __future__ import annotations

import asyncio
import json
import logging
import urllib.parse
import urllib.request
from typing import Awaitable, Callable

log = logging.getLogger("cryomonitor.tgpoll")

LONG_POLL_S = 25
OFFSET_KEY = "tg_poll_offset"


def parse_ack_callbacks(updates: list[dict]) -> list[dict]:
    """[{update_id, cq_id, token, chat_id, message_id, text}] — pure.

    chat_id/message_id/text identify the message carrying the pressed
    button so the loop can edit it: button removed, ACKNOWLEDGED marker
    appended (contacts must see at a glance that someone owns the alarm).
    """
    out = []
    for u in updates:
        cq = u.get("callback_query") or {}
        data = cq.get("data") or ""
        if data.startswith("ack:") and cq.get("id"):
            msg = cq.get("message") or {}
            chat = msg.get("chat") or {}
            out.append({"update_id": u["update_id"], "cq_id": cq["id"],
                        "token": data[4:], "chat_id": chat.get("id"),
                        "message_id": msg.get("message_id"),
                        "text": msg.get("text") or ""})
    return out


def parse_messages(updates: list[dict]) -> list[tuple[int, int, str]]:
    """[(update_id, chat_id, sender_name)] for plain messages — pure.

    Anyone messaging the bot is onboarding: the loop replies with their
    chat id so they can hand it to the wearer/operator, and logs it so
    the operator can read it from the server log.
    """
    out = []
    for u in updates:
        m = u.get("message") or {}
        chat = m.get("chat") or {}
        if chat.get("id") is not None:
            frm = m.get("from") or {}
            name = " ".join(x for x in (frm.get("first_name"),
                                        frm.get("last_name")) if x)
            out.append((u["update_id"], chat["id"], name or "unknown"))
    return out


def _api(bot_token: str, method: str, params: dict, timeout: float) -> dict:
    url = (f"https://api.telegram.org/bot{bot_token}/{method}?"
           + urllib.parse.urlencode(params))
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.load(r)


async def poll_loop(bot_token: str, store, on_ack: Callable[[str], Awaitable[str]],
                    idle_sleep_s: int = 30) -> None:
    """on_ack(token) -> confirmation text shown to the contact."""
    while True:
        try:
            offset = int(store.kv_get(OFFSET_KEY) or 0)
            resp = await asyncio.to_thread(
                _api, bot_token, "getUpdates",
                {"timeout": LONG_POLL_S, "offset": offset + 1,
                 # messages must stay enabled: restricting storage to
                 # callbacks makes Telegram DROP contacts' onboarding
                 # messages before anyone can read their chat id
                 "allowed_updates": '["message","callback_query"]'},
                LONG_POLL_S + 10)
            updates = resp.get("result", [])
            if updates:
                for update_id, chat_id, name in parse_messages(updates):
                    log.info("telegram onboarding message from chat %s (%s)",
                             chat_id, name)
                    await asyncio.to_thread(
                        _api, bot_token, "sendMessage",
                        {"chat_id": chat_id,
                         "text": ("Standby bot. Your chat id is: "
                                  f"{chat_id}\nGive this number to the "
                                  "wearer or operator so they can add you "
                                  "as an emergency contact.")}, 15)
                for cb in parse_ack_callbacks(updates):
                    try:
                        text = await on_ack(cb["token"])
                    except Exception:
                        log.exception("ack handler failed")
                        text = "Error recording acknowledgement"
                    await asyncio.to_thread(
                        _api, bot_token, "answerCallbackQuery",
                        {"callback_query_id": cb["cq_id"], "text": text[:190]}, 15)
                    # edit the original message: button gone, outcome visible
                    if cb["chat_id"] is not None and cb["message_id"] is not None:
                        marker = ("✅ ACKNOWLEDGED"
                                  if text.startswith(("Acknowledged", "Already"))
                                  else f"ℹ️ {text}")
                        try:
                            await asyncio.to_thread(
                                _api, bot_token, "editMessageText",
                                {"chat_id": cb["chat_id"],
                                 "message_id": cb["message_id"],
                                 "text": f"{cb['text']}\n\n{marker}"}, 15)
                        except Exception as e:
                            log.warning("ack message edit failed: %s", e)
                new_offset = max(u["update_id"] for u in updates)
                await asyncio.to_thread(store.kv_set, OFFSET_KEY, str(new_offset))
        except Exception as e:
            log.warning("telegram poll cycle failed: %s", e)
            await asyncio.sleep(idle_sleep_s)
