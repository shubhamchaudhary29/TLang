package TLang.runtime;

import java.util.List;

import TLang.ast.FunctionStmt;
import TLang.ast.Stmt;
import TLang.lexer.Token;

/**
 * Runtime representation of a user-defined function.
 *
 * Captures the function declaration (name, parameters, body)
 * and the environment in which it was defined (closure).
 */
public final class TinyFunction {

    private final FunctionStmt declaration;
    private final Environment closure;

    public TinyFunction(FunctionStmt declaration, Environment closure) {
        this.declaration = declaration;
        this.closure = closure;
    }

    public String getName() {
        return declaration.getName().getLexeme();
    }

    public int arity() {
        return declaration.getParams().size();
    }

    /**
     * Call this function with the given arguments.
     *
     * Creates a new environment enclosed by the closure,
     * binds parameters to arguments, and executes the body.
     *
     * @return the return value, or null if no return statement was reached
     */
    public Object call(Interpreter interpreter, List<Object> arguments) {
        Environment env = new Environment(closure);

        // Bind each parameter to its argument
        List<Token> params = declaration.getParams();
        for (int i = 0; i < params.size(); i++) {
            env.define(params.get(i).getLexeme(), arguments.get(i));
        }

        try {
            interpreter.executeBlock(declaration.getBody(), env);
        } catch (ReturnException ret) {
            return ret.getValue();
        }

        return null;  // implicit return
    }

    @Override
    public String toString() {
        return "<function " + getName() + ">";
    }
}
