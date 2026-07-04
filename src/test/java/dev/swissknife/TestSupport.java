package dev.swissknife;

import java.util.Objects;

public final class TestSupport {
    private TestSupport() {}
    public static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Esperado " + expected + ", obtido " + actual);
    }
    public static void truth(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
