package dev.tlang.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.runtime.json.JsonParser;

public final class JsonModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public JsonModule() {
        exports.put("stringify", new NativeFunction("stringify", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                return jsonStringify(args.get(0), token);
            }
        });

        exports.put("parse", new NativeFunction("parse", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object arg = args.get(0);
                if (Type.of(arg) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'parse' must be a string.");
                }
                JsonParser jp = new JsonParser((String) arg, token);
                return jp.parse();
            }
        });
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }

    public static String jsonStringifyExternal(Object value, Token token) {
        return jsonStringify(value, token);
    }

    private static String jsonStringify(Object value, Token token) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Integer) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof String) {
            return escapeJsonString((String) value);
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < list.size(); i++) {
                sb.append(jsonStringify(list.get(i), token));
                if (i < list.size() - 1) {
                    sb.append(",");
                }
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new RuntimeError(token, "JSON object keys must be strings.");
                }
                sb.append(escapeJsonString((String) entry.getKey()));
                sb.append(":");
                sb.append(jsonStringify(entry.getValue(), token));
                if (i < map.size() - 1) {
                    sb.append(",");
                }
                i++;
            }
            sb.append("}");
            return sb.toString();
        }
        throw new RuntimeError(token, "Cannot serialize a function to JSON.");
    }

    private static String escapeJsonString(String str) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
