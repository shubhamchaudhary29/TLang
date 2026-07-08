package dev.tlang.modules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.runtime.filesystem.StdlibOps;

public final class FilesystemModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public FilesystemModule() {
        exports.put("read", new NativeFunction("read", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'read' must be a string.");
                }
                return StdlibOps.readFile((String) path, token);
            }
        });

        exports.put("write", new NativeFunction("write", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                Object content = args.get(1);
                if (Type.of(path) != Type.STRING || Type.of(content) != Type.STRING) {
                    throw new RuntimeError(token, "Arguments to 'write' must be strings.");
                }
                StdlibOps.writeFile((String) path, (String) content, token);
                return null;
            }
        });

        exports.put("append", new NativeFunction("append", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                Object content = args.get(1);
                if (Type.of(path) != Type.STRING || Type.of(content) != Type.STRING) {
                    throw new RuntimeError(token, "Arguments to 'append' must be strings.");
                }
                StdlibOps.appendFile((String) path, (String) content, token);
                return null;
            }
        });

        exports.put("exists", new NativeFunction("exists", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'exists' must be a string.");
                }
                return StdlibOps.fileExists((String) path);
            }
        });

        exports.put("delete", new NativeFunction("delete", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'delete' must be a string.");
                }
                return StdlibOps.deleteFile((String) path);
            }
        });

        exports.put("list", new NativeFunction("list", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'list' must be a string.");
                }
                return new ArrayList<Object>(StdlibOps.listDirectory((String) path, token));
            }
        });

        exports.put("mkdir", new NativeFunction("mkdir", 1, 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'mkdir' must be a string.");
                }
                boolean recursive = false;
                if (args.size() == 2) {
                    Object recArg = args.get(1);
                    if (Type.of(recArg) != Type.BOOLEAN) {
                        throw new RuntimeError(token, "Second argument to 'mkdir' must be a boolean (got " + Type.of(recArg).displayName() + ").");
                    }
                    recursive = (Boolean) recArg;
                }
                return StdlibOps.makeDirectory((String) path, recursive, token);
            }
        });

        exports.put("rmdir", new NativeFunction("rmdir", 1, 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object path = args.get(0);
                if (Type.of(path) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'rmdir' must be a string.");
                }
                boolean recursive = false;
                if (args.size() == 2) {
                    Object recArg = args.get(1);
                    if (Type.of(recArg) != Type.BOOLEAN) {
                        throw new RuntimeError(token, "Second argument to 'rmdir' must be a boolean (got " + Type.of(recArg).displayName() + ").");
                    }
                    recursive = (Boolean) recArg;
                }
                return StdlibOps.removeDirectory((String) path, recursive, token);
            }
        });
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
