package dev.tlang.lexer;

/** Immutable identity and contents for one parsed TLang source unit. */
public record SourceUnit(String name, String source) {
    private static final SourceUnit UNKNOWN = new SourceUnit("", null);

    public SourceUnit {
        name = name == null ? "" : name;
    }

    public static SourceUnit unknown() {
        return UNKNOWN;
    }

    public String displayName() {
        return name.isBlank() ? "script" : name;
    }
}
