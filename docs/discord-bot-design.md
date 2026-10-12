# Phase 2: Discord bot approval channel

Phase 1 deliberately uses an incoming webhook only for outbound notifications.
The `RequestNotifier` interface and `ApprovalService.decide(...)` method are the
extension seam: a future bot implementation can replace or complement the
webhook notifier, and every approval still goes through the same state check,
audit write, and main-thread Paper whitelist mutation.

The future bot must use an outbound Discord Gateway connection only; it must
not open an inbound listener. It needs no privileged intents. Use buttons whose
custom IDs contain only an opaque request ID, not a player name or UUID. On
every click, verify the configured guild and channel, then check the clicker's
allowed user ID or moderator role ID before calling the shared decision
service. Reject expired, already-decided, unknown, or replayed request IDs.

Grant the bot only View Channel, Send Messages, and Embed Links in the private
approval channel. Add Read Message History only if the chosen implementation
demonstrably needs it. Do not grant administrator, Manage Server, or any
console-command access. Keep bot credentials outside config and source control.
