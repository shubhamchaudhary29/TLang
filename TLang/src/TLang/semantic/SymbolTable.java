package TLang.semantic;

import java.util.ArrayList;
import java.util.List;

public final class SymbolTable {
    private final List<Scope> scopes = new ArrayList<>();

    public SymbolTable() {
        // Root global scope
        beginScope();
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
