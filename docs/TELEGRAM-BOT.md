# Telegram alerts: creating and configuring the bot(s)

Standby alerts contacts through a Telegram **bot** you create yourself —
there is no shared Standby bot, so nobody but you holds the keys.

There are two independent places a bot can be used:

| | **Server bot** (main channel) | **Phone fallback bot** (optional) |
|---|---|---|
| Who sends | the Standby server | the Android app itself |
| When | every alarm, test and phone-silent warning | only when an alarm fires and the server cannot be reached |
| Features | **Acknowledge** button, contact tiers, retries, all-clear messages | one plain text message with a map link; a plain "cancelled" message |
| Token lives in | `CM_TELEGRAM_BOT_TOKEN` in the server's `.env` | the app: *Show manual configuration* → *Telegram bot token* |

## Same bot or two bots?

**Use two bots.** The reasons, in order of weight:

1. **A shared server must never hand out its token.** If several
   wearers use one server (a family, a response group, a beta), giving
   each phone the server's token would let any of them send messages as
   the bot and read everyone's acknowledgements.
2. **One poller only.** The server receives *Acknowledge* presses by
   long-polling the bot. Telegram delivers each update to a single
   poller; anyone else holding the token who calls `getUpdates` (or sets
   a webhook) silently steals acknowledgements — contacts press the
   button and the escalation carries on regardless.
3. **The phone is the weaker vault.** A lost or compromised phone leaks
   its token. With a separate fallback bot you revoke that one in
   BotFather and the server's alerts are untouched.
4. **It tells contacts something.** A message from the *fallback* bot
   means "the server was unreachable when this fired" — useful to know
   at 3 a.m.

The cost is that each contact presses **Start** on two bots instead of
one. On a single-user, self-hosted setup you may knowingly reuse one
bot for both; nothing breaks, because the phone only ever calls
`sendMessage`. You just lose points 3 and 4.

## 1. Create the server bot

1. In Telegram, open **[@BotFather](https://t.me/BotFather)** and send
   `/newbot`.
2. Give it a display name (e.g. `Standby alerts — Kvasnikov`) and a
   username ending in `bot` (e.g. `kvasnikov_standby_bot`).
3. BotFather replies with the **token** — `123456789:AA…`. Treat it as a
   password: whoever has it controls the bot.
4. Recommended while you are there: `/setjoingroups` → *Disable*
   (unless you plan to alert a group chat, see below), and
   `/setdescription` with a line such as *"Emergency alerts for
   <name>. Press Start to receive them."* so contacts know what it is.

Put the token on the server and restart it:

```bash
# server/.env
CM_TELEGRAM_BOT_TOKEN=123456789:AA...
```

```bash
docker compose up -d        # picks up the new .env
```

Where Telegram is blocked from the server's network, also set
`CM_OUTBOUND_PROXY` (see `.env.example`). Do not run two servers with
the same token, and do not set a webhook on this bot (reason 2 above).

## 2. Each contact presses Start and gets a chat id

A bot cannot message someone who has never talked to it. So every
contact (and the wearer, for self-copies):

1. Opens the bot (`https://t.me/<your_bot_username>`) and presses
   **Start** — or sends it any message.
2. The bot answers: **"Standby bot. Your chat id is: 123456789"**.
3. They send that number to the wearer.

Enter the chat id for the contact either in the Android app —
*Contacts & safety net* → the contact → *Telegram chat id* — or in the
web dashboard under the wearer's contacts.

**Group chats** work too: add the bot to the group (needs
`/setjoingroups` enabled), send a message there, and use the group's
chat id, which is negative (e.g. `-1001234567890`). One acknowledgement
from anyone in the group counts.

## 3. Test it

In the app: *Contacts & safety net* → **🔔 Fire drill — send a TEST
alert now**. Every message is tagged `[TEST]`. Each contact should get
a message with an **Acknowledge** button; pressing it stops the
escalation and shows up in the dashboard. Do this once per new contact —
an alert channel nobody has tested is a hope, not a channel.

## 4. (Optional) the phone fallback bot

Repeat step 1 to create a **second** bot, e.g.
`kvasnikov_standby_fallback_bot`. Then on the phone: *Show manual
configuration* →

- **Telegram bot token (phone-direct fallback channel):** the second
  bot's token.
- **Telegram chat ids (phone-direct fallback):** comma-separated ids of
  the people to alert.

Each of those people must press **Start** on the fallback bot as well.
Their chat id is the same number as with the server bot (for a person,
the chat id is their Telegram user id), so you do not need to ask again.

Running with **no server at all**? Then this is your only Telegram
channel and nothing replies with chat ids. A contact can get theirs
from any "user info" bot, or you can read it yourself: have them message
your bot, then open
`https://api.telegram.org/bot<TOKEN>/getUpdates` in a browser and look
for `"chat":{"id":…}`.

**Test it:** *Show manual configuration* → **Test fallback bot**. It
sends a `[TEST]` message through the fallback bot right away and shows
Telegram's answer per chat id — *delivered*, *has not pressed Start*,
*token rejected* — so a typo is found now, not during an emergency. (The
fire drill does not exercise this bot: it goes to the server.)

The fallback sends only when an alarm fires and the server did not
accept it; if it fired, the cancellation is sent there too. It has no
Acknowledge button and no retries — it is a last resort, not a
replacement.

## If a token leaks

In BotFather: `/revoke` → choose the bot → a new token is issued and
the old one dies immediately. Update `.env` (server bot) or the app
setting (fallback bot). Contacts do not need to do anything; their chat
ids stay valid.
