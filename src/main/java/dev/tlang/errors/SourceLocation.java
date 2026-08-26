package dev.tlang.errors;

import dev.tlang.lexer.SourceUnit;
import dev.tlang.lexer.Token;

/** Immutable source coordinate with optional source text for diagnostics. */
public record SourceLocation(String sourceName, String source, int line, int column) {
    private static final SourceLocation UNKNOWN = new SourceLocation("", null, 0, 0);

    public SourceLocation {
        sourceName = sourceName == null ? "" : sourceName;
    }

    public static SourceLocation from(Token token) {
        if (token == null) {
            return UNKNOWN;
        }
        SourceUnit unit = token.getSourceUnit();
        return new SourceLocation(unit.name(), unit.source(), token.getLine(), token.getColumn());
    }

    public static SourceLocation unknown() {
        return UNKNOWN;
    }

    public String displaySourceName() {
        return sourceName.isBlank() ? "script" : sourceName;
    }

    public boolean hasPosition() {
        return line > 0 && column > 0;
    }
}
