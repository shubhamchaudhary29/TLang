package dev.tlang.resolver;

import dev.tlang.errors.SemanticError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.tlang.ast.*;
import dev.tlang.lexer.Token;

public final class Resolver implements Expr.Visitor<Void>, Stmt.Visitor {

    private final SymbolTable symbolTable = new SymbolTable();
    private final List<SemanticError> errors = new ArrayList<>();
    private final List<SymbolReference> symbolReferences = new ArrayList<>();
    private int functionDepth = 0;
    private int loopDepth = 0;
    private int lastLine = 1;

    public List<SymbolReference> getSymbolReferences() {
        return symbolReferences;
    }

    private void recordSymbolRef(Token token, Symbol symbol) {
        if (token == null || symbol == null) return;
        symbolReferences.add(new SymbolReference(
            token.getLine(),
            token.getColumn(),
            token.getLexeme().length(),
            symbol
        ));
    }

    public Resolver() {
        // Root global scope is already initialized inside SymbolTable
    }

    public List<SemanticError> resolve(List<Stmt> program) {
        symbolReferences.clear();
        // Pass 1: Declare all functions in current scope
        for (Stmt stmt : program) {
            if (stmt instanceof FunctionStmt) {
                FunctionStmt fn = (FunctionStmt) stmt;
                Symbol symbol = new Symbol(fn.getName().getLexeme(), SymbolKind.FUNCTION, fn.getName().getLine(), fn.getName().getColumn());
                boolean success = symbolTable.declare(symbol);
                if (!success) {
                    error(fn.getName(), "Function '" + fn.getName().getLexeme() + "' is already declared in this scope.");
                } else {
                    recordSymbolRef(fn.getName(), symbol);
                }
            }
        }
        // Pass 2: Resolve all statements
        resolveStatements(program);
        return errors;
    }

    private void resolveStatements(List<Stmt> statements) {
        boolean terminated = false;
        for (Stmt s : statements) {
            if (terminated) {
                System.err.println("[line " + getStmtLine(s) + "] Warning: Unreachable code detected.");
            }
            resolve(s);
            if (s instanceof ReturnStmt || s instanceof BreakStmt || s instanceof ContinueStmt) {
                terminated = true;
            }
        }
    }

    public List<SemanticError> getErrors() {
        return errors;
    }

    private void resolve(Stmt stmt) {
        if (stmt != null) {
            getStmtLine(stmt); // Update lastLine
            stmt.accept(this);
        }
    }

    private void resolve(Expr expr) {
        if (expr != null) {
            int line = getExprLine(expr);
            if (line != 1) {
                lastLine = line;
            }
            expr.accept(this);
        }
    }

    private void error(Token token, String message) {
        errors.add(new SemanticError(message, token.getLine(), token.getColumn()));
    }

    // ── Stmt Visitor Implementation ──────────────────────────────

    @Override
    public void visitVarStmt(VarStmt stmt) {
        if (stmt.getInitializer() != null) {
            resolve(stmt.getInitializer());
        }
        Symbol symbol = new Symbol(stmt.getName().getLexeme(), SymbolKind.VARIABLE, stmt.getName().getLine(), stmt.getName().getColumn());
        boolean success = symbolTable.declare(symbol);
        if (!success) {
            error(stmt.getName(), "Variable '" + stmt.getName().getLexeme() + "' is already declared in this scope.");
        } else {
            recordSymbolRef(stmt.getName(), symbol);
        }
    }

    @Override
    public void visitExpressionStmt(ExpressionStmt stmt) {
        resolve(stmt.getExpression());
    }

    @Override
    public void visitPrintStmt(PrintStmt stmt) {
        resolve(stmt.getExpression());
    }

