package dev.swissknife.cli;

import java.util.regex.Pattern;

/**
 * Suporte minimo a cor ANSI no terminal, respeitando NO_COLOR (https://no-color.org)
 * e a chave de configuracao output.color (auto|always|never).
 */
public final class Ansi {
    private Ansi() {}

    private static final char ESC_CHAR = (char) 27;
    private static final String RESET = ESC_CHAR + "[0m";
    private static final String KEY_COLOR = ESC_CHAR + "[36m";
    private static final String STRING_COLOR = ESC_CHAR + "[32m";
    private static final String NUMBER_COLOR = ESC_CHAR + "[33m";
    private static final Pattern LINE = Pattern.compile("^(\\s*(?:- )?)([^:\\n]+)(:\\s?)(.*)$");

    public static boolean enabled(CliConfig config) {
        String mode = config.get("output.color", "auto").toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("never")) return false;
        if (mode.equals("always")) return true;
        if (System.getenv("NO_COLOR") != null) return false;
        return System.console() != null;
    }

    /** Coloriza saidas no formato "chave: valor" (text/yaml), linha a linha. */
    public static String colorize(String rendered, boolean enabled) {
        if (!enabled || rendered == null || rendered.isEmpty()) return rendered;
        StringBuilder out = new StringBuilder();
        for (String line : rendered.split("\n", -1)) {
            var matcher = LINE.matcher(line);
            if (matcher.matches()) {
                String prefix = matcher.group(1), key = matcher.group(2), sep = matcher.group(3), value = matcher.group(4);
                out.append(prefix).append(KEY_COLOR).append(key).append(RESET).append(sep);
                if (!value.isBlank()) out.append(colorValue(value));
                out.append('\n');
            } else out.append(line).append('\n');
        }
        if (!rendered.endsWith("\n") && out.length() > 0) out.setLength(out.length() - 1);
        return out.toString();
    }

    private static String colorValue(String value) {
        String trimmed = value.trim();
        if (trimmed.equals("true") || trimmed.equals("false") || trimmed.matches("-?\\d+(\\.\\d+)?"))
            return NUMBER_COLOR + value + RESET;
        return STRING_COLOR + value + RESET;
    }
}
