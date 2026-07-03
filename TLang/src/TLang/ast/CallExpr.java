package TLang.ast;

import java.util.List;
import TLang.lexer.Token;

/**
 * Function call expression: callee(arg1, arg2, ...).
 */
public final class CallExpr extends Expr {

    private final Token name;
    private final List<Expr> arguments;

    public CallExpr(Token name, List<Expr> arguments) {
        this.name = name;
        this.arguments = arguments;
    }

    public Token getName()           { return name; }
    public List<Expr> getArguments() { return arguments; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitCallExpr(this);
    }
}