    @Override
    public void visitBlockStmt(BlockStmt stmt) {
        symbolTable.beginScope();
        // Pass 1: Declare all functions in current block scope
        for (Stmt s : stmt.getStatements()) {
            if (s instanceof FunctionStmt) {
                FunctionStmt fn = (FunctionStmt) s;
                Symbol symbol = new Symbol(fn.getName().getLexeme(), SymbolKind.FUNCTION, fn.getName().getLine(), fn.getName().getColumn());
                boolean success = symbolTable.declare(symbol);
                if (!success) {
                    error(fn.getName(), "Function '" + fn.getName().getLexeme() + "' is already declared in this scope.");
                } else {
                    recordSymbolRef(fn.getName(), symbol);
                }
            }
        }
        // Pass 2: Resolve all statements
        resolveStatements(stmt.getStatements());
        symbolTable.endScope();
    }

    private int getStmtLine(Stmt s) {
        int line = getStmtLineRaw(s);
        if (line != 1) {
            lastLine = line;
            return line;
        }
        return lastLine;
    }

    private int getStmtLineRaw(Stmt s) {
        if (s == null) return 1;
        if (s instanceof VarStmt) return ((VarStmt) s).getName().getLine();
        if (s instanceof FunctionStmt) return ((FunctionStmt) s).getName().getLine();
        if (s instanceof ReturnStmt) return ((ReturnStmt) s).getKeyword().getLine();
        if (s instanceof BreakStmt) return ((BreakStmt) s).getKeyword().getLine();
        if (s instanceof ContinueStmt) return ((ContinueStmt) s).getKeyword().getLine();
        if (s instanceof ImportStmt) return ((ImportStmt) s).getName().getLine();
        if (s instanceof PrintStmt) {
            int line = getExprLine(((PrintStmt) s).getExpression());
            if (line != 1) return line;
        }
        if (s instanceof ExpressionStmt) {
            int line = getExprLine(((ExpressionStmt) s).getExpression());
            if (line != 1) return line;
        }
        if (s instanceof IfStmt) {
            int line = getExprLine(((IfStmt) s).getCondition());
            if (line != 1) return line;
        }
        if (s instanceof WhileStmt) {
            int line = getExprLine(((WhileStmt) s).getCondition());
            if (line != 1) return line;
        }
        if (s instanceof ForStmt) {
            int line = getStmtLineRaw(((ForStmt) s).getInitializer());
            if (line != 1) return line;
        }
        if (s instanceof BlockStmt) {
            List<Stmt> stmts = ((BlockStmt) s).getStatements();
            if (!stmts.isEmpty()) return getStmtLineRaw(stmts.get(0));
        }
        return 1;
    }

    private int getExprLine(Expr e) {
        if (e == null) return 1;
        if (e instanceof VariableExpr) return ((VariableExpr) e).getName().getLine();
        if (e instanceof AssignExpr) return ((AssignExpr) e).getName().getLine();
        if (e instanceof BinaryExpr) return ((BinaryExpr) e).getOperator().getLine();
        if (e instanceof UnaryExpr) return ((UnaryExpr) e).getOperator().getLine();
        if (e instanceof LogicalExpr) return ((LogicalExpr) e).getOperator().getLine();
        if (e instanceof CallExpr) return ((CallExpr) e).getParen().getLine();
        if (e instanceof IndexExpr) return getExprLine(((IndexExpr) e).getCollection());
        if (e instanceof IndexSetExpr) return getExprLine(((IndexSetExpr) e).getCollection());
        if (e instanceof FieldAccessExpr) return getExprLine(((FieldAccessExpr) e).getObject());
        if (e instanceof FieldSetExpr) return getExprLine(((FieldSetExpr) e).getObject());
        if (e instanceof GroupingExpr) return getExprLine(((GroupingExpr) e).getExpression());
        if (e instanceof ListExpr) {
            List<Expr> elements = ((ListExpr) e).getElements();
            if (!elements.isEmpty()) return getExprLine(elements.get(0));
        }
        if (e instanceof MapExpr) {
            List<Expr> values = ((MapExpr) e).getValues();
            if (!values.isEmpty()) return getExprLine(values.get(0));
        }
        return 1;
    }

