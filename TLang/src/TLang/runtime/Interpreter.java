package TLang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import TLang.ast.*;
import TLang.lexer.Token;
import TLang.lexer.TokenType;
import TLang.types.Type;

/**
 * Tree-walking interpreter for the TLang language.
 *
 * Implements both the expression visitor (returns Object) and
 * the statement visitor (returns void).  Supports integers,
 * booleans, strings, user-defined functions, closures, lists, and maps.
 */
public final class Interpreter implements Expr.Visitor<Object>, Stmt.Visitor {

    private final ModuleLoader moduleLoader;
    private Environment environment;
    private int callDepth = 0;

    public Interpreter() {
        this.moduleLoader = null;
        this.environment = new Environment();
        defineGlobals();
    }

    public Interpreter(ModuleLoader moduleLoader) {
        this.moduleLoader = moduleLoader;
        this.environment = new Environment();
        defineGlobals();
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
    public void visitImportStmt(ImportStmt stmt) {
        if (moduleLoader == null) {
            throw new RuntimeError(stmt.getName(), "Module loader not initialized.");
        }
        Map<String, Object> exports = moduleLoader.load(stmt.getName().getLexeme(), stmt.getName());
        environment.define(stmt.getName().getLexeme(), exports);
    }

    public Environment getGlobalEnvironment() {
        return environment;
    }

    private void defineGlobals() {
        environment.define("read_file", new NativeFunction("read_file", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'read_file' must be a string.");
                }
                return StdlibOps.readFile((String) path, token);
            }
        });

