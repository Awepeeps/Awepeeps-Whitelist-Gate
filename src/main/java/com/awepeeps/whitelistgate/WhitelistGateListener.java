package com.awepeeps.whitelistgate;

import com.awepeeps.whitelistgate.core.Platform;
import com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Observes Paper's own whitelist decision for every login.
 *
 * <p>{@link ProfileWhitelistVerifyEvent} is the Paper 26.3 event Paper fires
 * while it verifies the whitelist: it carries the authenticated profile (so a
 * Bedrock player arrives with their Floodgate UUID) and the exact whitelist
 * decision. This listener never grants access; it only records a denied login
 * and swaps the kick message for a friendlier one. If this plugin is absent or
 * fails to load, Paper's whitelist still denies the login unchanged.</p>
 */
public final class WhitelistGateListener implements Listener {
    private final WhitelistGatePlugin plugin;

    public WhitelistGateListener(WhitelistGatePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProfileWhitelistVerify(ProfileWhitelistVerifyEvent event) {
        // Whitelist off means the player is already allowed; only observe real denials.
        if (!event.isWhitelistEnabled() || event.isWhitelisted()) {
            return;
        }
        UUID uuid = event.getPlayerProfile().getId();
        if (uuid == null) {
            return;
        }
        String name = event.getPlayerProfile().getName();
        if (name == null || name.isBlank()) {
            name = "unknown";
        }
        Platform platform = plugin.isBedrock(uuid) ? Platform.BEDROCK : Platform.JAVA;
        plugin.approvalService().recordAttempt(uuid, name, platform);
        event.kickMessage(Component.text(plugin.kickMessage()));
    }
}