    @Override
    public void visitIfStmt(IfStmt stmt) {
        resolve(stmt.getCondition());
        resolve(stmt.getThenBranch());
        if (stmt.getElseBranch() != null) {
            resolve(stmt.getElseBranch());
        }
    }

    @Override
    public void visitWhileStmt(WhileStmt stmt) {
        resolve(stmt.getCondition());
        loopDepth++;
        resolve(stmt.getBody());
        loopDepth--;
    }

    @Override
    public void visitForStmt(ForStmt stmt) {
        symbolTable.beginScope();
        resolve(stmt.getInitializer());
        resolve(stmt.getCondition());
        resolve(stmt.getUpdate());
        loopDepth++;
        resolve(stmt.getBody());
        loopDepth--;
        symbolTable.endScope();
    }

    @Override
    public void visitFunctionStmt(FunctionStmt stmt) {
        // Resolve default parameters first in outer scope
        for (Expr defaultValue : stmt.getDefaults()) {
            resolve(defaultValue);
        }

        // New scope for parameter bindings
        symbolTable.beginScope();
        Set<String> paramNames = new HashSet<>();
        for (Token param : stmt.getParams()) {
            if (paramNames.contains(param.getLexeme())) {
                error(param, "Duplicate parameter '" + param.getLexeme() + "' in function definition.");
            } else {
                paramNames.add(param.getLexeme());
                Symbol symbol = new Symbol(param.getLexeme(), SymbolKind.PARAMETER, param.getLine(), param.getColumn());
                symbolTable.declare(symbol);
                recordSymbolRef(param, symbol);
            }
        }

        // Pass 1: Declare nested functions in function scope
        for (Stmt s : stmt.getBody()) {
            if (s instanceof FunctionStmt) {
                FunctionStmt fn = (FunctionStmt) s;
                Symbol symbol = new Symbol(fn.getName().getLexeme(), SymbolKind.FUNCTION, fn.getName().getLine(), fn.getName().getColumn());
                boolean success = symbolTable.declare(symbol);
                if (!success) {
                    error(fn.getName(), "Function '" + fn.getName().getLexeme() + "' is already declared in this scope.");
                } else {
                    recordSymbolRef(fn.getName(), symbol);
                }
            }
        }

        functionDepth++;
        resolveStatements(stmt.getBody());
        functionDepth--;
        symbolTable.endScope();
    }

    @Override
    public void visitReturnStmt(ReturnStmt stmt) {
        if (functionDepth == 0) {
            error(stmt.getKeyword(), "Cannot return from top-level code.");
        }
        if (stmt.getValue() != null) {
            resolve(stmt.getValue());
        }
    }

    @Override
    public void visitBreakStmt(BreakStmt stmt) {
        if (loopDepth == 0) {
            error(stmt.getKeyword(), "Cannot use 'break' outside of a loop.");
        }
    }

    @Override
    public void visitContinueStmt(ContinueStmt stmt) {
        if (loopDepth == 0) {
            error(stmt.getKeyword(), "Cannot use 'continue' outside of a loop.");
        }
    }

    @Override
    public void visitImportStmt(ImportStmt stmt) {
        Symbol symbol = new Symbol(stmt.getName().getLexeme(), SymbolKind.VARIABLE, stmt.getName().getLine(), stmt.getName().getColumn());
        boolean success = symbolTable.declare(symbol);
        if (!success) {
            error(stmt.getName(), "Variable '" + stmt.getName().getLexeme() + "' is already declared in this scope.");
        } else {
            recordSymbolRef(stmt.getName(), symbol);
        }
    }

    // ── Expr Visitor Implementation ──────────────────────────────

    @Override
    public Void visitBinaryExpr(BinaryExpr expr) {
        resolve(expr.getLeft());
        resolve(expr.getRight());
        return null;
    }

    @Override
    public Void visitLogicalExpr(LogicalExpr expr) {
        resolve(expr.getLeft());
        resolve(expr.getRight());
        return null;
    }

