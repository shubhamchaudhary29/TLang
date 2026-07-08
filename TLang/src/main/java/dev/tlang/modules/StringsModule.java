package dev.tlang.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.interpreter.Interpreter;

public final class StringsModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public StringsModule() {
        exports.put("join", new NativeFunction("join", 2) {
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

        exports.put("repeat", new NativeFunction("repeat", 2) {
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

        exports.put("reverse", new NativeFunction("reverse", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object strVal = args.get(0);
                if (Type.of(strVal) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'reverse' must be a string.");
                }
                return new StringBuilder((String) strVal).reverse().toString();
            }
        });

        exports.put("isBlank", new NativeFunction("isBlank", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object strVal = args.get(0);
                if (Type.of(strVal) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'isBlank' must be a string.");
                }
                return ((String) strVal).trim().isEmpty();
            }
        });

        exports.put("padLeft", new NativeFunction("padLeft", 3) {
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

        exports.put("padRight", new NativeFunction("padRight", 3) {
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
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
