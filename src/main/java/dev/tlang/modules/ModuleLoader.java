package dev.tlang.modules;

import dev.tlang.interpreter.Interpreter;

import dev.tlang.errors.RuntimeError;

import dev.tlang.errors.LexerError;

import dev.tlang.errors.ParseError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.tlang.ast.Stmt;
import dev.tlang.errors.ErrorFormatter;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.parser.Parser;
import dev.tlang.resolver.Resolver;
import dev.tlang.errors.SemanticError;

/**
 * Loader and cache for TLang modules.
 */
public final class ModuleLoader {
    private final Path scriptDir;
    private final Map<String, Map<String, Object>> loadedModules = new HashMap<>();
    private final Set<String> loading = new HashSet<>();

    public ModuleLoader(Path scriptDir) {
        this.scriptDir = scriptDir;
    }

    /**
     * Load a module by name.
     *
     * @param moduleName  the name of the module (e.g. "math" or "greeter_module")
     * @param importToken the token of the import statement, for error location reporting
     * @return the module's exported bindings as a Map
     */
    public Map<String, Object> load(String moduleName, Token importToken) {
        // 1. Check Native modules registry
        Map<String, Object> nativeModule = dev.tlang.modules.ModuleRegistry.getModule(moduleName);
        if (nativeModule != null) {
            if (loadedModules.containsKey(moduleName)) {
                return loadedModules.get(moduleName);
            }
            loadedModules.put(moduleName, nativeModule);
            return nativeModule;
        }

        // 2. Resolve to absolute file path relative to script directory
        Path modulePath = scriptDir.resolve(moduleName + ".tiny").toAbsolutePath().normalize();
        String cacheKey = modulePath.toString();

        if (loadedModules.containsKey(cacheKey)) {
            return loadedModules.get(cacheKey);
        }

        // 3. Circular dependency check
        if (loading.contains(cacheKey)) {
            throw new RuntimeError(importToken, "Circular import detected involving module '" + moduleName + "'.");
        }

        loading.add(cacheKey);

        try {
            // Read file content
            String source;
            try {
                source = new String(Files.readAllBytes(modulePath));
            } catch (IOException e) {
                throw new RuntimeError(importToken, "Module '" + moduleName + "' not found.");
            }

            // Lex
            Lexer lexer = new Lexer(source);
            List<Token> tokens;
            try {
                tokens = lexer.tokenize();
            } catch (LexerError e) {
                System.err.println(ErrorFormatter.format(source, modulePath.toString(), e.getLine(), e.getColumn(), "Lexer error", e.getRawMessage()));
                System.exit(65);
                return null;
            }

            // Parse
            Parser parser = new Parser(tokens);
            List<Stmt> program;
            try {
                program = parser.parse();
            } catch (ParseError e) {
                Token t = e.getToken();
                System.err.println(ErrorFormatter.format(source, modulePath.toString(), t.getLine(), t.getColumn(), "Parse error", e.getRawMessage()));
                System.exit(65);
                return null;
            }

            // Resolve (Semantic analysis)
            Resolver resolver = new Resolver();
            List<SemanticError> errors = resolver.resolve(program);
            if (!errors.isEmpty()) {
                for (SemanticError err : errors) {
                    System.err.println(ErrorFormatter.format(source, modulePath.toString(), err.getLine(), err.getColumn(), "Semantic error", err.getMessage()));
                }
                System.exit(65);
                return null;
            }

            // Interpret inside a fresh global Environment sharing the same ModuleLoader
            Interpreter moduleInterpreter = new Interpreter(this);
            try {
                moduleInterpreter.interpret(program);
            } catch (RuntimeError e) {
                Token t = e.getToken();
                if (t != null) {
                    System.err.println(ErrorFormatter.format(source, modulePath.toString(), t.getLine(), t.getColumn(), "Runtime error", e.getMessage()));
                } else {
                    System.err.println(ErrorFormatter.format(source, modulePath.toString(), 0, 0, "Runtime error", e.getMessage()));
                }
                System.exit(70);
                return null;
            }

            // Collect all top-level bindings
            Map<String, Object> exports = moduleInterpreter.getGlobalEnvironment().getValues();
            loadedModules.put(cacheKey, exports);
            return exports;

        } finally {
            loading.remove(cacheKey);
        }
    }
}
