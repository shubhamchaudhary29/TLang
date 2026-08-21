package dev.tlang.benchmark;

/** Deterministically generates representative front-end inputs by size. */
public final class BenchmarkSources {
    private BenchmarkSources() {}

    public static String frontend(String size) {
        int declarations = switch (size) {
            case "small" -> 10;
            case "medium" -> 100;
            case "large" -> 500;
            default -> throw new IllegalArgumentException("Unknown front-end benchmark size: " + size);
        };
        StringBuilder source = new StringBuilder();
        source.append("define combine taking left and right\n")
            .append("  return left + right\n")
            .append("let value0 be 0\n");
        for (int index = 1; index <= declarations; index++) {
            source.append("let value").append(index).append(" be combine(value")
                .append(index - 1).append(", ").append(index).append(")\n");
        }
        source.append("let result be value").append(declarations).append("\n");
        return source.toString();
    }
}
