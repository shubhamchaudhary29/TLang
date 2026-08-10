package dev.tlang.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.runtime.filesystem.StdlibOps;

public final class RandomModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public RandomModule() {
        exports.put("between", new NativeFunction("between", 2) {
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

        exports.put("boolean", new NativeFunction("boolean", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                return StdlibOps.randomBoolean();
            }
        });

        exports.put("choice", new NativeFunction("choice", 1) {
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

        exports.put("shuffle", new NativeFunction("shuffle", 1) {
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
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
