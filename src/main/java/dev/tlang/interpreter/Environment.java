package dev.tlang.interpreter;

import dev.tlang.errors.RuntimeError;

import java.util.HashMap;
import java.util.Map;

import dev.tlang.lexer.Token;

/**
 * A lexical environment that maps variable names to values.
 *
 * Environments form a chain: each environment has an optional
 * enclosing (parent) scope.  Variable lookup walks up the chain
 * until the name is found or the global scope is exhausted.
 */
public final class Environment {

    private final Map<String, Object> values = new HashMap<>();
    private final Environment enclosing;

    /** Create the global (top-level) environment. */
    public Environment() {
        this.enclosing = null;
    }

    /** Create a child environment enclosed by the given parent. */
    public Environment(Environment enclosing) {
        this.enclosing = enclosing;
    }

    /**
     * Define a new variable in the current scope.
     * Re-defining an existing name in the same scope overwrites it.
     */
    public void define(String name, Object value) {
        values.put(name, value);
    }

    /**
     * Look up a variable by name, walking up enclosing scopes.
     *
     * @throws RuntimeError if the variable is not defined anywhere
     */
    public Object get(Token name) {
        if (values.containsKey(name.getLexeme())) {
            return values.get(name.getLexeme());
        }
        if (enclosing != null) {
            return enclosing.get(name);
        }
        throw new RuntimeError(name,
                "Undefined variable '" + name.getLexeme() + "'.");
    }

    /**
     * Assign a new value to an existing variable, walking up
     * enclosing scopes to find where it was defined.
     *
     * @throws RuntimeError if the variable is not defined anywhere
     */
    public void assign(Token name, Object value) {
        if (values.containsKey(name.getLexeme())) {
            values.put(name.getLexeme(), value);
            return;
        }
        if (enclosing != null) {
            enclosing.assign(name, value);
            return;
        }
        throw new RuntimeError(name,
                "Undefined variable '" + name.getLexeme() + "'.");
    }

    /** Return a copy of all values bound directly in this scope. */
    public Map<String, Object> getValues() {
        return new HashMap<>(values);
    }
}
