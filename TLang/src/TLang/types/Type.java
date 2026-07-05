package TLang.types;

public enum Type {
    NUMBER, STRING, BOOLEAN, FUNCTION, LIST, MAP, NULL;

    /** Classify a runtime value. This is the ONLY place that should do
     *  broad instanceof-based type classification going forward. */
    public static Type of(Object value) {
        if (value == null) return NULL;
        if (value instanceof Integer) return NUMBER;
        if (value instanceof String) return STRING;
        if (value instanceof Boolean) return BOOLEAN;
        if (value instanceof TLang.runtime.TinyFunction || value instanceof TLang.runtime.NativeFunction) return FUNCTION;
        if (value instanceof java.util.List) return LIST;
        if (value instanceof java.util.Map) return MAP;
        throw new IllegalStateException("Unclassifiable runtime value: " + value.getClass());
    }

    /** Human-readable name for error messages. Must match
     *  the exact strings Interpreter.typeName() currently produces, or
     *  you'll break existing test expectations. */
    public String displayName() {
        switch (this) {
            case NUMBER:   return "integer";
            case STRING:   return "string";
            case BOOLEAN:  return "boolean";
            case FUNCTION: return "function";
            case LIST:     return "list";
            case MAP:      return "map";
            case NULL:     return "nil";
        }
        throw new IllegalStateException("Unreachable");
    }
}
