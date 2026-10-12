package com.awepeeps.whitelistgate.core;

import java.util.regex.Pattern;

public final class TextSafety {
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern MARKDOWN = Pattern.compile("([\\\\`*_{}\\[\\]()#+.!|>~-])");

    private TextSafety() {}

    public static String discord(String value) {
        String clean = CONTROL.matcher(value == null ? "" : value).replaceAll("");
        clean = clean.replace("@", "＠");
        return MARKDOWN.matcher(clean).replaceAll("\\\\$1");
    }

    public static String commandText(String value) {
        return CONTROL.matcher(value == null ? "" : value).replaceAll("");
    }
}
