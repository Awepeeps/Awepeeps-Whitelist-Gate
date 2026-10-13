# Repository guidance: Awepeeps Whitelist Gate

This file applies to **every file in this repository**. This repository is **private**, but it must
still never contain secrets or real player data. [README.md](README.md) is the user-facing guide;
the repo-local skill
[whitelist-gate-notification-change](.agents/skills/whitelist-gate-notification-change/SKILL.md)
covers notification and decision changes.

## Purpose and layout

A Paper plugin that observes Paper's own whitelist denials (`ProfileWhitelistVerifyEvent`), keeps a
bounded pending-request queue, and can send an optional Discord incoming-webhook notice. Local
`/wlgate` commands approve or deny; approval is the only path that writes Paper's vanilla whitelist.
Code is `src/main/java/com/awepeeps/whitelistgate` (`core`, `notify`, `storage`); config is
`src/main/resources/config.yml`; the optional future-bot seam is `docs/discord-bot-design.md`.

## Invariants to preserve

- The plugin never admits a login and never changes whitelist settings; it does not call
  `setWhitelisted(true)` on the event. Vanilla whitelist stays the only thing that admits or denies.
- Notifications are outbound-only: no inbound listener, bot, analytics, or updater yet.
- The webhook URL comes only from the environment (`WLGATE_DISCORD_WEBHOOK_URL`) or a mounted
  secret file (`secrets.webhook-file`), and is never written to config, source, logs or `/wlgate status`.
- No IP address is read or logged; player text is sanitized and Discord mentions are disabled.
- MCSManager owns the container, itzg Paper image and runtime; this plugin does not manage them.

## Build and test

Requires JDK 25. Run from the repo root:

```sh
./gradlew --no-daemon clean test build
```

Jar: `build/libs/awepeeps-whitelist-gate-1.0.1.jar`. `.gitattributes` pins LF so the jar is
reproducible on Windows and Linux; CI prints the jar SHA-256. Update `CHANGELOG.md` for
user-visible changes.

## Privacy

Keep the webhook URL and every other secret out of Git, config, logs and status output. Retained
request data is UUID, platform, name, timestamps and decision metadata only.
