package dev.swissknife.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;

/** Comandos operacionais autocontidos da plataforma CLI. */
public final class CliTools {
    private CliTools() {}

    public static Map<String, Object> doctor(CliConfig config, Path cwd) {
        List<Map<String, Object>> checks = new ArrayList<>();
        check(checks, "java", Runtime.version().feature() >= 21,
            "Java " + Runtime.version(), "Instale JDK 21 ou superior.");
        check(checks, "diretorio", Files.isDirectory(cwd) && Files.isReadable(cwd),
            cwd.toString(), "Verifique o diretório de trabalho.");
        check(checks, "escrita", Files.isWritable(cwd), cwd.toString(),
            "Conceda permissão de escrita para gerar relatórios.");
        check(checks, "configuracao", true,
            config.sources().isEmpty() ? "defaults internos" : config.sources().toString(), "");
        int drivers = (int) DriverManager.drivers().count();
        check(checks, "drivers-jdbc", drivers > 0, drivers + " driver(s) disponível(is)",
            "Adicione o driver JDBC ao classpath antes de migrar bancos.");
        Path ignore = cwd.resolve(config.get("scan.ignoreFile", ".swissknifeignore"));
        check(checks, "ignore", true, Files.exists(ignore) ? ignore.toString() : "não configurado", "");
        long failed = checks.stream().filter(c -> !Boolean.TRUE.equals(c.get("ok"))).count();
        return new LinkedHashMap<>(Map.of("healthy", failed == 0, "failed", failed,
            "profile", config.get("profile", "development"), "checks", checks,
            "timestamp", Instant.now().toString()));
    }

    public static String completion(String shell) {
        String commands = "help " + String.join(" ", CommandCatalog.names());
        return switch (shell.toLowerCase(Locale.ROOT)) {
            case "bash" -> "_swissknife(){ COMPREPLY=( $(compgen -W '" + commands +
                "' -- \"${COMP_WORDS[1]}\") ); }; complete -F _swissknife swissknife\n";
            case "zsh" -> "#compdef swissknife\n_arguments '1:command:(" + commands.replace(' ', '\n') + ")'\n";
            case "fish" -> Arrays.stream(commands.split(" "))
                .map(c -> "complete -c swissknife -f -a " + c).reduce("", (a, b) -> a + b + "\n");
            case "powershell", "pwsh" -> """
                Register-ArgumentCompleter -CommandName swissknife,swissknife.cmd -ScriptBlock {
                  param($commandName,$wordToComplete)
                  '%s'.Split(' ') | Where-Object { $_ -like "$wordToComplete*" } |
                    ForEach-Object { [System.Management.Automation.CompletionResult]::new($_,$_,'ParameterValue',$_) }
                }
                """.formatted(commands);
            default -> throw new IllegalArgumentException("Shell inválido: " + shell +
                ". Use bash, zsh, fish ou powershell.");
        };
    }

    public static List<List<String>> pipeline(Path file) throws IOException {
        List<List<String>> commands = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            commands.add(tokenize(trimmed));
        }
        return commands;
    }

    public static List<String> tokenize(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean single = false, dbl = false, escape = false;
        for (char c : command.toCharArray()) {
            if (escape) { token.append(c); escape = false; continue; }
            if (c == '\\' && !single) { escape = true; continue; }
            if (c == '\'' && !dbl) { single = !single; continue; }
            if (c == '"' && !single) { dbl = !dbl; continue; }
            if (Character.isWhitespace(c) && !single && !dbl) {
                if (!token.isEmpty()) { result.add(token.toString()); token.setLength(0); }
            } else token.append(c);
        }
        if (single || dbl) throw new IllegalArgumentException("Aspas não terminadas no pipeline: " + command);
        if (!token.isEmpty()) result.add(token.toString());
        if (result.isEmpty()) throw new IllegalArgumentException("Comando vazio no pipeline");
        return result;
    }

    private static void check(List<Map<String, Object>> checks, String name, boolean ok,
                              String detail, String suggestion) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("name", name); check.put("ok", ok); check.put("detail", detail);
        if (!ok && !suggestion.isBlank()) check.put("suggestion", suggestion);
        checks.add(check);
    }
}
