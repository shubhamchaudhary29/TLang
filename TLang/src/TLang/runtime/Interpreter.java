package TLang.runtime;

import java.util.ArrayList;
import java.util.List;

import TLang.ast.*;
import TLang.lexer.Token;
import TLang.lexer.TokenType;

/**
 * Tree-walking interpreter for the Antigravity v2 language.
 *
 * Implements both the expression visitor (returns Object) and
 * the statement visitor (returns void).  Supports integers,
 * booleans, strings, user-defined functions, and closures.
 */
public final class Interpreter implements Expr.Visitor<Object>, Stmt.Visitor {

    private Environment environment;

    public Interpreter() {
        this.environment = new Environment();
    }

    // ── Public API ──────────────────────────────────────────────

    /** Interpret (execute) a complete program. */
    public void interpret(List<Stmt> statements) {
        for (Stmt stmt : statements) {
            execute(stmt);
        }
    }

    // ── Statement execution ─────────────────────────────────────

    private void execute(Stmt stmt) {
        stmt.accept(this);
    }

    @Override
    public void visitVarStmt(VarStmt stmt) {
        Object value = evaluate(stmt.getInitializer());
        environment.define(stmt.getName().getLexeme(), value);
    }

    @Override
    public void visitExpressionStmt(ExpressionStmt stmt) {
        evaluate(stmt.getExpression());
    }

    @Override
    public void visitPrintStmt(PrintStmt stmt) {
        Object value = evaluate(stmt.getExpression());
        System.out.println(stringify(value));
    }

    @Override
    public void visitBlockStmt(BlockStmt stmt) {
        executeBlock(stmt.getStatements(), new Environment(environment));
    }

    @Override
    public void visitIfStmt(IfStmt stmt) {
        Object condition = evaluate(stmt.getCondition());
        checkBoolean(condition, "if condition");

        if (isTruthy(condition)) {
            execute(stmt.getThenBranch());
        } else if (stmt.getElseBranch() != null) {
            execute(stmt.getElseBranch());
        }
    }

    @Override
    public void visitWhileStmt(WhileStmt stmt) {
        while (true) {
            Object condition = evaluate(stmt.getCondition());
            checkBoolean(condition, "while condition");
            if (!isTruthy(condition)) break;
            execute(stmt.getBody());
        }
    }

    @Override
    public void visitForStmt(ForStmt stmt) {
        // Kept for backward compatibility; repeat loops desugar
        // into WhileStmt via the parser so this is rarely called.
        Environment previous = this.environment;
        try {
            this.environment = new Environment(previous);
            execute(stmt.getInitializer());
            while (true) {
                Object condition = evaluate(stmt.getCondition());
                checkBoolean(condition, "for-loop condition");
                if (!isTruthy(condition)) break;
                execute(stmt.getBody());
                evaluate(stmt.getUpdate());
            }
        } finally {
            this.environment = previous;
        }
    }

    @Override
    public void visitFunctionStmt(FunctionStmt stmt) {
        TinyFunction function = new TinyFunction(stmt, environment);
        environment.define(stmt.getName().getLexeme(), function);
    }

    @Override
    public void visitReturnStmt(ReturnStmt stmt) {
        Object value = evaluate(stmt.getValue());
        throw new ReturnException(value);
    }

    /** Execute a list of statements in the given environment. */
    public void executeBlock(List<Stmt> statements, Environment env) {
        Environment previous = this.environment;
        try {
            this.environment = env;
            for (Stmt stmt : statements) {
                execute(stmt);
            }
        } finally {
            this.environment = previous;
        }
    }

    // ── Expression evaluation ───────────────────────────────────

    private Object evaluate(Expr expr) {
        return expr.accept(this);
    }

    @Override
    public Object visitLiteralExpr(LiteralExpr expr) {
        return expr.getValue();
    }

    @Override
    public Object visitGroupingExpr(GroupingExpr expr) {
        return evaluate(expr.getExpression());
    }

    @Override
    public Object visitVariableExpr(VariableExpr expr) {
        return environment.get(expr.getName());
    }

    @Override
    public Object visitAssignExpr(AssignExpr expr) {
        Object value = evaluate(expr.getValue());
        environment.assign(expr.getName(), value);
        return value;
    }

    @Override
    public Object visitUnaryExpr(UnaryExpr expr) {
        Object operand = evaluate(expr.getOperand());
        Token op = expr.getOperator();

        switch (op.getType()) {
            case MINUS:
                checkInteger(operand, op);
                return -((int) operand);
            case NOT:
                checkBoolean(operand, op);
                return !((boolean) operand);
            default:
                throw new RuntimeError(op,
                        "Unknown unary operator '" + op.getLexeme() + "'.");
        }
    }

