package com.awepeeps.whitelistgate;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import com.awepeeps.whitelistgate.core.Platform;
import com.awepeeps.whitelistgate.core.RequestBook;
import com.awepeeps.whitelistgate.core.RequestState;
import com.awepeeps.whitelistgate.core.SecretScrubber;
import com.awepeeps.whitelistgate.core.WhitelistState;
import com.awepeeps.whitelistgate.notify.DiscordWebhookNotifier;
import com.awepeeps.whitelistgate.notify.NoopRequestNotifier;
import com.awepeeps.whitelistgate.notify.RequestNotifier;
import com.awepeeps.whitelistgate.notify.WebhookClient;
import com.awepeeps.whitelistgate.notify.WebhookSecretResolver;
import com.awepeeps.whitelistgate.storage.AuditLog;
import com.awepeeps.whitelistgate.storage.FileRequestStore;
import com.awepeeps.whitelistgate.storage.StoragePaths;
import com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class WhitelistGatePlugin extends JavaPlugin {
    private RequestBook requestBook;
    private ApprovalService approvalService;
    private FileRequestStore requestStore;
    private AuditLog auditLog;
    private RequestNotifier notifier;
    private Duration auditRetention = Duration.ofDays(90);
    private String kickMessage;
    private boolean floodgateDetected;
    private FloodgateSupport floodgateSupport;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createStorage();
        loadConfiguration();
        requestBook = new RequestBook(loadRequests(), maxPending(),
                notificationCooldown(), deniedCooldown(), pendingTtl());
        approvalService = new ApprovalService(this, requestBook, requestStore, auditLog);
        reloadNotifier();

        PluginCommand command = getCommand("wlgate");
        if (command == null) {
            getLogger().severe("Command registration is missing; disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        WhitelistGateCommand executor = new WhitelistGateCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(
                new WhitelistGateListener(this), this);
        getLogger().info("Whitelist-denial listener registered (event "
                + ProfileWhitelistVerifyEvent.class.getName() + ").");
        getServer().getScheduler().runTaskTimer(this, approvalService::expireRequests,
                20L, 20L * 60L);
        for (ApprovalRequest request : requestBook.pending()) {
            notifier.notifyRequest(request, messageId ->
                    approvalService.messageIdSaved(request.requestId(), messageId));
        }
        checkWhitelistSafety();
    }

    private void createStorage() {
        // getDataFolder() is relative on Paper ("plugins/Awepeeps-Whitelist-Gate");
        // StoragePaths absolutises it so no unguarded getParent() is ever needed.
        Path directory = StoragePaths.dataDirectory(
                getDataFolder() == null ? null : getDataFolder().toPath());
        requestStore = new FileRequestStore(
                StoragePaths.fileInDataFolder(directory, "requests.properties"),
                (backup, cause) -> {
                    String backupName = backup == null ? "unavailable" : backup.getFileName().toString();
                    getLogger().warning("Corrupt request store was backed up as "
                            + backupName + "; starting with an empty request store.");
                });
        auditLog = new AuditLog(StoragePaths.fileInDataFolder(directory, "decisions.log"));
    }

    private List<ApprovalRequest> loadRequests() {
        try {
            return requestStore.load();
        } catch (IOException failure) {
            getLogger().warning("Request store could not be read: "
                    + SecretScrubber.safeError(failure) + "; starting empty.");
            return List.of();
        }
    }

    private void loadConfiguration() {
        reloadConfig();
        kickMessage = getConfig().getString("messages.whitelist-denied",
                "Your request was sent to the server owner. Please try again later.");
        auditRetention = Duration.ofDays(positiveLong("storage.audit-retention-days", 90));
        floodgateDetected = getServer().getPluginManager().isPluginEnabled("floodgate");
        floodgateSupport = floodgateDetected ? new FloodgateSupport() : null;
    }

    private void reloadNotifier() {
        if (notifier != null) {
            notifier.close();
        }
        // The resolver owns all webhook warnings so a missing file yields exactly
        // one clear line, never a second generic warning and never an exception.
        WebhookSecretResolver.Resolution resolution = WebhookSecretResolver.resolve(
                getConfig().getString("secrets.webhook-environment", "WLGATE_DISCORD_WEBHOOK_URL"),
                System::getenv,
                getConfig().getString("secrets.webhook-file", ""),
                getLogger());
        if (!resolution.configured()) {
            notifier = new NoopRequestNotifier();
        } else {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            WebhookClient client = new WebhookClient(resolution.uri(), http, Duration.ofSeconds(8));
            notifier = new DiscordWebhookNotifier(client, getLogger(), maxPending(),
                    boundedInt("request.max-discord-messages-per-minute", 5, 1, 60));
        }
        if (approvalService != null) {
            approvalService.setNotifier(notifier);
        }
    }

    public void reloadSettings() {
        try {
            loadConfiguration();
            requestBook.configure(maxPending(), notificationCooldown(), deniedCooldown(), pendingTtl());
            reloadNotifier();
            for (ApprovalRequest request : requestBook.pending()) {
                notifier.notifyRequest(request, messageId ->
                        approvalService.messageIdSaved(request.requestId(), messageId));
            }
            checkWhitelistSafety();
        } catch (RuntimeException failure) {
            // A bad environment must never take the plugin down on reload.
            getLogger().warning("Configuration reload failed: "
                    + SecretScrubber.safeError(failure));
        }
    }

    private boolean isWebhookConfigured() {
        return notifier instanceof DiscordWebhookNotifier;
    }

    public boolean floodgateDetected() {
        return floodgateDetected;
    }

    public boolean isBedrock(java.util.UUID uuid) {
        return floodgateDetected && floodgateSupport.isBedrock(uuid);
    }

    public String kickMessage() {
        return kickMessage;
    }

    public ApprovalService approvalService() {
        return approvalService;
    }

    public RequestBook requestBook() {
        return requestBook;
    }

    public Duration auditRetention() {
        return auditRetention;
    }

    public int maxPending() {
        return boundedInt("request.max-pending", 100, 1, 1000);
    }

    /**
     * Live vanilla whitelist state from the Paper API. Never reads
     * {@code server.properties} from a guessed path, and never throws:
     * an unreadable state becomes {@link WhitelistState.Enforcement#UNKNOWN}.
     */
    public WhitelistState whitelistState() {
        boolean whitelistEnabled;
        try {
            whitelistEnabled = getServer().hasWhitelist();
        } catch (RuntimeException failure) {
            return WhitelistState.unknown(false, SecretScrubber.safeError(failure));
        }
        try {
            return WhitelistState.known(whitelistEnabled, getServer().isWhitelistEnforced());
        } catch (RuntimeException failure) {
            return WhitelistState.unknown(whitelistEnabled, SecretScrubber.safeError(failure));
        }
    }

    public boolean isWebhookConfiguredForStatus() {
        return isWebhookConfigured();
    }

    private void checkWhitelistSafety() {
        WhitelistState state = whitelistState();
        if (state.fullyEnforced()) {
            return;
        }
        if (!state.enforcementKnown()) {
            getLogger().warning("WARNING: Could not determine whether the vanilla whitelist is "
                    + "enforced (" + state.detail() + "). This plugin will not change the whitelist "
                    + "settings or allow players.");
            return;
        }
        getLogger().severe("WARNING: Vanilla whitelist enforcement is not fully enabled "
                + "(whitelist=" + state.whitelistEnabled() + ", enforce-whitelist="
                + state.describeEnforcement() + "). This plugin will not change it or allow players.");
    }

    private Duration pendingTtl() {
        return Duration.ofHours(positiveLong("request.pending-ttl-hours", 24));
    }

    private Duration deniedCooldown() {
        return Duration.ofHours(nonNegativeLong("request.denied-rerequest-cooldown-hours", 24));
    }

    private Duration notificationCooldown() {
        return Duration.ofSeconds(nonNegativeLong("request.notification-cooldown-seconds", 300));
    }

    private int boundedInt(String key, int fallback, int minimum, int maximum) {
        int value = getConfig().getInt(key, fallback);
        if (value < minimum || value > maximum) {
            getLogger().warning("Invalid " + key + "; using default " + fallback + ".");
            return fallback;
        }
        return value;
    }

    private long positiveLong(String key, long fallback) {
        long value = getConfig().getLong(key, fallback);
        return value > 0 ? value : fallback;
    }

    private long nonNegativeLong(String key, long fallback) {
        long value = getConfig().getLong(key, fallback);
        return value >= 0 ? value : fallback;
    }

    @Override
    public void onDisable() {
        try {
            if (approvalService != null) {
                approvalService.close();
            }
        } catch (RuntimeException failure) {
            getLogger().warning("Shutdown cleanup failed: " + SecretScrubber.safeError(failure));
        }
    }
}
