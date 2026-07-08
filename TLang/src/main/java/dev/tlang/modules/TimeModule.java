package dev.tlang.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.runtime.filesystem.StdlibOps;

public final class TimeModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public TimeModule() {
        exports.put("now", new NativeFunction("now", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                return StdlibOps.now();
            }
        });

        exports.put("elapsed_seconds", new NativeFunction("elapsed_seconds", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object start = args.get(0);
                if (Type.of(start) != Type.NUMBER) {
                    throw new RuntimeError(token, "Argument to 'elapsed_seconds' must be an integer.");
                }
                return StdlibOps.now() - (Integer) start;
            }
        });
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
