package dev.tlang.lsp;

import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.ModuleRegistry;
import dev.tlang.resolver.SymbolTable;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds completion candidates from TLang tokens, indentation scopes and module
 * metadata. The analysis is deliberately tolerant: one malformed line cannot
 * prevent completion elsewhere in a document that is still being edited.
 */
final class CompletionAnalyzer {
    private static final Pattern IDENTIFIER_AT_END =
        Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern MEMBER_AT_END =
        Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)?$");
    private static final Pattern IMPORT_AT_END =
        Pattern.compile("^\\s*import(?:\\s+([A-Za-z_][A-Za-z0-9_]*)?)?\\s*$");

    List<CompletionItem> complete(String uri, String source, Position requestedPosition) {
        Objects.requireNonNull(source, "source");
        Cursor cursor = Cursor.clamp(source, requestedPosition);
        String beforeCursor = cursor.lineText().substring(0, cursor.character());

        if (insideCommentOrString(beforeCursor)) {
            return List.of();
        }

        DocumentModel model = DocumentModel.build(source, cursor);
        Matcher member = MEMBER_AT_END.matcher(beforeCursor);
        if (member.find()) {
            return moduleMembers(uri, model, member.group(1), nullToEmpty(member.group(2)));
        }

        Matcher importMatcher = IMPORT_AT_END.matcher(beforeCursor);
        if (importMatcher.matches()) {
            return moduleNames(uri, nullToEmpty(importMatcher.group(1)));
        }

        String prefix = identifierPrefix(beforeCursor);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (String keyword : Lexer.getKeywords()) {
            add(candidates, new Candidate(keyword, CompletionItemKind.Keyword, "TLang keyword"));
        }
        for (String name : SymbolTable.getBuiltInFunctionNames()) {
            add(candidates, new Candidate(name, CompletionItemKind.Function, "built-in function"));
        }
        for (ScopeNode scope : model.ancestorsOuterFirst()) {
            for (Candidate declaration : scope.declarations.values()) {
                // Inner declarations replace outer declarations with the same name.
                candidates.put(declaration.name(), declaration);
            }
        }
        return items(candidates.values(), prefix);
    }

    private List<CompletionItem> moduleNames(String uri, String prefix) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (String name : ModuleRegistry.getModuleNames()) {
            add(candidates, new Candidate(name, CompletionItemKind.Module, "native module"));
        }
        Path directory = documentDirectory(uri);
        if (directory != null) {
            try (var paths = Files.list(directory)) {
                paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".tiny"))
                    .map(name -> name.substring(0, name.length() - 5))
                    .sorted()
                    .forEach(name -> add(candidates,
                        new Candidate(name, CompletionItemKind.Module, "TLang module")));
            } catch (IOException | SecurityException ignored) {
                // Completion must remain available when a workspace cannot be read.
            }
        }
        return items(candidates.values(), prefix);
    }

    private List<CompletionItem> moduleMembers(
            String uri, DocumentModel model, String receiver, String prefix) {
        Candidate binding = model.visibleDeclaration(receiver);
        if (binding == null || binding.kind() != CompletionItemKind.Module) {
            return List.of();
        }

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        if (ModuleRegistry.hasModule(receiver)) {
            for (String export : ModuleRegistry.getExportNames(receiver)) {
                add(candidates, new Candidate(export, CompletionItemKind.Function,
                    receiver + " module export"));
            }
        } else {
            Path directory = documentDirectory(uri);
            if (directory != null) {
                Path modulePath = directory.resolve(receiver + ".tiny").normalize();
                try {
                    if (Files.isRegularFile(modulePath)) {
                        String moduleSource = Files.readString(modulePath);
                        DocumentModel module = DocumentModel.build(
                            moduleSource, Cursor.atEnd(moduleSource));
                        for (Candidate declaration : module.root.declarations.values()) {
                            if (declaration.kind() != CompletionItemKind.Module) {
                                add(candidates, declaration.withDetail(receiver + " module export"));
                            }
                        }
                    }
                } catch (IOException | SecurityException ignored) {
                    // Missing/unreadable modules simply have no known members.
                }
            }
        }
        return items(candidates.values(), prefix);
    }

    private static Path documentDirectory(String uri) {
        try {
            URI parsed = URI.create(uri);
            if (!"file".equalsIgnoreCase(parsed.getScheme())) {
                return null;
            }
            Path path = Path.of(parsed);
            return path.getParent();
        } catch (IllegalArgumentException | SecurityException ex) {
            return null;
        }
    }

    private static List<CompletionItem> items(Iterable<Candidate> source, String prefix) {
        List<Candidate> sorted = new ArrayList<>();
        for (Candidate candidate : source) {
            if (prefix.isEmpty() || candidate.name().startsWith(prefix)) {
                sorted.add(candidate);
            }
        }
        sorted.sort(Comparator.comparing(Candidate::name)
            .thenComparing(candidate -> candidate.kind().getValue()));

        List<CompletionItem> result = new ArrayList<>(sorted.size());
        for (Candidate candidate : sorted) {
            CompletionItem item = new CompletionItem(candidate.name());
            item.setKind(candidate.kind());
            item.setDetail(candidate.detail());
            item.setSortText(candidate.name());
            result.add(item);
        }
        return result;
    }

    private static void add(Map<String, Candidate> candidates, Candidate candidate) {
        candidates.putIfAbsent(candidate.name(), candidate);
    }

    private static String identifierPrefix(String text) {
        Matcher matcher = IDENTIFIER_AT_END.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean insideCommentOrString(String text) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
            } else if (current == '"') {
                inString = true;
            } else if (current == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                return true;
            }
        }
        return inString;
    }

    private record Candidate(String name, CompletionItemKind kind, String detail) {
        Candidate withDetail(String newDetail) {
            return new Candidate(name, kind, newDetail);
        }
    }

    private record Cursor(List<String> lines, int line, int character) {
        static Cursor clamp(String source, Position requested) {
            List<String> lines = List.of(source.split("\\r?\\n", -1));
            int requestedLine = requested == null ? 0 : requested.getLine();
            int line = Math.max(0, Math.min(requestedLine, lines.size() - 1));
            int requestedCharacter = requested == null ? 0 : requested.getCharacter();
            int character = Math.max(0, Math.min(requestedCharacter, lines.get(line).length()));
            return new Cursor(lines, line, character);
        }

        static Cursor atEnd(String source) {
            List<String> lines = List.of(source.split("\\r?\\n", -1));
            int line = lines.size() - 1;
            return new Cursor(lines, line, lines.get(line).length());
        }

        String lineText() {
            return lines.get(line);
        }
    }

    private static final class DocumentModel {
        private final ScopeNode root;
        private final ScopeNode cursorScope;

        private DocumentModel(ScopeNode root, ScopeNode cursorScope) {
            this.root = root;
            this.cursorScope = cursorScope;
        }

        static DocumentModel build(String source, Cursor cursor) {
            List<String> lines = List.of(source.split("\\r?\\n", -1));
            ScopeNode root = new ScopeNode(null, 0, -1);
            Deque<ScopeNode> stack = new ArrayDeque<>();
            stack.push(root);
            ScopeNode[] scopes = new ScopeNode[lines.size()];
            Map<Integer, ScopeNode> bodyScopes = new HashMap<>();
            int previousSignificantLine = -1;

            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                String line = lines.get(lineIndex);
                int indent = indentation(line);
                boolean significant = !line.isBlank() && !line.stripLeading().startsWith("//");

                while (stack.size() > 1 && indent < stack.peek().indent) {
                    stack.pop();
                }
                if ((significant || lineIndex == cursor.line()) && indent > stack.peek().indent) {
                    ScopeNode child = new ScopeNode(stack.peek(), indent, previousSignificantLine);
                    stack.push(child);
                    bodyScopes.put(previousSignificantLine, child);
                }
                scopes[lineIndex] = stack.peek();
                if (significant) {
                    previousSignificantLine = lineIndex;
                }
            }

            int cursorLine = Math.min(cursor.line(), scopes.length - 1);
            ScopeNode cursorScope = scopes[cursorLine] == null ? root : scopes[cursorLine];

            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                List<Token> tokens = tokensForLine(lines.get(lineIndex));
                if (tokens.isEmpty()) {
                    continue;
                }
                ScopeNode scope = scopes[lineIndex] == null ? root : scopes[lineIndex];
                boolean completedBeforeCursor = lineIndex < cursor.line();
                collectDeclaration(tokens, scope, bodyScopes.get(lineIndex), completedBeforeCursor);
            }
            return new DocumentModel(root, cursorScope);
        }

        private static void collectDeclaration(
                List<Token> tokens, ScopeNode scope, ScopeNode bodyScope,
                boolean completedBeforeCursor) {
            TokenType first = tokens.get(0).getType();
            if (first == TokenType.DEFINE && tokens.size() > 1
                    && tokens.get(1).getType() == TokenType.IDENTIFIER) {
                Token name = tokens.get(1);
                scope.declare(new Candidate(name.getLexeme(), CompletionItemKind.Function,
                    "function"), true);
                addParameters(tokens, bodyScope, 2);
            } else if (first == TokenType.LET && completedBeforeCursor && tokens.size() > 1
                    && tokens.get(1).getType() == TokenType.IDENTIFIER) {
                Token name = tokens.get(1);
                scope.declare(new Candidate(name.getLexeme(), CompletionItemKind.Variable,
                    "variable"), false);
            } else if (first == TokenType.IMPORT && completedBeforeCursor && tokens.size() > 1
                    && tokens.get(1).getType() == TokenType.IDENTIFIER) {
                Token name = tokens.get(1);
                scope.declare(new Candidate(name.getLexeme(), CompletionItemKind.Module,
                    ModuleRegistry.hasModule(name.getLexeme()) ? "native module" : "TLang module"), false);
            } else if (first == TokenType.REPEAT) {
                int asIndex = indexOf(tokens, TokenType.AS, 1);
                if (asIndex >= 0 && asIndex + 1 < tokens.size()
                        && tokens.get(asIndex + 1).getType() == TokenType.IDENTIFIER
                        && bodyScope != null) {
                    Token name = tokens.get(asIndex + 1);
                    bodyScope.declare(new Candidate(name.getLexeme(), CompletionItemKind.Variable,
                        "loop variable"), false);
                }
            } else {
                int functionIndex = indexOf(tokens, TokenType.FUNCTION, 0);
                if (functionIndex >= 0) {
                    addParameters(tokens, bodyScope, functionIndex + 1);
                }
            }
        }

        private static void addParameters(List<Token> tokens, ScopeNode bodyScope, int start) {
            if (bodyScope == null) {
                return;
            }
            int taking = indexOf(tokens, TokenType.TAKING, start);
            if (taking < 0) {
                return;
            }
            boolean expectingParameter = true;
            for (int i = taking + 1; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (expectingParameter && token.getType() == TokenType.IDENTIFIER) {
                    bodyScope.declare(new Candidate(token.getLexeme(), CompletionItemKind.Variable,
                        "function parameter"), false);
                    expectingParameter = false;
                } else if (token.getType() == TokenType.AND) {
                    expectingParameter = true;
                }
            }
        }

        private static int indexOf(List<Token> tokens, TokenType type, int start) {
            for (int i = start; i < tokens.size(); i++) {
                if (tokens.get(i).getType() == type) {
                    return i;
                }
            }
            return -1;
        }

        private static List<Token> tokensForLine(String line) {
            try {
                List<Token> result = new ArrayList<>();
                for (Token token : new Lexer(line + "\n").tokenize()) {
                    if (token.getType() != TokenType.INDENT
                            && token.getType() != TokenType.DEDENT
                            && token.getType() != TokenType.NEWLINE
                            && token.getType() != TokenType.EOF) {
                        result.add(token);
                    }
                }
                return result;
            } catch (RuntimeException ignored) {
                return List.of();
            }
        }

        private static int indentation(String line) {
            int width = 0;
            int index = 0;
            while (index < line.length()) {
                char current = line.charAt(index);
                if (current == ' ') {
                    width++;
                } else if (current == '\t') {
                    width += 4;
                } else {
                    break;
                }
                index++;
            }
            return width;
        }

        List<ScopeNode> ancestorsOuterFirst() {
            Deque<ScopeNode> scopes = new ArrayDeque<>();
            for (ScopeNode scope = cursorScope; scope != null; scope = scope.parent) {
                scopes.push(scope);
            }
            return new ArrayList<>(scopes);
        }

        Candidate visibleDeclaration(String name) {
            for (ScopeNode scope = cursorScope; scope != null; scope = scope.parent) {
                Candidate declaration = scope.declarations.get(name);
                if (declaration != null) {
                    return declaration;
                }
            }
            return null;
        }
    }

    private static final class ScopeNode {
        private final ScopeNode parent;
        private final int indent;
        @SuppressWarnings("unused")
        private final int ownerLine;
        private final Map<String, Candidate> declarations = new LinkedHashMap<>();

        private ScopeNode(ScopeNode parent, int indent, int ownerLine) {
            this.parent = parent;
            this.indent = indent;
            this.ownerLine = ownerLine;
        }

        private void declare(Candidate candidate, boolean hoistedFunction) {
            if (hoistedFunction) {
                declarations.putIfAbsent(candidate.name(), candidate);
            } else {
                declarations.put(candidate.name(), candidate);
            }
        }
    }
}
