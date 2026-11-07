package br.com.cobbleverse.generations.rules;

import java.util.Locale;
import java.util.regex.Pattern;

public final class FormNames {
    private static final Pattern FORM_SEPARATOR = Pattern.compile("[-\\s]+");
    private static final Pattern INACTIVE_MEGA = Pattern.compile(".*(?:=|_|-)(?:none|false|normal|base)$");
    private FormNames() {}
    public static String canonical(String form) {
        if (form == null) return "normal";
        String trimmed = form.trim();
        boolean simple = true;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                simple = false;
                break;
            }
        }
        String key = simple ? trimmed : FORM_SEPARATOR.matcher(trimmed.toLowerCase(Locale.ROOT)).replaceAll("_");
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
