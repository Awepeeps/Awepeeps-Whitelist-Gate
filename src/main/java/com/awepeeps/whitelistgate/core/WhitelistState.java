package com.awepeeps.whitelistgate.core;

/**
 * The server's vanilla whitelist state, as reported by the Paper API.
 *
 * <p>The plugin never reads {@code server.properties} itself: Paper exposes the
 * live values through {@code Server.hasWhitelist()} and
 * {@code Server.isWhitelistEnforced()}. When those accessors cannot be read, the
 * state is {@link Enforcement#UNKNOWN} and callers warn rather than throw.</p>
 */
public record WhitelistState(boolean whitelistEnabled, Enforcement enforcement, String detail) {
    public enum Enforcement {
        ENFORCED,
        NOT_ENFORCED,
        UNKNOWN
    }

    public static WhitelistState known(boolean whitelistEnabled, boolean enforceWhitelist) {
        return new WhitelistState(whitelistEnabled,
                enforceWhitelist ? Enforcement.ENFORCED : Enforcement.NOT_ENFORCED, null);
    }

    public static WhitelistState unknown(boolean whitelistEnabled, String reason) {
        return new WhitelistState(whitelistEnabled, Enforcement.UNKNOWN,
                reason == null || reason.isBlank() ? "not available" : reason);
    }

    public boolean enforcementKnown() {
        return enforcement != Enforcement.UNKNOWN;
    }

    public boolean enforceWhitelist() {
        return enforcement == Enforcement.ENFORCED;
    }

    /** True only when both the whitelist and its enforcement are definitely on. */
    public boolean fullyEnforced() {
        return whitelistEnabled && enforcement == Enforcement.ENFORCED;
    }

    /** Human-readable enforcement value for {@code /wlgate status}. */
    public String describeEnforcement() {
        return switch (enforcement) {
            case ENFORCED -> "true";
            case NOT_ENFORCED -> "false";
            case UNKNOWN -> "unknown (" + detail + ")";
        };
    }
}
