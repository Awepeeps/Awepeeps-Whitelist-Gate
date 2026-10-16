# Changelog

## Retired — 2026-10-16

- Retired: the Awepeeps server now uses the plain Paper/Floodgate whitelist plus
  [DiscordSRV](https://github.com/DiscordSRV/DiscordSRV) for whitelist-request
  notifications and approvals. A maintained, widely used plugin replaces this
  custom duplicate of Paper's own whitelist handling; the code is kept public for
  reference only and should not be installed. No code changes; the plugin
  version is unchanged.

## 1.0.1

- Fix a crash-on-enable NullPointerException: whitelist enforcement is now read
  from the Paper API (`Server.hasWhitelist()` / `Server.isWhitelistEnforced()`)
  instead of guessing `server.properties` through `getDataFolder().getParent()`.
- Make every plugin path absolute and null-parent safe (request store, audit
  log, corrupt-store backup, atomic-move temp files), so a relative Paper data
  folder can no longer disable the plugin.
- Report a missing webhook secret file as the normal "not configured yet" state
  with a single clear WARN line naming the file and environment variable; give
  unreadable, empty, and invalid files their own distinct warnings.
- Ensure `onEnable`, `onDisable`, and `/wlgate reload` never propagate an
  environment failure (missing file, unreadable directory, relative paths,
  absent Floodgate, absent webhook).
- Add `.gitattributes` and Windows/Linux reproducible-build verification in CI.
- Add unit tests for path resolution, null-parent handling, and every webhook
  secret-file state.

## 1.0.0

- Add fail-closed vanilla whitelist request capture and local approve/deny commands.
- Add optional Discord incoming-webhook notices, bounded request handling,
  durable storage, audit retention, and optional Floodgate UUID classification.
- Document deployment, privacy, and the future bot integration seam.
