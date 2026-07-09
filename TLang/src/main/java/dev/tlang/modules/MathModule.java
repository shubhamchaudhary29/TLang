package dev.tlang.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;

public final class MathModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public MathModule() {
        exports.put("abs", new NativeFunction("abs", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object arg = args.get(0);
                if (Type.of(arg) != Type.NUMBER) {
                    throw new RuntimeError(token, "Argument to 'abs' must be an integer.");
                }
                return Math.abs((Integer) arg);
            }
        });

        exports.put("max", new NativeFunction("max", 2) {
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

        exports.put("min", new NativeFunction("min", 2) {
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

        exports.put("pow", new NativeFunction("pow", 2) {
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

        exports.put("gcd", new NativeFunction("gcd", 2) {
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

        exports.put("floorDiv", new NativeFunction("floorDiv", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object a = args.get(0);
                Object b = args.get(1);
                if (Type.of(a) != Type.NUMBER || Type.of(b) != Type.NUMBER) {
                    throw new RuntimeError(token, "Arguments to 'floorDiv' must be integers.");
                }
                int divisor = (Integer) b;
                if (divisor == 0) {
                    throw new RuntimeError(token, "Division by zero.");
                }
                return Math.floorDiv((Integer) a, divisor);
            }
        });

        exports.put("sign", new NativeFunction("sign", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object arg = args.get(0);
                if (Type.of(arg) != Type.NUMBER) {
                    throw new RuntimeError(token, "Argument to 'sign' must be an integer.");
                }
                return Integer.compare((Integer) arg, 0);
            }
        });
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
