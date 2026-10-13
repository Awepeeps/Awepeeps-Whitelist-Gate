---
name: whitelist-gate-notification-change
description: Change Awepeeps Whitelist Gate request capture, decisions, or Discord notifications while keeping the fail-closed whitelist contract and the webhook secret out of Git. Use for edits under src/main/java/com/awepeeps/whitelistgate or its config and tests.
---

# Whitelist Gate notification change

Work in an isolated worktree off `main`. Read [AGENTS.md](../../../AGENTS.md) first.

## Preserve the fail-closed contract

- The listener reads Paper's `ProfileWhitelistVerifyEvent` decision and only replaces the kick
  message; never call `setWhitelisted(true)` on the event.
- Only `/wlgate approve` writes the vanilla whitelist, via
  `Bukkit.getOfflinePlayer(uuid).setWhitelisted(true)` on the main thread. Never add automatic admission.
- Keep notifications outbound-only. The `RequestNotifier` interface is the seam for the documented
  future bot; do not add an inbound listener, JDA, analytics, or updater now.

## Handle the secret correctly

- Resolve the webhook URL from `secrets.webhook-environment` (default `WLGATE_DISCORD_WEBHOOK_URL`)
  or `secrets.webhook-file`; the env var takes precedence and a missing file means "not configured".
- Never log, echo, or embed the URL. Run resolved values through `SecretScrubber`, and keep distinct
  warnings for missing, unreadable, empty, and invalid files.

## Test and build

1. Add unit tests under `src/test/java/com/awepeeps/whitelistgate` (see `WebhookSecretResolverTest`,
   `StoragePathsTest`); keep plugin paths absolute and null-parent safe.
2. Run `./gradlew --no-daemon clean test build`.
3. Update `config.yml` comments, `README.md`, and `CHANGELOG.md` for user-visible changes.

## Do not

- Retire `white-list`/`enforce-whitelist`; the plugin assumes both stay on.
- Commit a webhook URL or any secret, even though this repository is private.
