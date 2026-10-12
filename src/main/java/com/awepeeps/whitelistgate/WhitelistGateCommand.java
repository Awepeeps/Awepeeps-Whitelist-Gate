package com.awepeeps.whitelistgate;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import com.awepeeps.whitelistgate.core.RequestDecision;
import com.awepeeps.whitelistgate.core.RequestState;
import com.awepeeps.whitelistgate.core.TextSafety;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;

public final class WhitelistGateCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final List<String> SUBCOMMANDS =
            List.of("list", "approve", "deny", "forget", "reload", "status");
    private final WhitelistGatePlugin plugin;

    public WhitelistGateCommand(WhitelistGatePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission("wlgate.admin")) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("Usage: /wlgate <list|approve|deny|forget|reload|status>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "approve" -> decide(sender, args, RequestDecision.APPROVED);
            case "deny" -> decide(sender, args, RequestDecision.DENIED);
            case "forget" -> forget(sender, args);
            case "reload" -> {
                plugin.reloadSettings();
                sender.sendMessage("Whitelist Gate configuration reloaded.");
            }
            case "status" -> status(sender);
            default -> sender.sendMessage("Unknown subcommand. Use /wlgate list, approve, deny, forget, reload, or status.");
        }
        return true;
    }

    private void list(CommandSender sender) {
        List<ApprovalRequest> pending = plugin.requestBook().pending();
        if (pending.isEmpty()) {
            sender.sendMessage("There are no pending whitelist requests.");
            return;
        }
        sender.sendMessage("Pending whitelist requests (" + pending.size() + "):");
        for (ApprovalRequest request : pending) {
            sender.sendMessage(request.requestId() + " | "
                    + TextSafety.commandText(request.name()) + " | " + request.platform()
                    + " | attempts " + request.attempts() + " | last seen "
                    + TIME.format(request.lastSeen()));
        }
    }

    private void decide(CommandSender sender, String[] args, RequestDecision decision) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /wlgate "
                    + (decision == RequestDecision.APPROVED ? "approve" : "deny")
                    + " <requestId|name>");
            return;
        }
        String actor = sender instanceof ConsoleCommandSender ? "CONSOLE" : sender.getName();
        plugin.approvalService().decide(args[1], decision, actor,
                result -> sender.sendMessage(result.message()));
    }

    private void forget(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /wlgate forget <requestId|name>");
            return;
        }
        ApprovalRequest removed = plugin.approvalService().forget(args[1]);
        sender.sendMessage(removed == null
                ? "No matching request was found."
                : "Request " + removed.requestId() + " was forgotten.");
    }

    private void status(CommandSender sender) {
        boolean whitelist = plugin.getServer().hasWhitelist();
        boolean enforce = plugin.enforceWhitelist();
        sender.sendMessage("Whitelist enabled: " + whitelist);
        sender.sendMessage("Enforce whitelist: " + enforce);
        sender.sendMessage("Webhook configured: " + plugin.isWebhookConfiguredForStatus());
        sender.sendMessage("Floodgate detected: " + plugin.floodgateDetected());
        sender.sendMessage("Pending requests: " + plugin.requestBook().pendingCount()
                + " / " + plugin.maxPending());
        sender.sendMessage("Dropped requests (capacity): "
                + plugin.requestBook().droppedRequests());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission("wlgate.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && List.of("approve", "deny", "forget")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> choices = new ArrayList<>();
            for (ApprovalRequest request : plugin.requestBook().pending()) {
                choices.add(request.requestId());
                choices.add(TextSafety.commandText(request.name()));
            }
            return filter(choices, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
