package dev.tlang.interpreter;

import java.util.List;
import dev.tlang.lexer.Token;

/**
 * Base class for all native functions in TLang.
 */
public abstract class NativeFunction {
    private final String name;
    private final int minArity;
    private final int maxArity;

    protected NativeFunction(String name, int arity) {
        this.name = name;
        this.minArity = arity;
        this.maxArity = arity;
    }

    protected NativeFunction(String name, int minArity, int maxArity) {
        this.name = name;
        this.minArity = minArity;
        this.maxArity = maxArity;
    }

    public String getName() {
        return name;
    }

    private boolean expectsReceiver = false;

    public boolean expectsReceiver() {
        return expectsReceiver;
    }

    public NativeFunction setExpectsReceiver(boolean expectsReceiver) {
        this.expectsReceiver = expectsReceiver;
        return this;
    }

    public int getArity() {
        return maxArity;
    }

    public int getMinArity() {
        return minArity;
    }

    public int getMaxArity() {
        return maxArity;
    }

    /**
     * Call the native function with the given arguments, providing the interpreter.
     * Subclasses that need the interpreter can override this.
     * By default, it delegates to call(args, token) for backward compatibility.
     */
    public Object call(Interpreter interpreter, List<Object> args, Token token) {
        return call(args, token);
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
