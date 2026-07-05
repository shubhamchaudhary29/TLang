package TLang.semantic;

import java.util.HashMap;
import java.util.Map;

public final class Scope {
    private final Map<String, Symbol> symbols = new HashMap<>();

    public boolean declare(Symbol symbol) {
        if (symbols.containsKey(symbol.getName())) {
            Symbol existing = symbols.get(symbol.getName());
            if (existing.getLine() == 0) {
                symbols.put(symbol.getName(), symbol);
                return true;
            }
            return false;
        }
        symbols.put(symbol.getName(), symbol);
        return true;
    }

    public Symbol get(String name) {
        return symbols.get(name);
    }

    public boolean contains(String name) {
        return symbols.containsKey(name);
    }
}
