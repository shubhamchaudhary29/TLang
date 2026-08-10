package dev.tlang.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.runtime.filesystem.StdlibOps;

public final class CacheModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();
    private final Map<String, CacheEntry> store = new LinkedHashMap<>();

    private static final class CacheEntry {
        final Object value;
        final long expiryTimeSeconds;

        CacheEntry(Object value, long expiryTimeSeconds) {
            this.value = value;
            this.expiryTimeSeconds = expiryTimeSeconds;
        }
    }

    public CacheModule() {
        exports.put("set", new NativeFunction("set", 3) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object keyObj = args.get(0);
                Object valueObj = args.get(1);
                Object ttlObj = args.get(2);

                if (Type.of(keyObj) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'set' must be a string.");
                }
                if (Type.of(ttlObj) != Type.NUMBER) {
                    throw new RuntimeError(token, "Third argument to 'set' must be an integer.");
                }

                String key = (String) keyObj;
                int ttl = (Integer) ttlObj;
                long expiry = (long) StdlibOps.now() + ttl;

                store.put(key, new CacheEntry(valueObj, expiry));
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
                long now = StdlibOps.now();
                CacheEntry entry = store.get(key);
                if (entry == null) {
                    return null;
                }
                if (now >= entry.expiryTimeSeconds) {
                    store.remove(key);
                    return null;
                }
                return entry.value;
            }
        });

        exports.put("delete", new NativeFunction("delete", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object keyObj = args.get(0);
                if (Type.of(keyObj) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'delete' must be a string.");
                }

                String key = (String) keyObj;
                long now = StdlibOps.now();
                CacheEntry entry = store.get(key);
                if (entry == null) {
                    return false;
                }
                store.remove(key);
                if (now >= entry.expiryTimeSeconds) {
                    return false;
                }
                return true;
            }
        });
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