    @Override
    public Object visitBinaryExpr(BinaryExpr expr) {
        Object left  = evaluate(expr.getLeft());
        Object right = evaluate(expr.getRight());
        Token op = expr.getOperator();

        switch (op.getType()) {
            // ── Arithmetic ──
            case PLUS:
                // String concatenation support
                if (left instanceof String || right instanceof String) {
                    return stringify(left) + stringify(right);
                }
                checkIntegers(left, right, op);
                return (int) left + (int) right;
            case MINUS:
                checkIntegers(left, right, op);
                return (int) left - (int) right;
            case STAR:
                checkIntegers(left, right, op);
                return (int) left * (int) right;
            case SLASH:
                checkIntegers(left, right, op);
                if ((int) right == 0) {
                    throw new RuntimeError(op, "Division by zero.");
                }
                return (int) left / (int) right;
            case PERCENT:
                checkIntegers(left, right, op);
                if ((int) right == 0) {
                    throw new RuntimeError(op, "Modulo by zero.");
                }
                return (int) left % (int) right;

            // ── Comparison (integers) ──
            case GREATER:
                checkIntegers(left, right, op);
                return (int) left > (int) right;
            case GREATER_EQUAL:
                checkIntegers(left, right, op);
                return (int) left >= (int) right;
            case LESS:
                checkIntegers(left, right, op);
                return (int) left < (int) right;
            case LESS_EQUAL:
                checkIntegers(left, right, op);
                return (int) left <= (int) right;

            // ── Equality (any types) ──
            case EQUAL_EQUAL:
                return isEqual(left, right);
            case BANG_EQUAL:
                return !isEqual(left, right);

            default:
                throw new RuntimeError(op,
                        "Unknown binary operator '" + op.getLexeme() + "'.");
        }
    }

    @Override
    public Object visitLogicalExpr(LogicalExpr expr) {
        Object left = evaluate(expr.getLeft());
        Token op = expr.getOperator();

        checkBoolean(left, op);

        // Short-circuit evaluation
        if (op.getType() == TokenType.OR) {
            if ((boolean) left) return true;
        } else {  // AND
            if (!(boolean) left) return false;
        }

        Object right = evaluate(expr.getRight());
        checkBoolean(right, op);
        return right;
    }

    @Override
    public Object visitCallExpr(CallExpr expr) {
        Object callee = environment.get(expr.getName());

        if (!(callee instanceof TinyFunction)) {
            throw new RuntimeError(expr.getName(),
                    "'" + expr.getName().getLexeme() + "' is not a function.");
        }

        TinyFunction function = (TinyFunction) callee;

        // Evaluate arguments
        List<Object> arguments = new ArrayList<>();
        for (Expr arg : expr.getArguments()) {
            arguments.add(evaluate(arg));
        }

        // Check arity
        if (arguments.size() != function.arity()) {
            throw new RuntimeError(expr.getName(),
                    "Function '" + function.getName() + "' expects "
                    + function.arity() + " argument(s) but got "
                    + arguments.size() + ".");
        }

        return function.call(this, arguments);
    }

    // ── Helpers ─────────────────────────────────────────────────

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean) return (boolean) value;
        return false;
    }

    private boolean isEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null) return false;
        return a.equals(b);
    }

    private String stringify(Object value) {
        if (value == null) return "nil";
        if (value instanceof Integer) return Integer.toString((int) value);
        if (value instanceof Boolean) return Boolean.toString((boolean) value);
        if (value instanceof String)  return (String) value;
        return value.toString();
    }

    // ── Type checking helpers ───────────────────────────────────

    private void checkInteger(Object value, Token operator) {
        if (value instanceof Integer) return;
        throw new RuntimeError(operator,
                "Operand must be an integer (got " + typeName(value) + ").");
    }

    private void checkIntegers(Object left, Object right, Token operator) {
        if (left instanceof Integer && right instanceof Integer) return;
        throw new RuntimeError(operator,
                "Operands must be integers (got " + typeName(left)
                + " and " + typeName(right) + ").");
    }

    private void checkBoolean(Object value, Token operator) {
        if (value instanceof Boolean) return;
        throw new RuntimeError(operator,
                "Operand must be a boolean (got " + typeName(value) + ").");
    }

    private void checkBoolean(Object value, String context) {
        if (value instanceof Boolean) return;
        throw new RuntimeError(null,
                context + " must be a boolean (got " + typeName(value) + ").");
    }

    private String typeName(Object value) {
        if (value instanceof Integer)      return "integer";
        if (value instanceof Boolean)      return "boolean";
        if (value instanceof String)       return "string";
        if (value instanceof TinyFunction) return "function";
        if (value == null)                 return "nil";
        return value.getClass().getSimpleName();
    }
}
