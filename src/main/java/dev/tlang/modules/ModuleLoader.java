package dev.tlang.modules;

import dev.tlang.interpreter.Interpreter;
import dev.tlang.runtime.task.TaskRuntime;

import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeErrorKind;

import dev.tlang.errors.LexerError;

import dev.tlang.errors.ParseError;
import dev.tlang.errors.ModuleLoadError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.tlang.ast.Stmt;
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
    public synchronized Map<String, Object> load(String moduleName, Token importToken) {
        return load(moduleName, importToken, new TaskRuntime());
    }

    /** Load a module as part of the given root interpreter task runtime. */
    public synchronized Map<String, Object> load(
            String moduleName, Token importToken, TaskRuntime taskRuntime) {
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
            throw new RuntimeError(RuntimeErrorKind.IMPORT_ERROR, importToken,
                "Circular import detected involving module '" + moduleName + "'.");
        }

        loading.add(cacheKey);

        try {
            // Read file content
            String source;
            try {
                source = new String(Files.readAllBytes(modulePath));
            } catch (IOException e) {
                throw new RuntimeError(RuntimeErrorKind.IMPORT_ERROR, importToken,
                    "Module '" + moduleName + "' not found.", e);
            }

            // Lex
            Lexer lexer = new Lexer(source, modulePath.toString());
            List<Token> tokens;
            try {
                tokens = lexer.tokenize();
            } catch (LexerError e) {
                throw moduleError(importToken, moduleName, source, modulePath, e.getLine(), e.getColumn(),
                    "Lexer error", e.getRawMessage(), 65);
            }

            // Parse
            Parser parser = new Parser(tokens);
            List<Stmt> program;
            try {
                program = parser.parse();
            } catch (ParseError e) {
                Token t = e.getToken();
                throw moduleError(importToken, moduleName, source, modulePath, t.getLine(), t.getColumn(),
                    "Parse error", e.getRawMessage(), 65);
            }

            // Resolve (Semantic analysis)
            Resolver resolver = new Resolver();
            List<SemanticError> errors = resolver.resolve(program);
            if (!errors.isEmpty()) {
                SemanticError first = errors.get(0);
                throw moduleError(importToken, moduleName, source, modulePath,
                    first.getLine(), first.getColumn(), "Semantic error", first.getMessage(), 65);
            }

            // Interpret inside a fresh global Environment sharing the same ModuleLoader
            Interpreter moduleInterpreter = new Interpreter(this, taskRuntime);
            try {
                moduleInterpreter.interpret(program);
            } catch (ModuleLoadError e) {
                throw e;
            } catch (RuntimeError e) {
                throw e.withFrame(dev.tlang.errors.RuntimeStackFrame.module(
                    moduleName, dev.tlang.errors.SourceLocation.from(importToken)));
            }

            // Collect all top-level bindings
            Map<String, Object> exports = moduleInterpreter.getGlobalEnvironment().getValues();
            loadedModules.put(cacheKey, exports);
            return exports;

        } finally {
            loading.remove(cacheKey);
        }
    }

    private static ModuleLoadError moduleError(
            Token importToken,
            String moduleName,
            String source,
            Path modulePath,
            int line,
            int column,
            String kind,
            String rawMessage,
            int exitCode) {
        return new ModuleLoadError(
            importToken,
            "Failed to load module '" + moduleName + "': " + kind.toLowerCase()
                + " at " + line + ":" + column + ": " + rawMessage,
            source,
            modulePath.toString(),
            line,
            column,
            kind,
            rawMessage,
            exitCode
        );
    }
}
