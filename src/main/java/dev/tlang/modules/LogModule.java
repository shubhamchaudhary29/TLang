package dev.tlang.modules;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;

public final class LogModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public LogModule() {
        exports.put("debug", createLogFunction("debug", "DEBUG"));
        exports.put("info", createLogFunction("info", "INFO"));
        exports.put("warn", createLogFunction("warn", "WARN"));
        exports.put("error", createLogFunction("error", "ERROR"));
    }

    private NativeFunction createLogFunction(String name, String level) {
        return new NativeFunction(name, 1, 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object messageObj = args.get(0);
                if (Type.of(messageObj) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to log functions must be a string.");
                }
                Map<?, ?> fields = null;
                if (args.size() == 2) {
                    Object fieldsObj = args.get(1);
                    if (Type.of(fieldsObj) != Type.MAP) {
                        throw new RuntimeError(token, "Second argument to log functions must be a map.");
                    }
                    fields = (Map<?, ?>) fieldsObj;
                }

                // Construct log map
                Map<String, Object> logMap = new LinkedHashMap<>();
                if (System.getenv("TLANG_TEST") != null) {
                    logMap.put("timestamp", "2026-07-09T00:00:00Z");
                } else {
                    logMap.put("timestamp", Instant.now().toString());
                }
                logMap.put("level", level);
                logMap.put("message", messageObj);

                if (fields != null) {
                    for (Map.Entry<?, ?> entry : fields.entrySet()) {
                        if (!(entry.getKey() instanceof String)) {
                            throw new RuntimeError(token, "Log fields keys must be strings.");
                        }
                        logMap.put((String) entry.getKey(), entry.getValue());
                    }
                }

                String jsonStr = JsonModule.jsonStringifyExternal(logMap, token);
                if ("ERROR".equals(level)) {
                    System.err.println(jsonStr);
                } else {
                    System.out.println(jsonStr);
                }
                return null;
            }
        };
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
