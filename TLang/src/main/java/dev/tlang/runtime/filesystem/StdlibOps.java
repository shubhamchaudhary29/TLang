package dev.tlang.runtime.filesystem;

import dev.tlang.errors.RuntimeError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;

/**
 * Shared logic for global functions and namespaced standard library modules.
 */
public final class StdlibOps {
    private static final Random RANDOM = new Random();

    // ── Filesystem Operations ───────────────────────────────────

    public static String readFile(String pathStr, Token token) {
        try {
            Path path = Paths.get(pathStr);
            return new String(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new RuntimeError(token, "Error reading file '" + pathStr + "': " + e.getMessage());
        }
    }

    public static void writeFile(String pathStr, String content, Token token) {
        try {
            Path path = Paths.get(pathStr);
            Files.write(path, content.getBytes());
        } catch (IOException e) {
            throw new RuntimeError(token, "Error writing file '" + pathStr + "': " + e.getMessage());
        }
    }

    public static void appendFile(String pathStr, String content, Token token) {
        try {
            Path path = Paths.get(pathStr);
            Files.write(path, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeError(token, "Error appending to file '" + pathStr + "': " + e.getMessage());
        }
    }

    public static boolean fileExists(String pathStr) {
        return Files.exists(Paths.get(pathStr));
    }

    public static boolean deleteFile(String pathStr) {
        try {
            return Files.deleteIfExists(Paths.get(pathStr));
        } catch (IOException e) {
            return false;
        }
    }

    public static List<String> listDirectory(String dirPathStr, Token token) {
        Path path = Paths.get(dirPathStr);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new RuntimeError(token, "Path '" + dirPathStr + "' is not a directory or does not exist.");
        }
        try (Stream<Path> stream = Files.list(path)) {
            return stream.map(p -> p.getFileName().toString()).collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeError(token, "Error listing directory '" + dirPathStr + "': " + e.getMessage());
        }
    }

    public static boolean makeDirectory(String dirPathStr, boolean recursive, Token token) {
        Path path = Paths.get(dirPathStr);
        if (Files.exists(path)) {
            if (Files.isDirectory(path)) {
                return false; // already exists as a directory — no-op
            }
            throw new RuntimeError(token,
                    "Path '" + dirPathStr + "' already exists and is not a directory.");
        }
        try {
            if (recursive) {
                Files.createDirectories(path);
            } else {
                Files.createDirectory(path);
            }
            return true;
        } catch (IOException e) {
            throw new RuntimeError(token, "Error creating directory '" + dirPathStr + "': " + e.getMessage());
        }
    }

    public static boolean removeDirectory(String dirPathStr, boolean recursive, Token token) {
        Path path = Paths.get(dirPathStr);
        if (!Files.exists(path)) {
            return false; // doesn't exist — no-op
        }
        if (!Files.isDirectory(path)) {
            throw new RuntimeError(token,
                    "Path '" + dirPathStr + "' is not a directory.");
        }
        try {
            if (recursive) {
                // Walk the tree in reverse order (deepest first) and delete everything
                java.util.Deque<Path> toDelete = new java.util.ArrayDeque<>();
                Files.walk(path).forEach(toDelete::push);
                for (Path p : toDelete) {
                    Files.deleteIfExists(p);
                }
            } else {
                // Non-recursive: only works if the directory is empty
                try (Stream<Path> entries = Files.list(path)) {
                    if (entries.findFirst().isPresent()) {
                        throw new RuntimeError(token,
                                "Directory '" + dirPathStr + "' is not empty. Use recursive mode to remove non-empty directories.");
                    }
                }
                Files.delete(path);
            }
            return true;
        } catch (RuntimeError e) {
            throw e; // re-throw our own errors
        } catch (IOException e) {
            throw new RuntimeError(token, "Error removing directory '" + dirPathStr + "': " + e.getMessage());
        }
    }


    // ── Time Operations ─────────────────────────────────────────

    public static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    // ── Random Operations ───────────────────────────────────────

    public static int randomBetween(int min, int max, Token token) {
        if (min > max) {
            throw new RuntimeError(token, "Min (" + min + ") must be less than or equal to Max (" + max + ").");
        }
        return min + RANDOM.nextInt(max - min + 1);
    }

    public static boolean randomBoolean() {
        return RANDOM.nextBoolean();
    }

    public static Object randomChoice(List<Object> list, Token token) {
        if (list == null || list.isEmpty()) {
            throw new RuntimeError(token, "Cannot choose from an empty list.");
        }
        return list.get(RANDOM.nextInt(list.size()));
    }

    public static List<Object> randomShuffle(List<Object> list) {
        List<Object> copy = new ArrayList<>(list);
        Collections.shuffle(copy, RANDOM);
        return copy;
    }

    // ── Conversion & Utility Operations ─────────────────────────

    public static int toInteger(Object val, Token token) {
        if (val instanceof Integer) {
            return (Integer) val;
        }
        if (val instanceof Boolean) {
            return ((Boolean) val) ? 1 : 0;
        }
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException e) {
                throw new RuntimeError(token, "Cannot convert string '" + val + "' to integer.");
            }
        }
        throw new RuntimeError(token, "Cannot convert value of type '" + Type.of(val).displayName() + "' to integer.");
    }

    public static String typeOf(Object val) {
        return Type.of(val).displayName();
    }
}
