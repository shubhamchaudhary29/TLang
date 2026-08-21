package dev.tlang.interpreter;

import dev.tlang.ast.Stmt;
import dev.tlang.errors.SemanticError;
import dev.tlang.lexer.Lexer;
import dev.tlang.parser.Parser;
import dev.tlang.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InterpreterHoistingTest {
    @Test
    void callsTopLevelFunctionBeforeDeclaration() {
        assertEquals(42, execute("""
            let result be greet()

            define greet
              return 42
            """));
    }

    @Test
    void preservesRecursion() {
        assertEquals(720, execute("""
            let result be factorial(6)

            define factorial taking n
              if n <= 1
                return 1
              return n * factorial(n - 1)
            """));
    }

    @Test
    void supportsMutualRecursion() {
        assertEquals(true, execute("""
            let result be even(10) and not even(9)

            define even taking n
              if n == 0
                return true
              otherwise
                return odd(n - 1)

            define odd taking n
              if n == 0
                return false
              otherwise
                return even(n - 1)
            """));
    }

    @Test
    void callsNestedFunctionBeforeDeclaration() {
        assertEquals(42, execute("""
            let result be outer(41)

            define outer taking value
              return inner()

              define inner
                return value + 1
            """));
    }

    @Test
    void hoistsInsideBlockWithoutLeaking() {
        assertEquals(7, execute("""
            let result be 0
            if true
              set result to inside()

              define inside
                return 7
            """));

        List<SemanticError> errors = resolve("""
            if true
              define inside
                return 7
            let result be inside()
            """);
        assertTrue(errors.stream().anyMatch(error ->
            error.getMessage().contains("Undefined variable 'inside'")));
    }

    @Test
    void duplicateFunctionsRemainSemanticErrors() {
        List<SemanticError> errors = resolve("""
            define duplicate
              return 1
            define duplicate
              return 2
            """);
        assertTrue(errors.stream().anyMatch(error ->
            error.getMessage().contains("already declared")));
    }

    @Test
    void variableFunctionCollisionRemainsSemanticError() {
        List<SemanticError> errors = resolve("""
            let collision be 1
            define collision
              return 2
            """);
        assertTrue(errors.stream().anyMatch(error ->
            error.getMessage().contains("already declared")));
    }

    @Test
    void hoistedFunctionCapturesItsLexicalEnvironment() {
        assertEquals(83, execute("""
            let counter be makeCounter(40)
            let result be counter() + counter()

            define makeCounter taking start
              return increment

              define increment
                set start to start + 1
                return start
            """));
    }

    private static Object execute(String source) {
        List<Stmt> program = parse(source);
        List<SemanticError> errors = new Resolver().resolve(program);
        assertTrue(errors.isEmpty(), () -> "Unexpected semantic errors: " + errors);
        Interpreter interpreter = new Interpreter();
        interpreter.interpret(program);
        return interpreter.getGlobalEnvironment().getValues().get("result");
    }

    private static List<SemanticError> resolve(String source) {
        return new Resolver().resolve(parse(source));
    }

    private static List<Stmt> parse(String source) {
        return new Parser(new Lexer(source).tokenize()).parse();
    }
}
