package dev.tlang.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.runtime.http.HttpOps;
import dev.tlang.runtime.http.ServerOps;

public final class HttpModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public HttpModule() {
        exports.put("get", new NativeFunction("get", 1, 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object urlVal = args.get(0);
                if (Type.of(urlVal) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'get' must be a string URL.");
                }
                Map<String, String> headers = extractHeaders(args, 1, "get", token);
                return HttpOps.get((String) urlVal, headers, token);
            }
        });

        exports.put("post", new NativeFunction("post", 2, 3) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object urlVal = args.get(0);
                Object bodyVal = args.get(1);
                if (Type.of(urlVal) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'post' must be a string URL.");
                }
                if (Type.of(bodyVal) != Type.STRING) {
                    throw new RuntimeError(token, "Second argument to 'post' must be a string body.");
                }
                Map<String, String> headers = extractHeaders(args, 2, "post", token);
                return HttpOps.post((String) urlVal, (String) bodyVal, headers, token);
            }
        });

        exports.put("put", new NativeFunction("put", 2, 3) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object urlVal = args.get(0);
                Object bodyVal = args.get(1);
                if (Type.of(urlVal) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'put' must be a string URL.");
                }
                if (Type.of(bodyVal) != Type.STRING) {
                    throw new RuntimeError(token, "Second argument to 'put' must be a string body.");
                }
                Map<String, String> headers = extractHeaders(args, 2, "put", token);
                return HttpOps.put((String) urlVal, (String) bodyVal, headers, token);
            }
        });

        exports.put("delete", new NativeFunction("delete", 1, 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object urlVal = args.get(0);
                if (Type.of(urlVal) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'delete' must be a string URL.");
                }
                Map<String, String> headers = extractHeaders(args, 1, "delete", token);
                return HttpOps.delete((String) urlVal, headers, token);
            }
        });

        exports.put("server", new NativeFunction("server", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object portVal = args.get(0);
                if (Type.of(portVal) != Type.NUMBER) {
                    throw new RuntimeError(token, "Port must be an integer.");
                }
                int port = (Integer) portVal;
                if (port < 1 || port > 65535) {
                    throw new RuntimeError(token, "Port must be in the range 1-65535 (got " + port + ").");
                }

                final ServerOps serverOps = new ServerOps(port);
                final Map<String, Object> serverMap = new LinkedHashMap<>();

                serverMap.put("get", new NativeFunction("get", 3) {
                    @Override
                    public Object call(List<Object> subArgs, Token subToken) {
                        Object pathVal = subArgs.get(1);
                        Object handlerVal = subArgs.get(2);
                        if (Type.of(pathVal) != Type.STRING) {
                            throw new RuntimeError(subToken, "Route path must be a string.");
                        }
                        serverOps.addRoute("GET", (String) pathVal, handlerVal, subToken);
                        return serverMap;
                    }
                }.setExpectsReceiver(true));

                serverMap.put("post", new NativeFunction("post", 3) {
                    @Override
                    public Object call(List<Object> subArgs, Token subToken) {
                        Object pathVal = subArgs.get(1);
                        Object handlerVal = subArgs.get(2);
                        if (Type.of(pathVal) != Type.STRING) {
                            throw new RuntimeError(subToken, "Route path must be a string.");
                        }
                        serverOps.addRoute("POST", (String) pathVal, handlerVal, subToken);
                        return serverMap;
                    }
                }.setExpectsReceiver(true));

                serverMap.put("put", new NativeFunction("put", 3) {
                    @Override
                    public Object call(List<Object> subArgs, Token subToken) {
                        Object pathVal = subArgs.get(1);
                        Object handlerVal = subArgs.get(2);
                        if (Type.of(pathVal) != Type.STRING) {
                            throw new RuntimeError(subToken, "Route path must be a string.");
                        }
                        serverOps.addRoute("PUT", (String) pathVal, handlerVal, subToken);
                        return serverMap;
                    }
                }.setExpectsReceiver(true));

                serverMap.put("delete", new NativeFunction("delete", 3) {
                    @Override
                    public Object call(List<Object> subArgs, Token subToken) {
                        Object pathVal = subArgs.get(1);
                        Object handlerVal = subArgs.get(2);
                        if (Type.of(pathVal) != Type.STRING) {
                            throw new RuntimeError(subToken, "Route path must be a string.");
                        }
                        serverOps.addRoute("DELETE", (String) pathVal, handlerVal, subToken);
                        return serverMap;
                    }
                }.setExpectsReceiver(true));

                serverMap.put("use", new NativeFunction("use", 2) {
                    @Override
                    public Object call(List<Object> subArgs, Token subToken) {
                        Object middlewareFn = subArgs.get(1);
                        serverOps.addMiddleware(middlewareFn);
                        return serverMap;
                    }
                }.setExpectsReceiver(true));

                serverMap.put("start", new NativeFunction("start", 1) {
                    @Override
                    public Object call(Interpreter interp, List<Object> subArgs, Token subToken) {
                        serverOps.start(interp, subToken);
                        return null;
                    }
                    @Override
                    public Object call(List<Object> subArgs, Token subToken) {
                        throw new RuntimeError(subToken, "Internal error: start called without interpreter.");
                    }
                }.setExpectsReceiver(true));

                serverMap.put("stop", new NativeFunction("stop", 1) {
                    @Override
                    public Object call(List<Object> subArgs, Token subToken) {
                        serverOps.stop();
                        return null;
                    }
                }.setExpectsReceiver(true));

                return serverMap;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractHeaders(List<Object> args, int index, String fnName, Token token) {
        if (args.size() <= index) {
            return null;
        }
        Object headersArg = args.get(index);
        if (Type.of(headersArg) != Type.MAP) {
            throw new RuntimeError(token,
                    "Headers argument to '" + fnName + "' must be a map (got " + Type.of(headersArg).displayName() + ").");
        }
        Map<String, Object> raw = (Map<String, Object>) headersArg;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (Type.of(entry.getValue()) != Type.STRING) {
                throw new RuntimeError(token,
                        "Header values must be strings (key '" + entry.getKey() + "' has type " + Type.of(entry.getValue()).displayName() + ").");
            }
            result.put(entry.getKey(), (String) entry.getValue());
        }
        return result;
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
