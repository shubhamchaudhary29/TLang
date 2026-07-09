package dev.tlang.modules;

import java.util.HashMap;
import java.util.Map;

public final class ModuleRegistry {
    private static final Map<String, NativeModule> REGISTRY = new HashMap<>();

    static {
        REGISTRY.put("math", new MathModule());
        REGISTRY.put("filesystem", new FilesystemModule());
        REGISTRY.put("time", new TimeModule());
        REGISTRY.put("random", new RandomModule());
        REGISTRY.put("strings", new StringsModule());
        REGISTRY.put("json", new JsonModule());
        REGISTRY.put("http", new HttpModule());
        REGISTRY.put("db", new DatabaseModule());
        REGISTRY.put("config", new ConfigModule());
        REGISTRY.put("log", new LogModule());
        REGISTRY.put("crypto", new CryptoModule());
        REGISTRY.put("validate", new ValidateModule());
    }

    public static Map<String, Object> getModule(String name) {
        NativeModule mod = REGISTRY.get(name);
        return mod != null ? mod.getExports() : null;
    }

    public static boolean hasModule(String name) {
        return REGISTRY.containsKey(name);
    }
}