        environment.define("write_file", new NativeFunction("write_file", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                Object content = args.get(1);
                if (Type.of(path) != Type.STRING || Type.of(content) != Type.STRING) {
                    throw new RuntimeError(token, "Arguments to 'write_file' must be strings.");
                }
                StdlibOps.writeFile((String) path, (String) content, token);
                return null;
            }
        });

        environment.define("file_exists", new NativeFunction("file_exists", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'file_exists' must be a string.");
                }
                return StdlibOps.fileExists((String) path);
            }
        });

        environment.define("delete_file", new NativeFunction("delete_file", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'delete_file' must be a string.");
                }
                return StdlibOps.deleteFile((String) path);
            }
        });

        environment.define("now", new NativeFunction("now", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                return StdlibOps.now();
            }
        });

        environment.define("random", new NativeFunction("random", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object min = args.get(0);
                Object max = args.get(1);
                if (Type.of(min) != Type.NUMBER || Type.of(max) != Type.NUMBER) {
                    throw new RuntimeError(token, "Arguments to 'random' must be integers.");
                }
                return StdlibOps.randomBetween((Integer) min, (Integer) max, token);
            }
        });

        environment.define("to_string", new NativeFunction("to_string", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                return stringify(args.get(0));
            }
        });

        environment.define("to_integer", new NativeFunction("to_integer", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                return StdlibOps.toInteger(args.get(0), token);
            }
        });

        environment.define("type_of", new NativeFunction("type_of", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                return StdlibOps.typeOf(args.get(0));
            }
        });
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
        Environment blockEnv = new Environment(environment);
        executeBlock(stmt.getStatements(), blockEnv);
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
            try {
                execute(stmt.getBody());
            } catch (BreakException e) {
                break;
            } catch (ContinueException e) {
                // Skip directly to the next condition check
            }
        }
    }

    @Override
    public void visitForStmt(ForStmt stmt) {
        Environment previous = this.environment;
        try {
            this.environment = new Environment(previous);
            execute(stmt.getInitializer());
            while (true) {
                Object condition = evaluate(stmt.getCondition());
                checkBoolean(condition, "for-loop condition");
                if (!isTruthy(condition)) break;
                try {
                    execute(stmt.getBody());
                } catch (ContinueException e) {
                    // Skip body, fall through to update/increment
                }
                evaluate(stmt.getUpdate());
            }
        } catch (BreakException e) {
            // Exit loop
        } finally {
            this.environment = previous;
        }
    }

    @Override
    public void visitFunctionStmt(FunctionStmt stmt) {
        TinyFunction function = new TinyFunction(
            stmt.getName().getLexeme(),
            stmt.getParams(),
            stmt.getDefaults(),
            stmt.getBody(),
            environment
        );
        environment.define(stmt.getName().getLexeme(), function);
    }

    public Object evaluateInEnvironment(Expr expr, Environment env) {
        Environment previous = this.environment;
        try {
            this.environment = env;
            return evaluate(expr);
        } finally {
            this.environment = previous;
        }
    }

    @Override
    public void visitReturnStmt(ReturnStmt stmt) {
        Object value = evaluate(stmt.getValue());
        throw new ReturnException(value);
    }

    @Override
    public void visitBreakStmt(BreakStmt stmt) {
        throw new BreakException();
    }

    @Override
    public void visitContinueStmt(ContinueStmt stmt) {
        throw new ContinueException();
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
                if (Type.of(left) == Type.STRING || Type.of(right) == Type.STRING) {
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
    @SuppressWarnings("unchecked")
    public Object visitCallExpr(CallExpr expr) {
        // ── Built-in method dispatch for lists and maps ──
        // When the callee is a FieldAccessExpr (e.g. list.add, map.get),
        // check if the target object is a List or Map and dispatch to
        // built-in method helpers. This avoids evaluating the field access
        // as a map key lookup (which would fail for lists and for method names).
        // TODO: Fold into a proper native-function system in Stage 4.
        if (expr.getCallee() instanceof FieldAccessExpr) {
            FieldAccessExpr fa = (FieldAccessExpr) expr.getCallee();
            Object target = evaluate(fa.getObject());
            Type targetType = Type.of(target);
            if (targetType == Type.LIST || targetType == Type.MAP || targetType == Type.STRING) {
                String methodName = fa.getName().getLexeme();
                List<Object> arguments = new ArrayList<>();
                for (Expr arg : expr.getArguments()) {
                    arguments.add(evaluate(arg));
                }
                switch (targetType) {
                    case LIST:
                        return callListMethod((List<Object>) target, methodName, arguments, fa.getName());
                    case MAP:
                        return callMapMethod((Map<String, Object>) target, methodName, arguments, fa.getName());
                    case STRING:
                        return callStringMethod((String) target, methodName, arguments, fa.getName());
                    default:
                        break;
                }
            }
        }

        Object callee = evaluate(expr.getCallee());

        // Evaluate arguments
        List<Object> arguments = new ArrayList<>();
        for (Expr arg : expr.getArguments()) {
            arguments.add(evaluate(arg));
        }

        return executeCall(callee, arguments, expr.getParen());
    }

    private Object executeCall(Object callee, List<Object> arguments, Token paren) {
        if (callee instanceof TinyFunction) {
            TinyFunction function = (TinyFunction) callee;
            int required = function.getRequiredCount();
            int total = function.getTotalCount();
            int got = arguments.size();
            if (got < required || got > total) {
                String nameStr = function.getName().equals("<anonymous>") ? "Anonymous function" : "Function '" + function.getName() + "'";
                String expectStr = (required == total) ? String.valueOf(required) : required + " to " + total;
                throw new RuntimeError(paren,
                        nameStr + " expects " + expectStr + " argument(s) but got " + got + ".");
            }

            callDepth++;
            if (callDepth > 1000) {
                callDepth--;
                throw new RuntimeError(paren, "Maximum recursion depth exceeded (limit: 1000).");
            }

            try {
                return function.call(this, arguments);
            } finally {
                callDepth--;
            }
        } else if (callee instanceof NativeFunction) {
            NativeFunction function = (NativeFunction) callee;
            int got = arguments.size();
            if (got != function.getArity()) {
                throw new RuntimeError(paren,
                        "Function '" + function.getName() + "' expects " + function.getArity() + " argument(s) but got " + got + ".");
            }
            return function.call(arguments, paren);
        } else {
            throw new RuntimeError(paren, "Cannot call a value of type '" + Type.of(callee).displayName() + "'.");
        }
    }

    @Override
    public Object visitLambdaExpr(LambdaExpr expr) {
        return new TinyFunction("<anonymous>", expr.getParams(), expr.getDefaults(), expr.getBody(), environment);
    }

    // ── List and Map expressions ────────────────────────────────

    @Override
    public Object visitListExpr(ListExpr expr) {
        List<Object> list = new ArrayList<>();
        for (Expr element : expr.getElements()) {
            list.add(evaluate(element));
        }
        return list;
    }

    @Override
    public Object visitMapExpr(MapExpr expr) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < expr.getKeys().size(); i++) {
            String key = expr.getKeys().get(i);
            Object value = evaluate(expr.getValues().get(i));
            // Duplicate keys: last one wins
            map.put(key, value);
        }
        return map;
    }

    @Override
    public Object visitIndexExpr(IndexExpr expr) {
        Object collection = evaluate(expr.getCollection());
        Object index = evaluate(expr.getIndex());

        Type collType = Type.of(collection);
        if (collType == Type.LIST) {
            if (Type.of(index) != Type.NUMBER) {
                throw new RuntimeError(expr.getBracket(), "List index must be an integer.");
            }
            List<?> list = (List<?>) collection;
            int idx = (Integer) index;
            if (idx < 0 || idx >= list.size()) {
                throw new RuntimeError(expr.getBracket(),
                    "Index " + idx + " out of bounds for list of length " + list.size() + ".");
            }
            return list.get(idx);
        }

        if (collType == Type.MAP) {
            if (Type.of(index) != Type.STRING) {
                throw new RuntimeError(expr.getBracket(), "Map index must be a string.");
            }
            Map<?, ?> map = (Map<?, ?>) collection;
            String key = (String) index;
            if (!map.containsKey(key)) {
                throw new RuntimeError(expr.getBracket(), "Key '" + key + "' not found in map.");
            }
            return map.get(key);
        }

        throw new RuntimeError(expr.getBracket(), "Cannot index type '" + typeName(collection) + "'.");
    }

    @Override
    public Object visitFieldAccessExpr(FieldAccessExpr expr) {
        Object object = evaluate(expr.getObject());

        if (Type.of(object) == Type.MAP) {
            Map<?, ?> map = (Map<?, ?>) object;
            String key = expr.getName().getLexeme();
            if (!map.containsKey(key)) {
                throw new RuntimeError(expr.getName(), "Key '" + key + "' not found in map.");
            }
            return map.get(key);
        }

        throw new RuntimeError(expr.getName(),
                "Cannot access field '" + expr.getName().getLexeme() + "' on " + typeName(object) + ".");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object visitIndexSetExpr(IndexSetExpr expr) {
        Object collection = evaluate(expr.getCollection());
        Object index = evaluate(expr.getIndex());
        Object value = evaluate(expr.getValue());

        Type collType = Type.of(collection);
        if (collType == Type.LIST) {
            if (Type.of(index) != Type.NUMBER) {
                throw new RuntimeError(expr.getBracket(), "List index must be an integer.");
            }
            List<Object> list = (List<Object>) collection;
            int idx = (Integer) index;
            if (idx < 0 || idx >= list.size()) {
                throw new RuntimeError(expr.getBracket(),
                    "Index " + idx + " out of bounds for list of length " + list.size() + ".");
            }
            list.set(idx, value);
            return value;
        }

        if (collType == Type.MAP) {
            if (Type.of(index) != Type.STRING) {
                throw new RuntimeError(expr.getBracket(), "Map index must be a string.");
            }
            Map<String, Object> map = (Map<String, Object>) collection;
            map.put((String) index, value);
            return value;
        }

        throw new RuntimeError(expr.getBracket(), "Cannot index assign type '" + typeName(collection) + "'.");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object visitFieldSetExpr(FieldSetExpr expr) {
        Object object = evaluate(expr.getObject());
        Object value = evaluate(expr.getValue());

        if (Type.of(object) == Type.MAP) {
            Map<String, Object> map = (Map<String, Object>) object;
            map.put(expr.getName().getLexeme(), value);
            return value;
        }

        throw new RuntimeError(expr.getName(),
                "Cannot set field '" + expr.getName().getLexeme() + "' on " + typeName(object) + ".");
    }

    // ── Built-in list/map method dispatch ───────────────────────
    // Narrowly-scoped dispatch for built-in collection methods.
    // TODO: Fold into a proper native-function system in Stage 4.

    private Object callListMethod(List<Object> list, String method, List<Object> args, Token token) {
        switch (method) {
            case "add":
                checkMethodArity(method, args, 1, token);
                list.add(args.get(0));
                return null;
            case "get":
                checkMethodArity(method, args, 1, token);
                checkMethodArgInteger(args.get(0), method, token);
                int getIdx = (Integer) args.get(0);
                if (getIdx < 0 || getIdx >= list.size()) {
                    throw new RuntimeError(token,
                        "Index " + getIdx + " out of bounds for list of length " + list.size() + ".");
                }
                return list.get(getIdx);
            case "set":
                checkMethodArity(method, args, 2, token);
                checkMethodArgInteger(args.get(0), method, token);
                int setIdx = (Integer) args.get(0);
                if (setIdx < 0 || setIdx >= list.size()) {
                    throw new RuntimeError(token,
                        "Index " + setIdx + " out of bounds for list of length " + list.size() + ".");
                }
                list.set(setIdx, args.get(1));
                return args.get(1);
            case "remove":
                checkMethodArity(method, args, 1, token);
                checkMethodArgInteger(args.get(0), method, token);
                int rmIdx = (Integer) args.get(0);
                if (rmIdx < 0 || rmIdx >= list.size()) {
                    throw new RuntimeError(token,
                        "Index " + rmIdx + " out of bounds for list of length " + list.size() + ".");
                }
                return list.remove(rmIdx);
            case "length":
                checkMethodArity(method, args, 0, token);
                return list.size();
            case "contains":
                checkMethodArity(method, args, 1, token);
                for (Object item : list) {
                    if (isEqual(item, args.get(0))) return true;
                }
                return false;
            default:
                throw new RuntimeError(token, "List has no method '" + method + "'.");
        }
    }

    private Object callMapMethod(Map<String, Object> map, String method, List<Object> args, Token token) {
        switch (method) {
            case "put":
                checkMethodArity(method, args, 2, token);
                checkMethodArgString(args.get(0), method, token);
                map.put((String) args.get(0), args.get(1));
                return args.get(1);
            case "get":
                checkMethodArity(method, args, 1, token);
                checkMethodArgString(args.get(0), method, token);
                String getKey = (String) args.get(0);
                if (!map.containsKey(getKey)) {
                    throw new RuntimeError(token, "Key '" + getKey + "' not found in map.");
                }
                return map.get(getKey);
            case "remove":
                checkMethodArity(method, args, 1, token);
                checkMethodArgString(args.get(0), method, token);
                return map.remove((String) args.get(0));  // returns null if key absent
            case "keys":
                checkMethodArity(method, args, 0, token);
                return new ArrayList<>(map.keySet());
            case "values":
                checkMethodArity(method, args, 0, token);
                return new ArrayList<>(map.values());
            case "contains":
                checkMethodArity(method, args, 1, token);
                checkMethodArgString(args.get(0), method, token);
                return map.containsKey((String) args.get(0));
            case "length":
                checkMethodArity(method, args, 0, token);
                return map.size();
            default:
                if (map.containsKey(method)) {
                    Object val = map.get(method);
                    return executeCall(val, args, token);
                }
                throw new RuntimeError(token, "Map has no method '" + method + "'.");
        }
    }

    private Object callStringMethod(String str, String method, List<Object> args, Token token) {
        switch (method) {
            case "length":
                checkMethodArity(method, args, 0, token);
                return str.length();
            case "toUpperCase":
                checkMethodArity(method, args, 0, token);
                return str.toUpperCase();
            case "toLowerCase":
                checkMethodArity(method, args, 0, token);
                return str.toLowerCase();
            case "trim":
                checkMethodArity(method, args, 0, token);
                return str.trim();
            case "contains":
                checkMethodArity(method, args, 1, token);
                checkMethodArgString(args.get(0), method, token);
                return str.contains((String) args.get(0));
            case "indexOf":
                checkMethodArity(method, args, 1, token);
                checkMethodArgString(args.get(0), method, token);
                return str.indexOf((String) args.get(0));
            case "substring": {
                checkMethodArity(method, args, 2, token);
                checkMethodArgInteger(args.get(0), method, token);
                checkMethodArgInteger(args.get(1), method, token);
                int start = (Integer) args.get(0);
                int end = (Integer) args.get(1);
                if (start < 0 || end > str.length() || start > end) {
                    throw new RuntimeError(token,
                        "Invalid substring range " + start + " to " + end + " for string of length " + str.length() + ".");
                }
                return str.substring(start, end);
            }
            case "charAt": {
                checkMethodArity(method, args, 1, token);
                checkMethodArgInteger(args.get(0), method, token);
                int index = (Integer) args.get(0);
                if (index < 0 || index >= str.length()) {
                    throw new RuntimeError(token,
                        "Index " + index + " out of bounds for string of length " + str.length() + ".");
                }
                return String.valueOf(str.charAt(index));
            }
            case "split": {
                checkMethodArity(method, args, 1, token);
                checkMethodArgString(args.get(0), method, token);
                String sep = (String) args.get(0);
                String[] parts;
                if (sep.isEmpty()) {
                    parts = new String[str.length()];
                    for (int idx = 0; idx < str.length(); idx++) {
                        parts[idx] = String.valueOf(str.charAt(idx));
                    }
                } else {
                    parts = str.split(java.util.regex.Pattern.quote(sep), -1);
                }
                List<Object> list = new ArrayList<>();
                for (String part : parts) {
                    list.add(part);
                }
                return list;
            }
            case "replace": {
                checkMethodArity(method, args, 2, token);
                checkMethodArgString(args.get(0), method, token);
                checkMethodArgString(args.get(1), method, token);
                return str.replace((String) args.get(0), (String) args.get(1));
            }
            default:
                throw new RuntimeError(token, "String has no method '" + method + "'.");
        }
    }

    private void checkMethodArity(String method, List<Object> args, int expected, Token token) {
        if (args.size() != expected) {
            throw new RuntimeError(token,
                    "Method '" + method + "' expects " + expected + " argument(s) but got " + args.size() + ".");
        }
    }
    private void checkMethodArgInteger(Object arg, String method, Token token) {
        if (Type.of(arg) != Type.NUMBER) {
            throw new RuntimeError(token,
                    "Method '" + method + "' expects an integer argument (got " + typeName(arg) + ").");
        }
    }

    private void checkMethodArgString(Object arg, String method, Token token) {
        if (Type.of(arg) != Type.STRING) {
            throw new RuntimeError(token,
                    "Method '" + method + "' expects a string argument (got " + typeName(arg) + ").");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private boolean isTruthy(Object value) {
        if (Type.of(value) == Type.BOOLEAN) return (boolean) value;
        return false;
    }

    private boolean isEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null) return false;
        return a.equals(b);
    }

    public static String stringify(Object value) {
        switch (Type.of(value)) {
            case NULL:
                return "nil";
            case NUMBER:
                return Integer.toString((int) value);
            case BOOLEAN:
                return Boolean.toString((boolean) value);
            case STRING:
                return (String) value;
            case LIST: {
                List<?> list = (List<?>) value;
                StringBuilder sb = new StringBuilder();
                sb.append("[");
                for (int i = 0; i < list.size(); i++) {
                    sb.append(stringify(list.get(i)));
                    if (i < list.size() - 1) sb.append(", ");
                }
                sb.append("]");
                return sb.toString();
            }
            case MAP: {
                Map<?, ?> map = (Map<?, ?>) value;
                StringBuilder sb = new StringBuilder();
                sb.append("{");
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    sb.append(entry.getKey()).append(": ").append(stringify(entry.getValue()));
                    if (i < map.size() - 1) sb.append(", ");
                    i++;
                }
                sb.append("}");
                return sb.toString();
            }
            case FUNCTION:
            default:
                return value.toString();
        }
    }

    // ── Type checking helpers ───────────────────────────────────

    private void checkInteger(Object value, Token operator) {
        if (Type.of(value) == Type.NUMBER) return;
        throw new RuntimeError(operator,
                "Operand must be an integer (got " + typeName(value) + ").");
    }

    private void checkIntegers(Object left, Object right, Token operator) {
        if (Type.of(left) == Type.NUMBER && Type.of(right) == Type.NUMBER) return;
        throw new RuntimeError(operator,
                "Operands must be integers (got " + typeName(left)
                + " and " + typeName(right) + ").");
    }

    private void checkBoolean(Object value, Token operator) {
        if (Type.of(value) == Type.BOOLEAN) return;
        throw new RuntimeError(operator,
                "Operand must be a boolean (got " + typeName(value) + ").");
    }

    private void checkBoolean(Object value, String context) {
        if (Type.of(value) == Type.BOOLEAN) return;
        throw new RuntimeError(null,
                context + " must be a boolean (got " + typeName(value) + ").");
    }

    private String typeName(Object value) {
        try {
            return Type.of(value).displayName();
        } catch (IllegalStateException e) {
            return value.getClass().getSimpleName();
        }
    }
}
