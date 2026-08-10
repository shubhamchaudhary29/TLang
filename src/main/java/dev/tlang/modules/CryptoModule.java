package dev.tlang.modules;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;

public final class CryptoModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();
    private static final int ITERATIONS = 100000;

    public CryptoModule() {
        exports.put("hashPassword", new NativeFunction("hashPassword", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object passwordObj = args.get(0);
                if (Type.of(passwordObj) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'hashPassword' must be a string.");
                }
                String password = (String) passwordObj;
                try {
                    SecureRandom random = new SecureRandom();
                    byte[] salt = new byte[16];
                    random.nextBytes(salt);
                    byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, 256);
                    return "pbkdf2$" + ITERATIONS + "$" + bytesToHex(salt) + "$" + bytesToHex(hash);
                } catch (Exception e) {
                    throw new RuntimeError(token, "Password hashing failed: " + e.getMessage());
                }
            }
        });

        exports.put("verifyPassword", new NativeFunction("verifyPassword", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object passwordObj = args.get(0);
                Object hashObj = args.get(1);
                if (Type.of(passwordObj) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'verifyPassword' must be a string.");
                }
                if (Type.of(hashObj) != Type.STRING) {
                    throw new RuntimeError(token, "Second argument to 'verifyPassword' must be a string.");
                }
                String password = (String) passwordObj;
                String storedHash = (String) hashObj;
                try {
                    String[] parts = storedHash.split("\\$");
                    if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
                        throw new RuntimeError(token, "Invalid password hash format.");
                    }
                    int iterations;
                    try {
                        iterations = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        throw new RuntimeError(token, "Invalid password hash format.");
                    }
                    byte[] salt = hexToBytes(parts[2]);
                    byte[] hash = hexToBytes(parts[3]);

                    byte[] testHash = pbkdf2(password.toCharArray(), salt, iterations, hash.length * 8);
                    return MessageDigest.isEqual(hash, testHash);
                } catch (RuntimeError re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeError(token, "Invalid password hash format.");
                }
            }
        });

        exports.put("compareConstantTime", new NativeFunction("compareConstantTime", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object aObj = args.get(0);
                Object bObj = args.get(1);
                if (Type.of(aObj) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'compareConstantTime' must be a string.");
                }
                if (Type.of(bObj) != Type.STRING) {
                    throw new RuntimeError(token, "Second argument to 'compareConstantTime' must be a string.");
                }
                byte[] aBytes = ((String) aObj).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] bBytes = ((String) bObj).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return MessageDigest.isEqual(aBytes, bBytes);
            }
        });

        exports.put("hmacSha256", new NativeFunction("hmacSha256", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object dataObj = args.get(0);
                Object secretObj = args.get(1);
                if (Type.of(dataObj) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'hmacSha256' must be a string.");
                }
                if (Type.of(secretObj) != Type.STRING) {
                    throw new RuntimeError(token, "Second argument to 'hmacSha256' must be a string.");
                }
                try {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    SecretKeySpec secretKey = new SecretKeySpec(
                        ((String) secretObj).getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"
                    );
                    mac.init(secretKey);
                    byte[] hash = mac.doFinal(((String) dataObj).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return bytesToHex(hash);
                } catch (Exception e) {
                    throw new RuntimeError(token, "HMAC-SHA256 signing failed: " + e.getMessage());
                }
            }
        });

        exports.put("sha256", new NativeFunction("sha256", 1) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object dataObj = args.get(0);
                if (Type.of(dataObj) != Type.STRING) {
                    throw new RuntimeError(token, "Argument to 'sha256' must be a string.");
                }
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] hash = digest.digest(((String) dataObj).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return bytesToHex(hash);
                } catch (Exception e) {
                    throw new RuntimeError(token, "SHA-256 hashing failed: " + e.getMessage());
                }
            }
        });
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
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
