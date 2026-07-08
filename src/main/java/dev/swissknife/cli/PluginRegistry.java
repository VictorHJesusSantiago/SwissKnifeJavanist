package dev.swissknife.cli;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Descobre e carrega plugins de terceiros via ServiceLoader a partir de JARs em
 * .swissknife/plugins/ (ou no diretório configurado por plugins.directory).
 */
public final class PluginRegistry {
    private final Map<String, SwissKnifePlugin> plugins;

    private PluginRegistry(Map<String, SwissKnifePlugin> plugins) { this.plugins = plugins; }

    public static PluginRegistry load(Path pluginsDirectory) throws IOException {
        Map<String, SwissKnifePlugin> found = new LinkedHashMap<>();
        if (pluginsDirectory == null || !Files.isDirectory(pluginsDirectory)) return new PluginRegistry(found);
        List<URL> urls = new ArrayList<>();
        try (Stream<Path> jars = Files.list(pluginsDirectory)) {
            for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).toList())
                urls.add(jar.toUri().toURL());
        }
        if (urls.isEmpty()) return new PluginRegistry(found);
        URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), PluginRegistry.class.getClassLoader());
        ServiceLoader.load(SwissKnifePlugin.class, loader).forEach(plugin -> found.put(plugin.command(), plugin));
        return new PluginRegistry(found);
    }

    public boolean has(String command) { return plugins.containsKey(command); }
    public Object execute(String[] args, CliConfig config) throws Exception { return plugins.get(args[0]).execute(args, config); }
    public Collection<SwissKnifePlugin> all() { return plugins.values(); }
}
