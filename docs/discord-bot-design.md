# Historical proposal: Discord bot approval channel

This document records an earlier design idea only. It is not an active roadmap
or implementation plan. New server setups use owner-reviewed Paper and
Floodgate native whitelist commands; they require no custom Discord bot,
webhook, or account-linking integration. Runtime removal of the existing Gate
plugin remains pending an isolated compatibility check and guarded zero-player
maintenance.

The historical plugin used an incoming webhook only for outbound
notifications. Its `RequestNotifier` interface and
`ApprovalService.decide(...)` method remain in the source as a possible extension
seam, but no bot implementation is currently planned.

If this proposal is revisited, any bot would need to use an outbound Discord
Gateway connection only and avoid an inbound listener. It would need no
privileged intents. Buttons should contain only an opaque request ID, not a
player name or UUID. Each click would need guild/channel and user/role checks
before calling the shared decision service, which should reject expired,
already-decided, unknown, or replayed request IDs.

Any future implementation should grant only View Channel, Send Messages, and
Embed Links in its approval channel, with Read Message History only if needed.
It should not receive administrator, Manage Server, or console-command access;
credentials must stay outside config and source control.
