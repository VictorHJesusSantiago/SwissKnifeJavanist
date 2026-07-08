package dev.swissknife.cli;

/** Fonte única da versão da suíte, usada pelo comando version, SARIF e completion. */
public final class Version {
    private Version() {}

    public static final String FALLBACK = "2.0.0-dev";

    public static String current() {
        var implementation = Version.class.getPackage().getImplementationVersion();
        return implementation == null ? FALLBACK : implementation;
    }
}
