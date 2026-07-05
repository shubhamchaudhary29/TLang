package TLang.semantic;

import java.util.List;
import java.util.Map;

public enum Type {
    NUMBER,
    STRING,
    BOOLEAN,
    FUNCTION,
    LIST,
    MAP,
    NULL;

    public static Type fromRuntimeValue(Object value) {
        if (value instanceof Integer) return NUMBER;
        if (value instanceof String)  return STRING;
        if (value instanceof Boolean) return BOOLEAN;
        if (value instanceof List)    return LIST;
        if (value instanceof Map)     return MAP;
        if (value == null)            return NULL;
        return FUNCTION;
    }
}
