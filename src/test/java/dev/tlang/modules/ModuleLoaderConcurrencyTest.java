package dev.tlang.modules;

import dev.tlang.errors.RuntimeError;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ModuleLoaderConcurrencyTest {
    private static final Token TOKEN = new Token(TokenType.IDENTIFIER, "import", null, 1, 1);

    @Test
    void concurrentFirstImportExecutesUserModuleOnce(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("shared.tiny"), """
            let moduleName be "shared"
            define greet taking name
                return moduleName + ":" + name
            """);
        ModuleLoader loader = new ModuleLoader(directory);

        List<CompletableFuture<Map<String, Object>>> imports = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            imports.add(CompletableFuture.supplyAsync(() -> loader.load("shared", TOKEN)));
        }
        CompletableFuture.allOf(imports.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

        Map<String, Object> first = imports.get(0).join();
        assertEquals("shared", first.get("moduleName"));
        for (CompletableFuture<Map<String, Object>> imported : imports) {
            assertSame(first, imported.join(), "all requests should observe the atomic module cache entry");
        }
    }

    @Test
    void badModuleRaisesRuntimeErrorWithoutTerminatingProcess(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("broken.tiny"), "let value be");
        Files.writeString(directory.resolve("healthy.tiny"), "let value be 42");
        ModuleLoader loader = new ModuleLoader(directory);

        RuntimeError failure = assertThrows(RuntimeError.class, () -> loader.load("broken", TOKEN));
        assertTrue(failure.getMessage().contains("parse error"));
        assertEquals(42, loader.load("healthy", TOKEN).get("value"));
    }
}
