package dev.tlang.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TLangCompletionTest {
    private TLangLanguageServer server;

    @BeforeEach
    void setUp() {
        server = new TLangLanguageServer();
    }

    @Test
    void advertisesCompletionWithDotTrigger() throws Exception {
        var capabilities = server.initialize(new InitializeParams()).get().getCapabilities();
        assertNotNull(capabilities.getCompletionProvider());
        assertEquals(List.of("."), capabilities.getCompletionProvider().getTriggerCharacters());
    }

    @Test
    void emptyFileIncludesKeywordsAndBuiltinsInStableOrder() throws Exception {
        Request request = open("file:///empty.tiny", "|");
        List<String> first = labels(request);
        List<String> second = labels(request);

        assertTrue(first.containsAll(List.of("let", "define", "show", "now", "read_file")));
        assertEquals(first, second);
        assertEquals(first.stream().sorted().toList(), first);
        assertEquals(first.size(), new HashSet<>(first).size());
    }

    @Test
    void filtersPartialIdentifiers() throws Exception {
        List<String> labels = labels(open("file:///partial.tiny", "sh|"));
        assertEquals(List.of("show"), labels);
    }

    @Test
    void seesTopLevelVariablesAndHoistedFunctions() throws Exception {
        Request request = open("file:///top-level.tiny", """
            let answer be 42
            an|
            define after taking value
              return value
            """);
        assertTrue(labels(request).contains("answer"));

        Request functionRequest = open("file:///functions.tiny", """
            af|
            define after taking value
              return value
            """);
        assertTrue(labels(functionRequest).contains("after"));
    }

    @Test
    void respectsParametersNestedScopesAndShadowing() throws Exception {
        Request nested = open("file:///nested.tiny", """
            let shared be 1
            define calculate taking parameter
              let local be parameter
              if true
                let shared be 2
                |
            """);
        List<String> labels = labels(nested);
        assertTrue(labels.containsAll(List.of("parameter", "local", "shared", "calculate")));
        assertEquals(1, labels.stream().filter("shared"::equals).count());
    }

    @Test
    void doesNotLeakBindingsFromChildOrFunctionScopes() throws Exception {
        Request afterChild = open("file:///no-child-leak.tiny", """
            define calculate taking parameter
              if true
                let secret be 1
                show secret
              sec|
            """);
        assertFalse(labels(afterChild).contains("secret"));

        Request afterFunction = open("file:///no-function-leak.tiny", """
            define calculate taking parameter
              let local be parameter
              return local
            par|
            """);
        assertFalse(labels(afterFunction).contains("parameter"));
        assertFalse(labels(afterFunction).contains("local"));
    }

    @Test
    void includesLoopVariablesOnlyInsideLoopBody() throws Exception {
        Request inside = open("file:///repeat-inside.tiny", """
            repeat 3 times as index
              ind|
            """);
        assertTrue(labels(inside).contains("index"));

        Request outside = open("file:///repeat-outside.tiny", """
            repeat 3 times as index
              show index
            ind|
            """);
        assertFalse(labels(outside).contains("index"));
    }

    @Test
    void completesNativeModuleNamesAndMembers() throws Exception {
        List<String> modules = labels(open("file:///imports.tiny", "import js|"));
        assertEquals(List.of("json"), modules);

        List<String> members = labels(open("file:///members.tiny", """
            import json
            json.|
            """));
        assertEquals(List.of("parse", "stringify"), members);
    }

    @Test
    void completesUserModuleTopLevelExports(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("helper.tiny"), """
            let value be 7
            define greet taking name
              return name
            """);
        String uri = directory.resolve("main.tiny").toUri().toString();
        Request request = open(uri, """
            import helper
            helper.|
            """);
        assertEquals(List.of("greet", "value"), labels(request));
    }

    @Test
    void unknownAndMissingModulesReturnNoMembers() throws Exception {
        assertTrue(labels(open("file:///unknown.tiny", "missing.|")).isEmpty());
        assertTrue(labels(open("file:///missing.tiny", """
            import missing
            missing.|
            """)).isEmpty());
    }

    @Test
    void toleratesIncompleteAndMalformedSyntax() throws Exception {
        Request incomplete = open("file:///incomplete.tiny", """
            define work taking argument
              let local be
              loc|
            """);
        assertTrue(labels(incomplete).contains("local"));

        Request malformed = open("file:///malformed.tiny", """
            let before be 1
            show @
            bef|
            """);
        assertTrue(labels(malformed).contains("before"));
    }

    @Test
    void handlesCursorBoundariesAndClosedDocuments() throws Exception {
        Request beginning = open("file:///boundaries.tiny", "|let x be 1");
        assertFalse(labels(beginning).isEmpty());

        CompletionParams beyondEnd = new CompletionParams(
            new TextDocumentIdentifier(beginning.uri()), new Position(100, 100));
        assertFalse(completionLabels(beyondEnd).isEmpty());

        CompletionParams unopened = new CompletionParams(
            new TextDocumentIdentifier("file:///not-open.tiny"), new Position(0, 0));
        assertTrue(completionLabels(unopened).isEmpty());
    }

    @Test
    void updatesCompletionAfterDocumentChangesWithoutDuplicates() throws Exception {
        String uri = "file:///changes.tiny";
        Request first = open(uri, """
            let oldName be 1
            old|
            """);
        assertTrue(labels(first).contains("oldName"));

        String changed = "let newName be 2\nnew";
        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri, 2),
            Collections.singletonList(new TextDocumentContentChangeEvent(changed))));
        List<String> labels = completionLabels(new CompletionParams(
            new TextDocumentIdentifier(uri), new Position(1, 3)));
        assertEquals(List.of("newName"), labels);
    }

    @Test
    void localBindingDoesNotDuplicateBuiltinName() throws Exception {
        List<String> labels = labels(open("file:///duplicates.tiny", """
            let now be 1
            no|
            """));
        assertEquals(List.of("not", "now"), labels);
        assertEquals(labels.size(), new HashSet<>(labels).size());
    }

    @Test
    void returnsNoSuggestionsInsideCommentsOrStrings() throws Exception {
        assertTrue(labels(open("file:///comment.tiny", "// sh|")).isEmpty());
        assertTrue(labels(open("file:///string.tiny", "show \"sh|\"")).isEmpty());
    }

    @Test
    void returnsNoSuggestionsInsideMultilineString() throws Exception {
        assertTrue(labels(open("file:///multiline-string.tiny", """
            let text be "hello
            sh|
            world"
            """)).isEmpty());
    }

    @Test
    void resumesCompletionAfterMultilineStringCloses() throws Exception {
        List<String> labels = labels(open("file:///after-multiline-string.tiny", """
            let text be "hello
            world"
            sh|
            """));
        assertEquals(List.of("show"), labels);
    }

    @Test
    void escapedQuoteDoesNotEndMultilineString() throws Exception {
        assertTrue(labels(open("file:///escaped-multiline-string.tiny", """
            let text be "hello \\\"
            sh|
            world"
            """)).isEmpty());
    }

    @Test
    void slashesInsideMultilineStringAreNotAComment() throws Exception {
        assertTrue(labels(open("file:///slashes-in-string.tiny", """
            let text be "hello
            // still a string
            sh|
            world"
            """)).isEmpty());
    }

    @Test
    void realCommentFollowingCodeHasNoCompletion() throws Exception {
        assertTrue(labels(open("file:///inline-comment.tiny", "let value be 1 // sh|")).isEmpty());
    }

    @Test
    void unterminatedMultilineStringDoesNotCrashOrComplete() throws Exception {
        assertTrue(labels(open("file:///unterminated-string.tiny", """
            let text be "unfinished
            another line
            sh|
            """)).isEmpty());
    }

    @Test
    void completesFinalLetExportWithoutTrailingNewline(@TempDir Path directory) throws Exception {
        assertEquals(List.of("value"), userModuleMembers(directory, "let value be 10"));
    }

    @Test
    void completesFinalFunctionExportWithoutTrailingNewline(@TempDir Path directory) throws Exception {
        assertEquals(List.of("greet"), userModuleMembers(directory, """
            define greet
              return "hello"""));
    }

    @Test
    void completesMultipleExportsWhenLastHasNoTrailingNewline(@TempDir Path directory) throws Exception {
        assertEquals(List.of("first", "last"), userModuleMembers(directory,
            "let first be 1\nlet last be 2"));
    }

    @Test
    void completesModuleWithTrailingNewline(@TempDir Path directory) throws Exception {
        assertEquals(List.of("value"), userModuleMembers(directory, "let value be 10\n"));
    }

    @Test
    void emptyUserModuleHasNoMembers(@TempDir Path directory) throws Exception {
        assertTrue(userModuleMembers(directory, "").isEmpty());
    }

    @Test
    void malformedUserModuleDoesNotCrashCompletion(@TempDir Path directory) throws Exception {
        assertEquals(List.of("valid"), userModuleMembers(directory,
            "let valid be 1\nlet broken be @"));
    }

    private List<String> userModuleMembers(Path directory, String moduleSource) throws Exception {
        Files.writeString(directory.resolve("helper.tiny"), moduleSource);
        String uri = directory.resolve("main.tiny").toUri().toString();
        return labels(open(uri, """
            import helper
            helper.|
            """));
    }

    private Request open(String uri, String markedSource) {
        int marker = markedSource.indexOf('|');
        assertTrue(marker >= 0, "test source must contain a cursor marker");
        String source = markedSource.substring(0, marker) + markedSource.substring(marker + 1);
        String before = markedSource.substring(0, marker);
        String[] lines = before.split("\\r?\\n", -1);
        Position position = new Position(lines.length - 1, lines[lines.length - 1].length());
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(uri, "tiny", 1, source)));
        return new Request(uri, position);
    }

    private List<String> labels(Request request) throws Exception {
        return completionLabels(new CompletionParams(
            new TextDocumentIdentifier(request.uri()), request.position()));
    }

    private List<String> completionLabels(CompletionParams params) throws Exception {
        var result = server.getTextDocumentService().completion(params).get();
        assertTrue(result.isLeft());
        List<String> labels = new ArrayList<>();
        for (CompletionItem item : result.getLeft()) {
            labels.add(item.getLabel());
        }
        return labels;
    }

    private record Request(String uri, Position position) {}
}
