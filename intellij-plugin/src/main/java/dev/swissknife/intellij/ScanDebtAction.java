package dev.swissknife.intellij;

import com.intellij.openapi.actionSystem.*;
import org.jetbrains.annotations.NotNull;

public final class ScanDebtAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project != null) SwissKnifeRunner.run(project, "debt", "src");
    }
}
