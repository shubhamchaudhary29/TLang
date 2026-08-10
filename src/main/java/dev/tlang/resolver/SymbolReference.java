package dev.tlang.resolver;

public final class SymbolReference {
    private final int line;       // 1-indexed
    private final int column;     // 1-indexed
    private final int length;
    private final Symbol symbol;

    public SymbolReference(int line, int column, int length, Symbol symbol) {
        this.line = line;
        this.column = column;
        this.length = length;
        this.symbol = symbol;
    }

    public int getLine() { return line; }
    public int getColumn() { return column; }
    public int getLength() { return length; }
    public Symbol getSymbol() { return symbol; }
}
