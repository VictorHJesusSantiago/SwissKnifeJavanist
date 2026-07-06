package dev.swissknife.intellij;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.notification.*;
import com.intellij.openapi.project.Project;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

final class SwissKnifeRunner {
    private SwissKnifeRunner() {}

    static void run(Project project, String... arguments) {
        var base = Path.of(project.getBasePath());
        var jar = locateJar(base);
        if (!Files.exists(jar)) {
            notify(project, "JAR não encontrado. Compile o projeto ou configure SWISSKNIFE_JAR.", NotificationType.WARNING);
            return;
        }
        try {
            var command = new GeneralCommandLine()
                .withExePath("java").withParameters("-jar", jar.toString())
                .withParameters(arguments).withWorkDirectory(base.toFile())
                .withCharset(StandardCharsets.UTF_8);
            var handler = new CapturingProcessHandler(command);
            var result = handler.runProcess(timeout());
            String content = result.getExitCode() == 0 ? result.getStdout() : result.getStderr();
            if (content.length() > 10_000) content = content.substring(0, 10_000) + "\n… saída truncada";
            notify(project, content,
                result.getExitCode() == 0 ? NotificationType.INFORMATION : NotificationType.ERROR);
        } catch (Exception e) {
            notify(project, e.getMessage(), NotificationType.ERROR);
        }
    }

    private static Path locateJar(Path base) {
        String configured = System.getenv("SWISSKNIFE_JAR");
        if (configured != null && !configured.isBlank() && Files.isRegularFile(Path.of(configured)))
            return Path.of(configured).toAbsolutePath().normalize();
        for (Path candidate : new Path[] {
            base.resolve("build/swissknife.jar"),
            base.resolve(".swissknife/swissknife.jar"),
            Path.of(System.getProperty("user.home"), ".swissknife", "swissknife.jar")
        }) if (Files.isRegularFile(candidate)) return candidate;
        return base.resolve("build/swissknife.jar");
    }
    private static int timeout() {
        String value = System.getenv("SWISSKNIFE_IDE_TIMEOUT_MS");
        try { return value == null ? 120_000 : Integer.parseInt(value); }
        catch (NumberFormatException e) { return 120_000; }
    }

    private static void notify(Project project, String content, NotificationType type) {
        new Notification("SwissKnife", "SwissKnife Javanist", content, type).notify(project);
    }
}
