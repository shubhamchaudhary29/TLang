package dev.tlang.resolver;

public final class Symbol {
    private final String name;
    private final SymbolKind kind;
    private final int line;

    public Symbol(String name, SymbolKind kind, int line) {
        this.name = name;
        this.kind = kind;
        this.line = line;
    }

    public String getName() { return name; }
    public SymbolKind getKind() { return kind; }
    public int getLine() { return line; }
}
