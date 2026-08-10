package dev.tlang.modules;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.runtime.filesystem.StdlibOps;
import dev.tlang.runtime.json.JsonParser;

public final class JwtModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();
    private NativeFunction hmacSha256;
    private NativeFunction compareConstantTime;

    public JwtModule() {
        exports.put("sign", new NativeFunction("sign", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object payloadObj = args.get(0);
                Object secretObj = args.get(1);
                if (Type.of(payloadObj) != Type.MAP) {
                    throw new RuntimeError(token, "First argument to 'sign' must be a map.");
                }
                if (Type.of(secretObj) != Type.STRING) {
                    throw new RuntimeError(token, "Second argument to 'sign' must be a string.");
                }
                Map<?, ?> payload = (Map<?, ?>) payloadObj;
                String secret = (String) secretObj;

                try {
                    // Header
                    Map<String, Object> header = new LinkedHashMap<>();
                    header.put("alg", "HS256");
                    header.put("typ", "JWT");
                    String headerJson = JsonModule.jsonStringifyExternal(header, token);
                    String headerB64 = base64UrlEncode(headerJson);

                    // Payload
                    Map<String, Object> finalPayload = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : payload.entrySet()) {
                        if (entry.getKey() instanceof String) {
                            finalPayload.put((String) entry.getKey(), entry.getValue());
                        }
                    }
                    if (!finalPayload.containsKey("exp")) {
                        finalPayload.put("exp", StdlibOps.now() + 3600);
                    }
                    String payloadJson = JsonModule.jsonStringifyExternal(finalPayload, token);
                    String payloadB64 = base64UrlEncode(payloadJson);

                    // Signature
                    String signingInput = headerB64 + "." + payloadB64;
                    initCrypto(token);
                    String sigHex = (String) hmacSha256.call(List.of(signingInput, secret), token);
                    byte[] sigBytes = hexToBytes(sigHex);
                    String sigB64 = base64UrlEncode(sigBytes);

                    return signingInput + "." + sigB64;
                } catch (Exception e) {
                    throw new RuntimeError(token, "JWT signing failed: " + e.getMessage());
                }
            }
        });

        exports.put("verify", new NativeFunction("verify", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object tokenObj = args.get(0);
                Object secretObj = args.get(1);
                if (Type.of(tokenObj) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'verify' must be a string.");
                }
                if (Type.of(secretObj) != Type.STRING) {
                    throw new RuntimeError(token, "Second argument to 'verify' must be a string.");
                }
                String tokenStr = (String) tokenObj;
                String secret = (String) secretObj;

                try {
                    String[] parts = tokenStr.split("\\.");
                    if (parts.length != 3) {
                        return createInvalidResult();
                    }
                    String headerB64 = parts[0];
                    String payloadB64 = parts[1];
                    String sigB64 = parts[2];

                    // Verify Signature
                    String signingInput = headerB64 + "." + payloadB64;
                    initCrypto(token);
                    String expectedSigHex = (String) hmacSha256.call(List.of(signingInput, secret), token);

                    byte[] sigBytes = Base64.getUrlDecoder().decode(sigB64);
                    String receivedSigHex = bytesToHex(sigBytes);

                    boolean sigValid = (Boolean) compareConstantTime.call(List.of(expectedSigHex, receivedSigHex), token);
                    if (!sigValid) {
                        return createInvalidResult();
                    }

                    // Decode Payload
                    String payloadJson = base64UrlDecode(payloadB64);
                    JsonParser jp = new JsonParser(payloadJson, token);
                    Object parsedPayloadObj = jp.parse();
                    if (Type.of(parsedPayloadObj) != Type.MAP) {
                        return createInvalidResult();
                    }
                    Map<?, ?> payloadMap = (Map<?, ?>) parsedPayloadObj;

                    // Verify Expiry
                    Object expVal = payloadMap.get("exp");
                    if (expVal instanceof Integer) {
                        int exp = (Integer) expVal;
                        if (StdlibOps.now() > exp) {
                            return createInvalidResult();
                        }
                    }

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("valid", true);
                    result.put("payload", payloadMap);
                    return result;
                } catch (Exception e) {
                    return createInvalidResult();
                }
            }
        });
    }

    private void initCrypto(Token token) {
        if (hmacSha256 == null) {
            Map<String, Object> cryptoExports = ModuleRegistry.getModule("crypto");
            if (cryptoExports == null) {
                throw new RuntimeError(token, "Required module 'crypto' is not registered.");
            }
            hmacSha256 = (NativeFunction) cryptoExports.get("hmacSha256");
            compareConstantTime = (NativeFunction) cryptoExports.get("compareConstantTime");
            if (hmacSha256 == null || compareConstantTime == null) {
                throw new RuntimeError(token, "Required functions in 'crypto' module not found.");
            }
        }
    }

    private Map<String, Object> createInvalidResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", false);
        result.put("payload", new LinkedHashMap<String, Object>());
        return result;
    }

    private static String base64UrlEncode(String src) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(src.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String base64UrlEncode(byte[] src) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(src);
    }

    private static String base64UrlDecode(String src) {
        return new String(Base64.getUrlDecoder().decode(src), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Hex string length must be even.");
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
