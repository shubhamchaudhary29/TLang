package TLang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import TLang.lexer.Token;
import TLang.types.Type;

/**
 * Registry for built-in native modules.
 */
public final class NativeModules {
    private static final Map<String, Map<String, Object>> MODULES = new HashMap<>();

    static {
        // ── MATH MODULE ─────────────────────────────────────────
        Map<String, Object> math = new LinkedHashMap<>();

        math.put("abs", new NativeFunction("abs", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object arg = args.get(0);
                if (Type.of(arg) != Type.NUMBER) {
                    throw new RuntimeError(token, "Argument to 'abs' must be an integer.");
                }
                return Math.abs((Integer) arg);
            }
        });

        math.put("max", new NativeFunction("max", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object a = args.get(0);
                Object b = args.get(1);
                if (Type.of(a) != Type.NUMBER || Type.of(b) != Type.NUMBER) {
                    throw new RuntimeError(token, "Arguments to 'max' must be integers.");
                }
                return Math.max((Integer) a, (Integer) b);
            }
        });

        math.put("min", new NativeFunction("min", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object a = args.get(0);
                Object b = args.get(1);
                if (Type.of(a) != Type.NUMBER || Type.of(b) != Type.NUMBER) {
                    throw new RuntimeError(token, "Arguments to 'min' must be integers.");
                }
                return Math.min((Integer) a, (Integer) b);
            }
        });

        math.put("pow", new NativeFunction("pow", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object base = args.get(0);
                Object exponent = args.get(1);
                if (Type.of(base) != Type.NUMBER || Type.of(exponent) != Type.NUMBER) {
                    throw new RuntimeError(token, "Arguments to 'pow' must be integers.");
                }
                int exp = (Integer) exponent;
                if (exp < 0) {
                    throw new RuntimeError(token, "Exponent must be non-negative.");
                }
                int b = (Integer) base;
                return (int) Math.pow(b, exp);
            }
        });

        math.put("gcd", new NativeFunction("gcd", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object a = args.get(0);
                Object b = args.get(1);
                if (Type.of(a) != Type.NUMBER || Type.of(b) != Type.NUMBER) {
                    throw new RuntimeError(token, "Arguments to 'gcd' must be integers.");
                }
                int x = Math.abs((Integer) a);
                int y = Math.abs((Integer) b);
                while (y != 0) {
                    int temp = y;
                    y = x % y;
                    x = temp;
                }
                return x;
            }
        });

        math.put("floor_div", new NativeFunction("floor_div", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object a = args.get(0);
                Object b = args.get(1);
                if (Type.of(a) != Type.NUMBER || Type.of(b) != Type.NUMBER) {
                    throw new RuntimeError(token, "Arguments to 'floor_div' must be integers.");
                }
                int divisor = (Integer) b;
                if (divisor == 0) {
                    throw new RuntimeError(token, "Division by zero.");
                }
                return Math.floorDiv((Integer) a, divisor);
            }
        });

        math.put("sign", new NativeFunction("sign", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object arg = args.get(0);
                if (Type.of(arg) != Type.NUMBER) {
                    throw new RuntimeError(token, "Argument to 'sign' must be an integer.");
                }
                return Integer.compare((Integer) arg, 0);
            }
        });

        MODULES.put("math", math);

        // ── FILESYSTEM MODULE ───────────────────────────────────
        Map<String, Object> filesystem = new LinkedHashMap<>();

        filesystem.put("read", new NativeFunction("read", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'read' must be a string.");
                }
                return StdlibOps.readFile((String) path, token);
            }
        });

        filesystem.put("write", new NativeFunction("write", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                Object content = args.get(1);
                if (Type.of(path) != Type.STRING || Type.of(content) != Type.STRING) {
                    throw new RuntimeError(token, "Arguments to 'write' must be strings.");
                }
                StdlibOps.writeFile((String) path, (String) content, token);
                return null;
            }
        });

        filesystem.put("append", new NativeFunction("append", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                Object content = args.get(1);
                if (Type.of(path) != Type.STRING || Type.of(content) != Type.STRING) {
                    throw new RuntimeError(token, "Arguments to 'append' must be strings.");
                }
                StdlibOps.appendFile((String) path, (String) content, token);
                return null;
            }
        });

        filesystem.put("exists", new NativeFunction("exists", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'exists' must be a string.");
                }
                return StdlibOps.fileExists((String) path);
            }
        });

        filesystem.put("delete", new NativeFunction("delete", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'delete' must be a string.");
                }
                return StdlibOps.deleteFile((String) path);
            }
        });

        filesystem.put("list", new NativeFunction("list", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'list' must be a string.");
                }
                return new ArrayList<Object>(StdlibOps.listDirectory((String) path, token));
            }
        });

        MODULES.put("filesystem", filesystem);

        // ── TIME MODULE ─────────────────────────────────────────
        Map<String, Object> time = new LinkedHashMap<>();

        time.put("now", new NativeFunction("now", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                return StdlibOps.now();
            }
        });

        time.put("elapsed_seconds", new NativeFunction("elapsed_seconds", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object start = args.get(0);
                if (Type.of(start) != Type.NUMBER) {
                    throw new RuntimeError(token, "Argument to 'elapsed_seconds' must be an integer.");
                }
                return StdlibOps.now() - (Integer) start;
            }
        });

        MODULES.put("time", time);

        // ── RANDOM MODULE ───────────────────────────────────────
        Map<String, Object> random = new LinkedHashMap<>();

        random.put("between", new NativeFunction("between", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object min = args.get(0);
                Object max = args.get(1);
                if (Type.of(min) != Type.NUMBER || Type.of(max) != Type.NUMBER) {
                    throw new RuntimeError(token, "Arguments to 'between' must be integers.");
                }
                return StdlibOps.randomBetween((Integer) min, (Integer) max, token);
            }
        });

        random.put("boolean", new NativeFunction("boolean", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                return StdlibOps.randomBoolean();
            }
        });

        random.put("choice", new NativeFunction("choice", 1) {
            @Override
            @SuppressWarnings("unchecked")
            public Object call(List<Object> args, Token token) {
                Object listVal = args.get(0);
                if (Type.of(listVal) != Type.LIST) {
                    throw new RuntimeError(token, "Argument to 'choice' must be a list.");
                }
                return StdlibOps.randomChoice((List<Object>) listVal, token);
            }
        });

        random.put("shuffle", new NativeFunction("shuffle", 1) {
            @Override
            @SuppressWarnings("unchecked")
            public Object call(List<Object> args, Token token) {
                Object listVal = args.get(0);
                if (Type.of(listVal) != Type.LIST) {
                    throw new RuntimeError(token, "Argument to 'shuffle' must be a list.");
                }
                return StdlibOps.randomShuffle((List<Object>) listVal);
            }
        });

        MODULES.put("random", random);

        // ── STRINGS MODULE ──────────────────────────────────────
        Map<String, Object> strings = new LinkedHashMap<>();

        strings.put("join", new NativeFunction("join", 2) {
            @Override
            @SuppressWarnings("unchecked")
            public Object call(List<Object> args, Token token) {
                Object listVal = args.get(0);
                Object sepVal = args.get(1);
                if (Type.of(listVal) != Type.LIST) {
                    throw new RuntimeError(token, "First argument to 'join' must be a list.");
                }
                if (Type.of(sepVal) != Type.STRING) {
                    throw new RuntimeError(token, "Second argument to 'join' must be a string separator.");
                }
                List<Object> list = (List<Object>) listVal;
                String separator = (String) sepVal;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.size(); i++) {
                    sb.append(Interpreter.stringify(list.get(i)));
                    if (i < list.size() - 1) {
                        sb.append(separator);
                    }
                }
                return sb.toString();
            }
        });

        strings.put("repeat", new NativeFunction("repeat", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object strVal = args.get(0);
                Object countVal = args.get(1);
                if (Type.of(strVal) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'repeat' must be a string.");
                }
                if (Type.of(countVal) != Type.NUMBER) {
                    throw new RuntimeError(token, "Second argument to 'repeat' must be an integer count.");
                }
                String str = (String) strVal;
                int count = (Integer) countVal;
                if (count < 0) {
                    throw new RuntimeError(token, "Repeat count must be non-negative.");
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    sb.append(str);
                }
                return sb.toString();
            }
        });

        strings.put("reverse", new NativeFunction("reverse", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object strVal = args.get(0);
                if (Type.of(strVal) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'reverse' must be a string.");
                }
                return new StringBuilder((String) strVal).reverse().toString();
            }
        });

        strings.put("isBlank", new NativeFunction("isBlank", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object strVal = args.get(0);
                if (Type.of(strVal) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'isBlank' must be a string.");
                }
                return ((String) strVal).trim().isEmpty();
            }
        });

        strings.put("padLeft", new NativeFunction("padLeft", 3) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object strVal = args.get(0);
                Object widthVal = args.get(1);
                Object padVal = args.get(2);
                if (Type.of(strVal) != Type.STRING || Type.of(widthVal) != Type.NUMBER || Type.of(padVal) != Type.STRING) {
                    throw new RuntimeError(token, "Arguments to 'padLeft' must be (string, integer, string).");
                }
                String str = (String) strVal;
                int width = (Integer) widthVal;
                String pad = (String) padVal;
                if (pad.length() != 1) {
                    throw new RuntimeError(token, "Padding character must be a string of length 1.");
                }
                if (str.length() >= width) {
                    return str;
                }
                StringBuilder sb = new StringBuilder();
                int needed = width - str.length();
                char c = pad.charAt(0);
                for (int i = 0; i < needed; i++) {
                    sb.append(c);
                }
                sb.append(str);
                return sb.toString();
            }
        });

        strings.put("padRight", new NativeFunction("padRight", 3) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object strVal = args.get(0);
                Object widthVal = args.get(1);
                Object padVal = args.get(2);
                if (Type.of(strVal) != Type.STRING || Type.of(widthVal) != Type.NUMBER || Type.of(padVal) != Type.STRING) {
                    throw new RuntimeError(token, "Arguments to 'padRight' must be (string, integer, string).");
                }
                String str = (String) strVal;
                int width = (Integer) widthVal;
                String pad = (String) padVal;
                if (pad.length() != 1) {
                    throw new RuntimeError(token, "Padding character must be a string of length 1.");
                }
                if (str.length() >= width) {
                    return str;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(str);
                int needed = width - str.length();
                char c = pad.charAt(0);
                for (int i = 0; i < needed; i++) {
                    sb.append(c);
                }
                return sb.toString();
            }
        });

        MODULES.put("strings", strings);

        // ── JSON MODULE ─────────────────────────────────────────
        Map<String, Object> json = new LinkedHashMap<>();

        json.put("stringify", new NativeFunction("stringify", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                return jsonStringify(args.get(0), token);
            }
        });

        json.put("parse", new NativeFunction("parse", 1) {
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

        MODULES.put("json", json);
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

    /**
     * Retrieve a native module by name, returning a fresh Map
     * referencing the native functions. Returns null if not found.
     */
    public static Map<String, Object> getModule(String name) {
        if (MODULES.containsKey(name)) {
            return new LinkedHashMap<>(MODULES.get(name));
        }
        return null;
    }
}
