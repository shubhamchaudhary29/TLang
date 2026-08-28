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
import dev.tlang.packages.PackageContext;

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
    private final PackageContext packageContext;
    private final Map<String, Map<String, Object>> loadedModules = new HashMap<>();
    private final Set<String> loading = new HashSet<>();

    public ModuleLoader(Path scriptDir) {
        this.scriptDir = scriptDir.toAbsolutePath().normalize();
        this.packageContext = PackageContext.discover(this.scriptDir);
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

        // 2. Resolve user/project/package modules without mutable global context.
        Path modulePath = resolveModulePath(moduleName, importToken);
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

    private Path resolveModulePath(String moduleName, Token importToken) {
        Path importingFile = sourcePath(importToken);
        Path importingDirectory = importingFile == null ? scriptDir : importingFile.getParent();
        if (importingDirectory != null) {
            Path sibling = importingDirectory.resolve(moduleName + ".tiny").toAbsolutePath().normalize();
            if (Files.isRegularFile(sibling) && !Files.isSymbolicLink(sibling)) return sibling;
        }
        if (packageContext != null) {
            if (packageContext.isProjectSource(importingFile)) {
                Path projectModule = packageContext.projectRoot().resolve(moduleName + ".tiny").normalize();
                if (Files.isRegularFile(projectModule) && !Files.isSymbolicLink(projectModule)) return projectModule;
            }
            Path dependency = packageContext.resolveDependency(moduleName, importingFile);
            if (dependency != null) return dependency;
        }
        // Preserve a deterministic not-found path without letting dependency
        // modules fall back into unrelated project files.
        Path missingBase = importingDirectory == null ? scriptDir : importingDirectory;
        return missingBase.resolve(moduleName + ".tiny").toAbsolutePath().normalize();
    }

    private Path sourcePath(Token token) {
        String name = token.getSourceUnit().name();
        if (name == null || name.isBlank()) return null;
        try {
            Path raw = Path.of(name);
            if (raw.isAbsolute()) return raw.normalize();
            Path fromWorkingDirectory = raw.toAbsolutePath().normalize();
            Path parent = fromWorkingDirectory.getParent();
            if ((parent != null && parent.equals(scriptDir)) || fromWorkingDirectory.startsWith(scriptDir)) {
                return fromWorkingDirectory;
            }
            return scriptDir.resolve(raw).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException ignored) {
            return null;
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
