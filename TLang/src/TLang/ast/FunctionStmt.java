package TLang.ast;

import java.util.List;
import TLang.lexer.Token;

/**
 * Function definition statement:
 *   define <name> taking <params>
 *       <body>
 */
public final class FunctionStmt extends Stmt {

    private final Token name;
    private final List<Token> params;
    private final List<Stmt> body;

    public FunctionStmt(Token name, List<Token> params, List<Stmt> body) {
        this.name = name;
        this.params = params;
        this.body = body;
    }

    public Token getName()         { return name; }
    public List<Token> getParams() { return params; }
    public List<Stmt> getBody()    { return body; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitFunctionStmt(this);
    }
}
