package dev.tlang.formatter;

import dev.tlang.ast.*;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;

import java.util.List;

/**
 * A syntax-preserving, canonical code formatter for TLang.
 * Walk the AST and output the canonical TLang source representation.
 */
public final class Formatter implements Stmt.Visitor, Expr.Visitor<String> {

    private StringBuilder builder = new StringBuilder();
    private int indentLevel = 0;

    public Formatter() {}

    /**
     * Format a list of statements into a canonical string.
     */
    public String format(List<Stmt> statements) {
        builder.setLength(0);
        indentLevel = 0;

        for (int i = 0; i < statements.size(); i++) {
            Stmt stmt = statements.get(i);
            if (stmt instanceof FunctionStmt) {
                ensureBlankLine();
            }
            builder.append(getIndent());
            stmt.accept(this);
            builder.append("\n");

            // Exactly one blank line after the last import statement at the top level
            if (stmt instanceof ImportStmt) {
                if (i + 1 < statements.size() && !(statements.get(i + 1) instanceof ImportStmt)) {
                    builder.append("\n");
                }
            }
        }
        return builder.toString();
    }

    // ── Stmt.Visitor Implementation ─────────────────────────────

    @Override
    public void visitVarStmt(VarStmt stmt) {
        builder.append("let ")
               .append(stmt.getName().getLexeme())
               .append(" be ")
               .append(stmt.getInitializer().accept(this));
    }

    @Override
    public void visitExpressionStmt(ExpressionStmt stmt) {
        builder.append(stmt.getExpression().accept(this));
    }

    @Override
    public void visitPrintStmt(PrintStmt stmt) {
        builder.append("show ")
               .append(stmt.getExpression().accept(this));
    }

    @Override
    public void visitBlockStmt(BlockStmt stmt) {
        indentLevel++;
        List<Stmt> statements = stmt.getStatements();
        for (int i = 0; i < statements.size(); i++) {
            Stmt s = statements.get(i);
            if (s instanceof FunctionStmt) {
                ensureBlankLine();
            }
            builder.append(getIndent());
            s.accept(this);
            if (i < statements.size() - 1) {
                builder.append("\n");
            }
        }
        indentLevel--;
    }

    @Override
    public void visitIfStmt(IfStmt stmt) {
        builder.append("if ");
        builder.append(stmt.getCondition().accept(this));
        builder.append("\n");
        stmt.getThenBranch().accept(this);
        if (stmt.getElseBranch() != null) {
            builder.append("\n");
            builder.append(getIndent());
            builder.append("otherwise\n");
            stmt.getElseBranch().accept(this);
        }
    }

    @Override
    public void visitWhileStmt(WhileStmt stmt) {
        builder.append("while ");
        builder.append(stmt.getCondition().accept(this));
        builder.append("\n");
        stmt.getBody().accept(this);
    }

    @Override
    public void visitForStmt(ForStmt stmt) {
        if (stmt.getInitializer() instanceof VarStmt && stmt.getCondition() instanceof BinaryExpr) {
            VarStmt init = (VarStmt) stmt.getInitializer();
            String varName = init.getName().getLexeme();
            BinaryExpr cond = (BinaryExpr) stmt.getCondition();
            Expr countExpr = cond.getRight();

            builder.append("repeat ")
                   .append(countExpr.accept(this))
                   .append(" times as ")
                   .append(varName)
                   .append("\n");
            stmt.getBody().accept(this);
        } else {
            // Fallback (should never be reached under standard TLang AST)
            builder.append("while ")
                   .append(stmt.getCondition().accept(this))
                   .append("\n");
            stmt.getBody().accept(this);
        }
    }

    @Override
    public void visitFunctionStmt(FunctionStmt stmt) {
        builder.append("define ")
               .append(stmt.getName().getLexeme())
               .append(formatParams(stmt.getParams(), stmt.getDefaults()))
               .append("\n");

        indentLevel++;
        List<Stmt> body = stmt.getBody();
        for (int i = 0; i < body.size(); i++) {
            Stmt s = body.get(i);
            if (s instanceof FunctionStmt) {
                ensureBlankLine();
            }
            builder.append(getIndent());
            s.accept(this);
            if (i < body.size() - 1) {
                builder.append("\n");
            }
        }
        indentLevel--;
    }

