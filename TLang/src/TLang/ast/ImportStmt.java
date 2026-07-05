package TLang.ast;

import TLang.lexer.Token;

/**
 * Import statement: import <moduleName>;
 */
public final class ImportStmt extends Stmt {

    private final Token name;

    public ImportStmt(Token name) {
        this.name = name;
    }

    public Token getName() {
        return name;
    }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitImportStmt(this);
    }
}
