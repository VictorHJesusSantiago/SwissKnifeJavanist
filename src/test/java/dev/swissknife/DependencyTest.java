package dev.swissknife;

import dev.swissknife.dependencies.DependencyVisualizer;
import java.nio.file.*;

public final class DependencyTest {
    public static void run() throws Exception {
        var root = Files.createTempDirectory("deps-test");
        Files.createDirectories(root.resolve("orders"));
        Files.writeString(root.resolve("orders/service.properties"), "name=orders\ndepends=payments,catalog\n");
        var visualizer = new DependencyVisualizer();
        var graph = visualizer.analyze(root);
        TestSupport.equal(1, graph.services().size());
        TestSupport.equal(2, graph.edges().size());
    }
}