    @Override
    public Void visitUnaryExpr(UnaryExpr expr) {
        resolve(expr.getOperand());
        return null;
    }

    @Override
    public Void visitLiteralExpr(LiteralExpr expr) {
        return null;
    }

    @Override
    public Void visitGroupingExpr(GroupingExpr expr) {
        resolve(expr.getExpression());
        return null;
    }

    @Override
    public Void visitVariableExpr(VariableExpr expr) {
        Symbol resolved = symbolTable.resolve(expr.getName().getLexeme());
        if (resolved == null) {
            error(expr.getName(), "Undefined variable '" + expr.getName().getLexeme() + "'.");
        } else {
            recordSymbolRef(expr.getName(), resolved);
        }
        return null;
    }

    @Override
    public Void visitAssignExpr(AssignExpr expr) {
        Symbol resolved = symbolTable.resolve(expr.getName().getLexeme());
        if (resolved == null) {
            error(expr.getName(), "Undefined variable '" + expr.getName().getLexeme() + "'.");
        } else {
            recordSymbolRef(expr.getName(), resolved);
        }
        resolve(expr.getValue());
        return null;
    }

    @Override
    public Void visitCallExpr(CallExpr expr) {
        resolve(expr.getCallee());
        for (Expr arg : expr.getArguments()) {
            resolve(arg);
        }
        return null;
    }

    @Override
    public Void visitLambdaExpr(LambdaExpr expr) {
        // Resolve default parameter values in enclosing scope
        for (Expr defaultValue : expr.getDefaults()) {
            resolve(defaultValue);
        }

        symbolTable.beginScope();
        Set<String> paramNames = new HashSet<>();
        for (Token param : expr.getParams()) {
            if (paramNames.contains(param.getLexeme())) {
                error(param, "Duplicate parameter '" + param.getLexeme() + "' in lambda definition.");
            } else {
                paramNames.add(param.getLexeme());
                Symbol symbol = new Symbol(param.getLexeme(), SymbolKind.PARAMETER, param.getLine(), param.getColumn());
                symbolTable.declare(symbol);
                recordSymbolRef(param, symbol);
            }
        }

        // Pass 1: Declare nested functions in lambda scope
        for (Stmt s : expr.getBody()) {
            if (s instanceof FunctionStmt) {
                FunctionStmt fn = (FunctionStmt) s;
                Symbol symbol = new Symbol(fn.getName().getLexeme(), SymbolKind.FUNCTION, fn.getName().getLine(), fn.getName().getColumn());
                boolean success = symbolTable.declare(symbol);
                if (!success) {
                    error(fn.getName(), "Function '" + fn.getName().getLexeme() + "' is already declared in this scope.");
                } else {
                    recordSymbolRef(fn.getName(), symbol);
                }
            }
        }

        functionDepth++;
        resolveStatements(expr.getBody());
        functionDepth--;
        symbolTable.endScope();
        return null;
    }

    @Override
    public Void visitListExpr(ListExpr expr) {
        for (Expr item : expr.getElements()) {
            resolve(item);
        }
        return null;
    }

    @Override
    public Void visitMapExpr(MapExpr expr) {
        for (Expr val : expr.getValues()) {
            resolve(val);
        }
        return null;
    }

    @Override
    public Void visitIndexExpr(IndexExpr expr) {
        resolve(expr.getCollection());
        resolve(expr.getIndex());
        return null;
    }

    @Override
    public Void visitFieldAccessExpr(FieldAccessExpr expr) {
        resolve(expr.getObject());
        return null;
    }

    @Override
    public Void visitIndexSetExpr(IndexSetExpr expr) {
        resolve(expr.getCollection());
        resolve(expr.getIndex());
        resolve(expr.getValue());
        return null;
    }

    @Override
    public Void visitFieldSetExpr(FieldSetExpr expr) {
        resolve(expr.getObject());
        resolve(expr.getValue());
        return null;
    }
}
