package dev.tlang.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.runtime.filesystem.StdlibOps;

public final class ConfigModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();
    private final Map<String, String> envCache = new LinkedHashMap<>();

    public ConfigModule() {
        exports.put("load", new NativeFunction("load", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                if (StdlibOps.fileExists(".env")) {
                    String content = StdlibOps.readFile(".env", token);
                    parseEnv(content, token);
                }
                return null;
            }
        });

        exports.put("get", new NativeFunction("get", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object keyObj = args.get(0);
                if (Type.of(keyObj) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'get' must be a string.");
                }
                String key = (String) keyObj;
                String val = System.getenv(key);
                if (val == null) {
                    val = envCache.get(key);
                }
                return val;
            }
        });

        exports.put("getOr", new NativeFunction("getOr", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object keyObj = args.get(0);
                Object defaultObj = args.get(1);
                if (Type.of(keyObj) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'getOr' must be a string.");
                }
                if (Type.of(defaultObj) != Type.STRING) {
                    throw new RuntimeError(token, "Second argument to 'getOr' must be a string.");
                }
                String key = (String) keyObj;
                String val = System.getenv(key);
                if (val == null) {
                    val = envCache.get(key);
                }
                return val != null ? val : (String) defaultObj;
            }
        });

        exports.put("require", new NativeFunction("require", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object keyObj = args.get(0);
                if (Type.of(keyObj) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'require' must be a string.");
                }
                String key = (String) keyObj;
                String val = System.getenv(key);
                if (val == null) {
                    val = envCache.get(key);
                }
                if (val == null) {
                    throw new RuntimeError(token, "Configuration key '" + key + "' is required but not set.");
                }
                return val;
            }
        });
    }

    private void parseEnv(String content, Token token) {
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eqIdx = trimmed.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eqIdx).trim();
            String val = trimmed.substring(eqIdx + 1).trim();
            if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            } else if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            }
            envCache.put(key, val);
        }
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
