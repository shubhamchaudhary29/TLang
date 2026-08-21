package dev.tlang.resolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SymbolTable {
    private static final List<String> BUILT_IN_FUNCTIONS = List.of(
        "delete_file", "file_exists", "now", "random", "read_file",
        "to_integer", "to_string", "type_of", "write_file"
    );

    private final List<Scope> scopes = new ArrayList<>();

    public SymbolTable() {
        // Root global scope
        beginScope();
        declareGlobalFunctions();
    }

    private void declareGlobalFunctions() {
        for (String name : BUILT_IN_FUNCTIONS) {
            declare(new Symbol(name, SymbolKind.FUNCTION, 0, 0));
        }
    }

    /** Names shared by semantic resolution and editor tooling. */
    public static List<String> getBuiltInFunctionNames() {
        return Collections.unmodifiableList(BUILT_IN_FUNCTIONS);
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
        Scope currentScope = scopes.get(scopes.size() - 1);
        boolean success = currentScope.declare(symbol);
        if (success) {
            symbol.setScope(currentScope);
        }
        return success;
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
