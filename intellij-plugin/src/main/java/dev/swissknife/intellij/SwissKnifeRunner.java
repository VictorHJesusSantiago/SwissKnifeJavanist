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
        var jar = base.resolve("build/swissknife.jar");
        if (!Files.exists(jar)) {
            notify(project, "Compile o projeto com build.ps1 antes de usar o plugin.", NotificationType.WARNING);
            return;
        }
        try {
            var command = new GeneralCommandLine()
                .withExePath("java").withParameters("-jar", jar.toString())
                .withParameters(arguments).withWorkDirectory(base.toFile())
                .withCharset(StandardCharsets.UTF_8);
            var handler = new CapturingProcessHandler(command);
            var result = handler.runProcess(60_000);
            notify(project, result.getExitCode() == 0 ? result.getStdout() : result.getStderr(),
                result.getExitCode() == 0 ? NotificationType.INFORMATION : NotificationType.ERROR);
        } catch (Exception e) {
            notify(project, e.getMessage(), NotificationType.ERROR);
        }
    }

    private static void notify(Project project, String content, NotificationType type) {
        new Notification("SwissKnife", "SwissKnife Javanist", content, type).notify(project);
    }
}
