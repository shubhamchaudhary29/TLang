package TLang.ast;

/**
 * Literal value expression: an integer or boolean constant.
 */
public final class LiteralExpr extends Expr {

    private final Object value;

    public LiteralExpr(Object value) {
        this.value = value;
    }

    public Object getValue() { return value; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitLiteralExpr(this);
    }
}