    @Override
    public void visitReturnStmt(ReturnStmt stmt) {
        builder.append("return");
        if (stmt.getValue() != null) {
            Expr val = stmt.getValue();
            if (val instanceof LiteralExpr && ((LiteralExpr) val).getValue() == null) {
                // Bare return
            } else {
                builder.append(" ").append(val.accept(this));
            }
        }
    }

    @Override
    public void visitBreakStmt(BreakStmt stmt) {
        builder.append("break");
    }

    @Override
    public void visitContinueStmt(ContinueStmt stmt) {
        builder.append("continue");
    }

    @Override
    public void visitImportStmt(ImportStmt stmt) {
        builder.append("import ").append(stmt.getName().getLexeme());
    }

    // ── Expr.Visitor<String> Implementation ─────────────────────

    @Override
    public String visitBinaryExpr(BinaryExpr expr) {
        return expr.getLeft().accept(this) + " " + expr.getOperator().getLexeme() + " " + expr.getRight().accept(this);
    }

    @Override
    public String visitLogicalExpr(LogicalExpr expr) {
        return expr.getLeft().accept(this) + " " + expr.getOperator().getLexeme() + " " + expr.getRight().accept(this);
    }

    @Override
    public String visitUnaryExpr(UnaryExpr expr) {
        String op = expr.getOperator().getLexeme();
        if (expr.getOperator().getType() == TokenType.NOT) {
            return "not " + expr.getOperand().accept(this);
        } else {
            return op + expr.getOperand().accept(this);
        }
    }

    @Override
    public String visitLiteralExpr(LiteralExpr expr) {
        Object val = expr.getValue();
        if (val == null) return "nil";
        if (val instanceof String) {
            return formatStringLiteral((String) val);
        }
        return val.toString();
    }

    @Override
    public String visitGroupingExpr(GroupingExpr expr) {
        return "(" + expr.getExpression().accept(this) + ")";
    }

    @Override
    public String visitVariableExpr(VariableExpr expr) {
        return expr.getName().getLexeme();
    }

    @Override
    public String visitAssignExpr(AssignExpr expr) {
        return "set " + expr.getName().getLexeme() + " to " + expr.getValue().accept(this);
    }

