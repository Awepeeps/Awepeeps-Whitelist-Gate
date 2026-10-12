package com.awepeeps.whitelistgate;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import com.awepeeps.whitelistgate.core.Platform;
import com.awepeeps.whitelistgate.core.RequestBook;
import com.awepeeps.whitelistgate.core.RequestState;
import com.awepeeps.whitelistgate.core.SecretScrubber;
import com.awepeeps.whitelistgate.notify.DiscordWebhookNotifier;
import com.awepeeps.whitelistgate.notify.NoopRequestNotifier;
import com.awepeeps.whitelistgate.notify.RequestNotifier;
import com.awepeeps.whitelistgate.notify.WebhookClient;
import com.awepeeps.whitelistgate.storage.AuditLog;
import com.awepeeps.whitelistgate.storage.FileRequestStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.bukkit.Bukkit;
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
    private boolean enforceReadWarningLogged;

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
        getServer().getScheduler().runTaskTimer(this, approvalService::expireRequests,
                20L, 20L * 60L);
        for (ApprovalRequest request : requestBook.pending()) {
            notifier.notifyRequest(request, messageId ->
                    approvalService.messageIdSaved(request.requestId(), messageId));
        }
        checkWhitelistSafety();
        if (!isWebhookConfigured()) {
            getLogger().warning("No Discord webhook configured; requests will be stored but not sent.");
        }
    }

    private void createStorage() {
        Path directory = getDataFolder().toPath();
        requestStore = new FileRequestStore(directory.resolve("requests.properties"),
                (backup, cause) -> {
                    String backupName = backup == null ? "unavailable" : backup.getFileName().toString();
                    getLogger().warning("Corrupt request store was backed up as "
                            + backupName + "; starting with an empty request store.");
                });
        auditLog = new AuditLog(directory.resolve("decisions.log"));
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
        URI webhook = readWebhookUri();
        if (webhook == null) {
            notifier = new NoopRequestNotifier();
        } else {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            WebhookClient client = new WebhookClient(webhook, http, Duration.ofSeconds(8));
            notifier = new DiscordWebhookNotifier(client, getLogger(), maxPending(),
                    boundedInt("request.max-discord-messages-per-minute", 5, 1, 60));
        }
        if (approvalService != null) {
            approvalService.setNotifier(notifier);
        }
    }

    private URI readWebhookUri() {
        String environmentName = getConfig().getString(
                "secrets.webhook-environment", "WLGATE_DISCORD_WEBHOOK_URL");
        environmentName = environmentName == null ? "" : environmentName.trim();
        String secret = environmentName.isEmpty() ? null : System.getenv(environmentName);
        if (secret == null || secret.isBlank()) {
            String file = getConfig().getString("secrets.webhook-file", "");
            file = file == null ? "" : file.trim();
            if (!file.isEmpty()) {
                try {
                    secret = Files.readString(Path.of(file)).trim();
                } catch (IOException | RuntimeException failure) {
                    getLogger().warning("Webhook secret file could not be read: "
                            + SecretScrubber.safeError(failure));
                }
            }
        }
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(secret);
            if (!WebhookClient.isDiscordWebhook(uri)) {
                getLogger().warning("Webhook secret is not a valid HTTPS Discord webhook URL; notifications disabled.");
                return null;
            }
            return uri;
        } catch (IllegalArgumentException failure) {
            getLogger().warning("Webhook secret is not a valid HTTPS Discord webhook URL; notifications disabled.");
            return null;
        }
    }

    public void reloadSettings() {
        loadConfiguration();
        requestBook.configure(maxPending(), notificationCooldown(), deniedCooldown(), pendingTtl());
        reloadNotifier();
        for (ApprovalRequest request : requestBook.pending()) {
            notifier.notifyRequest(request, messageId ->
                    approvalService.messageIdSaved(request.requestId(), messageId));
        }
        checkWhitelistSafety();
        if (!isWebhookConfigured()) {
            getLogger().warning("No Discord webhook configured; requests will be stored but not sent.");
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

    public boolean enforceWhitelist() {
        Path propertiesPath = getDataFolder().toPath().getParent().getParent()
                .resolve("server.properties");
        Properties properties = new Properties();
        try (var input = Files.newInputStream(propertiesPath)) {
            properties.load(input);
            return Boolean.parseBoolean(properties.getProperty("enforce-whitelist", "false"));
        } catch (IOException failure) {
            if (!enforceReadWarningLogged) {
                enforceReadWarningLogged = true;
                getLogger().warning("Could not read enforce-whitelist from server.properties.");
            }
            return false;
        }
    }

    public boolean isWebhookConfiguredForStatus() {
        return isWebhookConfigured();
    }

    private void checkWhitelistSafety() {
        if (!getServer().hasWhitelist() || !enforceWhitelist()) {
            getLogger().severe("WARNING: Vanilla whitelist enforcement is not fully enabled. "
                    + "This plugin will not change it or allow players.");
        }
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
        if (approvalService != null) {
            approvalService.close();
        }
    }
}
