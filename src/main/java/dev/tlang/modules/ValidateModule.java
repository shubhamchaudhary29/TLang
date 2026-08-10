package dev.tlang.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;

public final class ValidateModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public ValidateModule() {
        exports.put("check", new NativeFunction("check", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object dataObj = args.get(0);
                Object schemaObj = args.get(1);

                if (Type.of(dataObj) != Type.MAP) {
                    throw new RuntimeError(token, "First argument to 'check' must be a map.");
                }
                if (Type.of(schemaObj) != Type.MAP) {
                    throw new RuntimeError(token, "Second argument to 'check' must be a map.");
                }

                Map<?, ?> data = (Map<?, ?>) dataObj;
                Map<?, ?> schema = (Map<?, ?>) schemaObj;

                Map<String, String> errorsMap = new LinkedHashMap<>();

                for (Map.Entry<?, ?> entry : schema.entrySet()) {
                    Object fieldNameObj = entry.getKey();
                    if (!(fieldNameObj instanceof String)) {
                        throw new RuntimeError(token, "Schema fields must be strings.");
                    }
                    String fieldName = (String) fieldNameObj;
                    Object rulesObj = entry.getValue();
                    if (Type.of(rulesObj) != Type.MAP) {
                        throw new RuntimeError(token, "Schema rules for field '" + fieldName + "' must be a map.");
                    }
                    Map<?, ?> rules = (Map<?, ?>) rulesObj;

                    // Validate rule keys
                    for (Object ruleKeyObj : rules.keySet()) {
                        if (!(ruleKeyObj instanceof String)) {
                            throw new RuntimeError(token, "Rule keys in schema must be strings.");
                        }
                        String ruleKey = (String) ruleKeyObj;
                        if (!ruleKey.equals("required") && !ruleKey.equals("type") && !ruleKey.equals("min") &&
                            !ruleKey.equals("max") && !ruleKey.equals("pattern") && !ruleKey.equals("in")) {
                            throw new RuntimeError(token, "Unknown validation rule '" + ruleKey + "' for field '" + fieldName + "'.");
                        }
                    }

                    // Check type rule type validation
                    Object typeRule = rules.get("type");
                    if (typeRule != null) {
                        if (!(typeRule instanceof String)) {
                            throw new RuntimeError(token, "Rule 'type' for field '" + fieldName + "' must be a string.");
                        }
                        String t = (String) typeRule;
                        if (!t.equals("string") && !t.equals("number") && !t.equals("integer") &&
                            !t.equals("boolean") && !t.equals("list") && !t.equals("map")) {
                            throw new RuntimeError(token, "Invalid validation type '" + t + "' in schema.");
                        }
                    }

                    // Check required rule type validation
                    Object requiredRule = rules.get("required");
                    if (requiredRule != null && !(requiredRule instanceof Boolean)) {
                        throw new RuntimeError(token, "Rule 'required' for field '" + fieldName + "' must be a boolean.");
                    }

                    // Check min rule type validation
                    Object minRule = rules.get("min");
                    if (minRule != null && !(minRule instanceof Integer)) {
                        throw new RuntimeError(token, "Rule 'min' for field '" + fieldName + "' must be an integer.");
                    }

                    // Check max rule type validation
                    Object maxRule = rules.get("max");
                    if (maxRule != null && !(maxRule instanceof Integer)) {
                        throw new RuntimeError(token, "Rule 'max' for field '" + fieldName + "' must be an integer.");
                    }

                    // Check pattern rule type validation
                    Object patternRule = rules.get("pattern");
                    if (patternRule != null && !(patternRule instanceof String)) {
                        throw new RuntimeError(token, "Rule 'pattern' for field '" + fieldName + "' must be a string.");
                    }

                    // Check in rule type validation
                    Object inRule = rules.get("in");
                    if (inRule != null && !(inRule instanceof List)) {
                        throw new RuntimeError(token, "Rule 'in' for field '" + fieldName + "' must be a list.");
                    }

                    // Perform actual validations
                    boolean hasKey = data.containsKey(fieldName);
                    Object val = hasKey ? data.get(fieldName) : null;

                    if (val == null) {
                        if (requiredRule != null && (Boolean) requiredRule) {
                            errorsMap.put(fieldName, fieldName + " is required");
                        }
                    } else {
                        // Type check
                        if (typeRule != null) {
                            String t = (String) typeRule;
                            Type actualType = Type.of(val);
                            boolean matches = false;
                            if (t.equals("string") && actualType == Type.STRING) matches = true;
                            else if ((t.equals("number") || t.equals("integer")) && actualType == Type.NUMBER) matches = true;
                            else if (t.equals("boolean") && actualType == Type.BOOLEAN) matches = true;
                            else if (t.equals("list") && actualType == Type.LIST) matches = true;
                            else if (t.equals("map") && actualType == Type.MAP) matches = true;

                            if (!matches) {
                                errorsMap.put(fieldName, fieldName + " must be a " + t);
                                continue; // Skip other validations for this field if type is wrong
                            }
                        }

                        // Min check
                        if (minRule != null) {
                            int minVal = (Integer) minRule;
                            Type actualType = Type.of(val);
                            if (actualType == Type.NUMBER) {
                                if (((Integer) val) < minVal) {
                                    errorsMap.put(fieldName, fieldName + " must be at least " + minVal);
                                }
                            } else if (actualType == Type.STRING) {
                                if (((String) val).length() < minVal) {
                                    errorsMap.put(fieldName, fieldName + " length must be at least " + minVal);
                                }
                            } else if (actualType == Type.LIST) {
                                if (((List<?>) val).size() < minVal) {
                                    errorsMap.put(fieldName, fieldName + " size must be at least " + minVal);
                                }
                            }
                        }

                        // Max check
                        if (maxRule != null) {
                            int maxVal = (Integer) maxRule;
                            Type actualType = Type.of(val);
                            if (actualType == Type.NUMBER) {
                                if (((Integer) val) > maxVal) {
                                    errorsMap.put(fieldName, fieldName + " must be at most " + maxVal);
                                }
                            } else if (actualType == Type.STRING) {
                                if (((String) val).length() > maxVal) {
                                    errorsMap.put(fieldName, fieldName + " length must be at most " + maxVal);
                                }
                            } else if (actualType == Type.LIST) {
                                if (((List<?>) val).size() > maxVal) {
                                    errorsMap.put(fieldName, fieldName + " size must be at most " + maxVal);
                                }
                            }
                        }

                        // Pattern check
                        if (patternRule != null && Type.of(val) == Type.STRING) {
                            String regex = (String) patternRule;
                            if (!Pattern.matches(regex, (String) val)) {
                                errorsMap.put(fieldName, fieldName + " must match pattern " + regex);
                            }
                        }

                        // In check
                        if (inRule != null) {
                            List<?> inList = (List<?>) inRule;
                            if (!inList.contains(val)) {
                                errorsMap.put(fieldName, fieldName + " must be one of: " + inList.toString());
                            }
                        }
                    }
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("valid", errorsMap.isEmpty());
                result.put("errors", errorsMap);
                return result;
            }
        });
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}
