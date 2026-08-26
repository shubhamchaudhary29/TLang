package dev.tlang.modules;

import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import dev.tlang.interpreter.RuntimeCollections;

public final class ModuleRegistry {
    private static final Map<String, NativeModule> REGISTRY = new HashMap<>();
    private static final Map<String, Map<String, Object>> EXPORTS = new HashMap<>();

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
        REGISTRY.put("jwt", new JwtModule());
        REGISTRY.put("mail", new MailModule());
        REGISTRY.put("cache", new CacheModule());
        for (Map.Entry<String, NativeModule> entry : REGISTRY.entrySet()) {
            EXPORTS.put(entry.getKey(), RuntimeCollections.newMap(entry.getValue().getExports()));
        }
    }

    public static Map<String, Object> getModule(String name) {
        return EXPORTS.get(name);
    }

    public static boolean hasModule(String name) {
        return REGISTRY.containsKey(name);
    }

    /** Stable module metadata for tooling without duplicating the registry. */
    public static Set<String> getModuleNames() {
        return Collections.unmodifiableSet(new TreeSet<>(REGISTRY.keySet()));
    }

    /** Stable export names for completion and other read-only tooling. */
    public static Set<String> getExportNames(String name) {
        Map<String, Object> exports = getModule(name);
        if (exports == null) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new TreeSet<>(RuntimeCollections.snapshot(exports).keySet()));
    }
}
