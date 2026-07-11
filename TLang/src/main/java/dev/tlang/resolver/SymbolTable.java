package dev.tlang.resolver;

import java.util.ArrayList;
import java.util.List;

public final class SymbolTable {
    private final List<Scope> scopes = new ArrayList<>();

    public SymbolTable() {
        // Root global scope
        beginScope();
        declareGlobalFunctions();
    }

    private void declareGlobalFunctions() {
        declare(new Symbol("read_file", SymbolKind.FUNCTION, 0, 0));
        declare(new Symbol("write_file", SymbolKind.FUNCTION, 0, 0));
        declare(new Symbol("file_exists", SymbolKind.FUNCTION, 0, 0));
        declare(new Symbol("delete_file", SymbolKind.FUNCTION, 0, 0));
        declare(new Symbol("now", SymbolKind.FUNCTION, 0, 0));
        declare(new Symbol("random", SymbolKind.FUNCTION, 0, 0));
        declare(new Symbol("to_string", SymbolKind.FUNCTION, 0, 0));
        declare(new Symbol("to_integer", SymbolKind.FUNCTION, 0, 0));
        declare(new Symbol("type_of", SymbolKind.FUNCTION, 0, 0));
    }

    public void beginScope() {
        scopes.add(new Scope());
    }

    public void endScope() {
        if (scopes.size() > 1) {
            scopes.remove(scopes.size() - 1);
        }
    }

    public boolean declare(Symbol symbol) {
        if (scopes.isEmpty()) return false;
        return scopes.get(scopes.size() - 1).declare(symbol);
    }

    public Symbol resolve(String name) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            Scope scope = scopes.get(i);
            if (scope.contains(name)) {
                return scope.get(name);
            }
        }
        return null;
    }
}