    @Override
    public String visitCallExpr(CallExpr expr) {
        StringBuilder sb = new StringBuilder();
        sb.append(expr.getCallee().accept(this)).append("(");
        List<Expr> args = expr.getArguments();
        for (int i = 0; i < args.size(); i++) {
            sb.append(args.get(i).accept(this));
            if (i < args.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public String visitLambdaExpr(LambdaExpr expr) {
        StringBuilder sb = new StringBuilder();
        sb.append("function").append(formatParams(expr.getParams(), expr.getDefaults())).append("\n");

        // Swap builder for statement aggregation
        StringBuilder oldBuilder = this.builder;
        this.builder = new StringBuilder();

        indentLevel++;
        List<Stmt> body = expr.getBody();
        for (int i = 0; i < body.size(); i++) {
            Stmt s = body.get(i);
            if (s instanceof FunctionStmt) {
                ensureBlankLine();
            }
            this.builder.append(getIndent());
            s.accept(this);
            if (i < body.size() - 1) {
                this.builder.append("\n");
            }
        }
        indentLevel--;

        String bodyText = this.builder.toString();
        this.builder = oldBuilder;

        sb.append(bodyText);
        return sb.toString();
    }

    @Override
    public String visitListExpr(ListExpr expr) {
        List<Expr> elements = expr.getElements();
        if (elements.isEmpty()) {
            return "[]";
        }

        // Try single-line
        StringBuilder singleLine = new StringBuilder();
        singleLine.append("[");
        boolean canBeSingleLine = true;
        for (int i = 0; i < elements.size(); i++) {
            String elemStr = elements.get(i).accept(this);
            if (elemStr.contains("\n")) {
                canBeSingleLine = false;
                break;
            }
            singleLine.append(elemStr);
            if (i < elements.size() - 1) {
                singleLine.append(", ");
            }
        }
        singleLine.append("]");

        if (canBeSingleLine && singleLine.length() <= 80) {
            return singleLine.toString();
        }

        // Fallback to multi-line
        StringBuilder multiLine = new StringBuilder();
        multiLine.append("[\n");
        indentLevel++;
        for (int i = 0; i < elements.size(); i++) {
            multiLine.append(getIndent()).append(elements.get(i).accept(this));
            if (i < elements.size() - 1) {
                multiLine.append(",\n");
            } else {
                multiLine.append("\n");
            }
        }
        indentLevel--;
        multiLine.append(getIndent()).append("]");
        return multiLine.toString();
    }

    @Override
    public String visitMapExpr(MapExpr expr) {
        List<String> keys = expr.getKeys();
        List<Expr> values = expr.getValues();
        if (keys.isEmpty()) {
            return "{}";
        }

        // Try single-line
        StringBuilder singleLine = new StringBuilder();
        singleLine.append("{");
        boolean canBeSingleLine = true;
        for (int i = 0; i < keys.size(); i++) {
            String valStr = values.get(i).accept(this);
            if (valStr.contains("\n")) {
                canBeSingleLine = false;
                break;
            }
            singleLine.append(formatMapKey(keys.get(i))).append(": ").append(valStr);
            if (i < keys.size() - 1) {
                singleLine.append(", ");
            }
        }
        singleLine.append("}");

        if (canBeSingleLine && singleLine.length() <= 80) {
            return singleLine.toString();
        }

        // Fallback to multi-line
        StringBuilder multiLine = new StringBuilder();
        multiLine.append("{\n");
        indentLevel++;
        for (int i = 0; i < keys.size(); i++) {
            multiLine.append(getIndent())
                     .append(formatMapKey(keys.get(i)))
                     .append(": ")
                     .append(values.get(i).accept(this));
            if (i < keys.size() - 1) {
                multiLine.append(",\n");
            } else {
                multiLine.append("\n");
            }
        }
        indentLevel--;
        multiLine.append(getIndent()).append("}");
        return multiLine.toString();
    }

    @Override
    public String visitIndexExpr(IndexExpr expr) {
        return expr.getCollection().accept(this) + "[" + expr.getIndex().accept(this) + "]";
    }

    @Override
    public String visitFieldAccessExpr(FieldAccessExpr expr) {
        return expr.getObject().accept(this) + "." + expr.getName().getLexeme();
    }

    @Override
    public String visitIndexSetExpr(IndexSetExpr expr) {
        return "set " + expr.getCollection().accept(this) + "[" + expr.getIndex().accept(this) + "] to " + expr.getValue().accept(this);
    }

    @Override
    public String visitFieldSetExpr(FieldSetExpr expr) {
        return "set " + expr.getObject().accept(this) + "." + expr.getName().getLexeme() + " to " + expr.getValue().accept(this);
    }

    // ── Helper Methods ──────────────────────────────────────────

    private String formatParams(List<Token> params, List<Expr> defaults) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(" taking ");
        for (int i = 0; i < params.size(); i++) {
            sb.append(params.get(i).getLexeme());
            Expr def = defaults.get(i);
            if (def != null) {
                sb.append(" be ").append(def.accept(this));
            }
            if (i < params.size() - 1) {
                sb.append(" and ");
            }
        }
        return sb.toString();
    }

    private String formatStringLiteral(String val) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < val.length(); i++) {
            char c = val.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);      break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private String formatMapKey(String key) {
        if (isValidIdentifier(key)) {
            return key;
        }
        return formatStringLiteral(key);
    }

    private boolean isValidIdentifier(String key) {
        if (key.isEmpty()) return false;
        if (isKeywordType(key)) return false;
        char first = key.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) return false;
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private boolean isKeywordType(String key) {
        switch (key) {
            case "let": case "be": case "set": case "to": case "import":
            case "show": case "if": case "otherwise": case "while": case "break":
            case "continue": case "repeat": case "times": case "as": case "define":
            case "taking": case "return": case "function": case "and": case "or":
            case "not": case "true": case "false": case "nil":
                return true;
            default:
                return false;
        }
    }

    private String getIndent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indentLevel; i++) {
            sb.append("    ");
        }
        return sb.toString();
    }

    private void ensureBlankLine() {
        if (builder.length() == 0) return;
        if (builder.charAt(builder.length() - 1) == '\n') {
            if (builder.length() < 2 || builder.charAt(builder.length() - 2) != '\n') {
                builder.append("\n");
            }
        }
    }
}
