package TLang.ast;

import java.util.List;

/**
 * AST node for a map literal: { key1: expr1, key2: expr2, ... }.
 */
public final class MapExpr extends Expr {

    private final List<String> keys;
    private final List<Expr> values;

    public MapExpr(List<String> keys, List<Expr> values) {
        this.keys = keys;
        this.values = values;
    }

    public List<String> getKeys() {
        return keys;
    }

    public List<Expr> getValues() {
        return values;
    }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitMapExpr(this);
    }
}
