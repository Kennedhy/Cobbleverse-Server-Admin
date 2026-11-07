package br.com.cobbleverse.generations.rules;

import java.util.Locale;
import java.util.regex.Pattern;

public final class FormNames {
    private static final Pattern FORM_SEPARATOR = Pattern.compile("[-\\s]+");
    private static final Pattern INACTIVE_MEGA = Pattern.compile(".*(?:=|_|-)(?:none|false|normal|base)$");
    private FormNames() {}
    public static String canonical(String form) {
        if (form == null) return "normal";
        String key = form.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            // Same separators as [-\s]+ with the default (ASCII) Java regex flags.
            if (c == '-' || c == ' ' || (c >= '\t' && c <= '\r')) {
                return alias(FORM_SEPARATOR.matcher(key).replaceAll("_"));
            }
        }
        return alias(key);
    }

    private static String alias(String key) {
        return switch(key) {
            case "alolan" -> "alola";
            case "galarian" -> "galar";
            case "hisuian" -> "hisui";
            case "paldean" -> "paldea";
            default -> key;
        };
    }
    public static boolean activeMega(String token) {
        String key=token.toLowerCase(Locale.ROOT);
        if (!key.contains("mega") && !key.contains("primal")) return false;
        if (INACTIVE_MEGA.matcher(key).matches()) return false;
        return true;
    }
}
