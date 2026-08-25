package dev.tlang.errors;

import dev.tlang.lexer.Token;

/** A request-safe module failure that retains CLI diagnostic and exit-code metadata. */
public final class ModuleLoadError extends RuntimeError {
    private final String source;
    private final String sourceName;
    private final int line;
    private final int column;
    private final String diagnosticKind;
    private final String rawMessage;
    private final int exitCode;

    public ModuleLoadError(
            Token importToken,
            String message,
            String source,
            String sourceName,
            int line,
            int column,
            String diagnosticKind,
            String rawMessage,
            int exitCode) {
        super(importToken, message);
        this.source = source;
        this.sourceName = sourceName;
        this.line = line;
        this.column = column;
        this.diagnosticKind = diagnosticKind;
        this.rawMessage = rawMessage;
        this.exitCode = exitCode;
    }

    public String getSource() { return source; }
    public String getSourceName() { return sourceName; }
    public int getLine() { return line; }
    public int getColumn() { return column; }
    public String getDiagnosticKind() { return diagnosticKind; }
    public String getRawMessage() { return rawMessage; }
    public int getExitCode() { return exitCode; }
}
