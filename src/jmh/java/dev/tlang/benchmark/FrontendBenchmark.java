package dev.tlang.benchmark;

import dev.tlang.ast.Stmt;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.parser.Parser;
import dev.tlang.resolver.Resolver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class FrontendBenchmark {
    @Param({"small", "medium", "large"})
    public String size;

    private String source;
    private List<Token> tokens;
    private List<Stmt> program;

    @Setup
    public void setUp() {
        source = BenchmarkSources.frontend(size);
        tokens = new Lexer(source).tokenize();
        program = new Parser(tokens).parse();
        if (!new Resolver().resolve(program).isEmpty()) {
            throw new IllegalStateException("Generated front-end source did not resolve: " + size);
        }
    }

    @Benchmark
    public List<Token> lex() {
        return new Lexer(source).tokenize();
    }

    @Benchmark
    public List<Stmt> parse() {
        return new Parser(tokens).parse();
    }

    @Benchmark
    public int resolve() {
        return new Resolver().resolve(program).size();
    }
}
