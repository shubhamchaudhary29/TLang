package TLang.runtime;

import java.util.List;
import TLang.lexer.Token;

/**
 * Base class for all native functions in TLang.
 */
public abstract class NativeFunction {
    private final String name;
    private final int arity;

    protected NativeFunction(String name, int arity) {
        this.name = name;
        this.arity = arity;
    }

    public String getName() {
        return name;
    }

    public int getArity() {
        return arity;
    }

    /**
     * Call the native function with the given arguments.
     *
     * @param args  the argument values
     * @param token the token triggering the call, for error reporting
     * @return the result of execution
     */
    public abstract Object call(List<Object> args, Token token);

    @Override
    public String toString() {
        return "<native function " + name + ">";
    }
}
