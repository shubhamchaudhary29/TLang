package TLang.ast;

import java.util.List;
import TLang.lexer.Token;

/**
 * Function call expression: callee(arg1, arg2, ...).
 */
public final class CallExpr extends Expr {

    private final Expr callee;
    private final Token paren;
    private final List<Expr> arguments;

    public CallExpr(Expr callee, Token paren, List<Expr> arguments) {
        this.callee = callee;
        this.paren = paren;
        this.arguments = arguments;
    }

    public Expr getCallee()          { return callee; }
    public Token getParen()          { return paren; }
    public List<Expr> getArguments() { return arguments; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitCallExpr(this);
    }
}
