package TLang.runtime;

import java.util.List;

import TLang.ast.Expr;
import TLang.ast.Stmt;
import TLang.lexer.Token;

/**
 * Runtime representation of a user-defined function.
 *
 * Captures the function declaration details (name, parameters, default values, body)
 * and the environment in which it was defined (closure).
 */
public final class TinyFunction {

    private final String name;
    private final List<Token> params;
    private final List<Expr> defaults;
    private final List<Stmt> body;
    private final Environment closure;

    public TinyFunction(String name, List<Token> params, List<Expr> defaults, List<Stmt> body, Environment closure) {
        this.name = name;
        this.params = params;
        this.defaults = defaults;
        this.body = body;
        this.closure = closure;
    }

    public String getName() {
        return name;
    }

    public int getRequiredCount() {
        int requiredCount = 0;
        while (requiredCount < defaults.size() && defaults.get(requiredCount) == null) {
            requiredCount++;
        }
        return requiredCount;
    }

    public int getTotalCount() {
        return params.size();
    }

    public int arity() {
        return params.size();
    }

    /**
     * Call this function with the given arguments.
     *
     * Creates a new environment enclosed by the closure,
     * binds parameters to arguments, evaluates defaults if needed, and executes the body.
     *
     * @return the return value, or null if no return statement was reached
     */
    public Object call(Interpreter interpreter, List<Object> arguments) {
        Environment env = new Environment(closure);

        // Bind passed arguments
        for (int i = 0; i < arguments.size(); i++) {
            env.define(params.get(i).getLexeme(), arguments.get(i));
        }

        // Bind default values for missing trailing arguments
        for (int i = arguments.size(); i < params.size(); i++) {
            Expr defaultExpr = defaults.get(i);
            Object value = interpreter.evaluateInEnvironment(defaultExpr, env);
            env.define(params.get(i).getLexeme(), value);
        }

        try {
            interpreter.executeBlock(body, env);
        } catch (ReturnException ret) {
            return ret.getValue();
        }

        return null;  // implicit return
    }

    @Override
    public String toString() {
        if (name.equals("<anonymous>")) {
            return name;
        }
        return "<function " + name + ">";
    }
}
