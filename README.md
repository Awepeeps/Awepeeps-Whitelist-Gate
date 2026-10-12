# Awepeeps Whitelist Gate

A small Paper plugin that observes **Paper's own whitelist denials**, keeps a
bounded pending-request queue, and sends a Discord incoming-webhook notice.
An operator approves or denies locally with `/wlgate`; approval adds the
authenticated UUID to Paper's vanilla whitelist. This plugin never allows a
login and never changes whitelist settings. Keep `white-list=true` and
`enforce-whitelist=true` enabled in the server itself.

Detection uses Paper 26.3's `ProfileWhitelistVerifyEvent`, which Paper fires
while it verifies the whitelist for each login. It carries Paper's real
decision (`whitelisted`) and the authenticated profile, so a Bedrock player
arrives with their Floodgate UUID. The listener only reads that decision and
replaces the kick message; it never calls `setWhitelisted(true)` on the event,
so the vanilla whitelist remains the only thing that admits or denies players.
If the plugin is absent or fails to load, Paper's whitelist still denies the
login exactly as before.


The webhook is notification-only. It cannot receive Discord button clicks.
The `RequestNotifier` interface and shared decision service leave a narrow
seam for the separately documented future bot; no bot, JDA, inbound listener,
analytics, update checker, or shaded dependency is included now.

## Requirements and setup

- Paper 26.3 on Java 25.
- Floodgate is optional. When present, the plugin checks Floodgate's
  `isFloodgateId(UUID)` API; it does not infer platform from a name prefix.
- Install the jar in `plugins/` and restart Paper. Do not disable the vanilla
  whitelist if this plugin is missing or unavailable.

Create a Discord incoming webhook in a private channel:

1. In Discord, open the server's **Server Settings**, then **Integrations**.
2. Choose **Webhooks**, create one, and select the private channel where
   request notices should appear.
3. Copy the webhook URL. Treat it like a password: anyone who obtains it can
   post to that channel. Do not paste it into this repository, `config.yml`,
   chat, or a command.
4. Supply it to the Minecraft process as the environment variable
   `WLGATE_DISCORD_WEBHOOK_URL`, or place only the URL in a separate mounted
   secret file and configure `secrets.webhook-file` to that file path.

For Docker/itzg managed by MCSManager, set the variable in the **Minecraft
container's saved instance environment**, not only in the MCSManager container.
If the panel cannot inject it securely, mount a secret file read-only and set
the plugin config path to that in-container path. Do not put the value in the
container command line, tracked compose file, or plugin config. Restart the
Minecraft process after supplying it. With neither source configured, the
plugin still records requests and local commands work; it logs one startup
warning and sends no notifications. The URL is never shown by status or
included in logs.

## Configuration

`plugins/Awepeeps-Whitelist-Gate/config.yml` is created with these defaults:

```yaml
request:
  pending-ttl-hours: 24
  denied-rerequest-cooldown-hours: 24
  notification-cooldown-seconds: 300
  max-pending: 100
  max-discord-messages-per-minute: 5
storage:
  audit-retention-days: 90
secrets:
  webhook-environment: WLGATE_DISCORD_WEBHOOK_URL
  webhook-file: ""
messages:
  whitelist-denied: "Your request was sent to the server owner. Please try again later."
```

Repeated attempts update the attempt count but do not produce another
notification inside the cooldown. Once it passes, the existing Discord
message is edited when its message ID is available. New outbound messages are
queued and globally rate-limited; the queue cannot exceed the pending-request
cap. Excess distinct pending identities are dropped and counted, never
admitted. Requests expire after the TTL. A denied identity can request again
after the configured denial cooldown.

`webhook-file` is an optional filesystem path to a mounted secret containing
only the URL; a non-empty environment variable takes precedence. Reload with
`/wlgate reload` after changing settings. The webhook itself is not copied to
plugin config.

## Commands and permission

All commands require `wlgate.admin` (`default: op`); the server console is
always allowed. Tab completion offers pending IDs and names.

| Command | Effect |
| --- | --- |
| `/wlgate list` | List pending request IDs, names, platform, attempts, and last-seen time. |
| `/wlgate approve <requestId\|name>` | Add the pending UUID to Paper's whitelist and record the actor. |
| `/wlgate deny <requestId\|name>` | Deny the request without changing the whitelist. |
| `/wlgate forget <requestId\|name>` | Remove a request record. |
| `/wlgate reload` | Reload configuration and webhook credentials. |
| `/wlgate status` | Show whitelist/enforcement state, webhook configured, Floodgate detected, and pending count. Never prints a secret. |

Every webhook notice includes the opaque request ID and the exact local
`/wlgate approve <id>` and `/wlgate deny <id>` commands. Names are escaped and
mentions are disabled in Discord payloads.

## Bedrock approval path

Floodgate classification uses its API against the authenticated profile UUID
(`FloodgateApi.getInstance().isFloodgateId(uuid)`), never a name prefix.
When an operator approves, the plugin calls
`Bukkit.getOfflinePlayer(request.uuid()).setWhitelisted(true)` on Paper's main
thread; it does not resolve a Bedrock gamertag over the network.

This path was checked against the Paper 26.3 and Minecraft 26.3 sources:

- `CraftOfflinePlayer.setWhitelisted(true)` adds a `UserWhiteListEntry` built
  from the offline player's `NameAndId` UUID, with no network call.
- `UserWhiteList.getKeyForUser(NameAndId)` returns `user.id().toString()`, so
  the whitelist is keyed by **UUID**. The next login of that Bedrock player is
  matched by the same Floodgate UUID that the login event reported.
- `CraftServer.getOfflinePlayer(UUID)` builds the entry with an **empty name
  string** for a player who has never joined. The resulting `whitelist.json`
  entry therefore looks like `{"uuid":"<floodgate-uuid>","name":""}`. That is
  valid JSON and reload-safe; the empty name is cosmetic (Paper keys by UUID).

So the same UUID recorded from the denied login is the UUID added on approval,
and the next join should be accepted. This has **not** been runtime-tested
against a real never-before-joined Bedrock account; verify that exact reconnect
on a private test server before relying on it.


## Privacy and retained data

The request store contains UUID, platform, name, request ID, first/last seen,
attempt count, state, decision actor/time, and Discord message ID. The audit
log contains request ID, UUID, decision, actor, and timestamp and is retained
for the configured period. No IP address is read into plugin state, stored, or
logged; there is no geolocation, third-party lookup, analytics, or updater.
The only outbound destination is the configured Discord webhook. Player text
is sanitized and `allowed_mentions` is set to none.

Build and test with JDK 25:

```sh
./gradlew --no-daemon clean test build
```

The jar is `build/libs/awepeeps-whitelist-gate-1.0.0.jar`. See
[`CHANGELOG.md`](CHANGELOG.md) and
[`docs/discord-bot-design.md`](docs/discord-bot-design.md).
