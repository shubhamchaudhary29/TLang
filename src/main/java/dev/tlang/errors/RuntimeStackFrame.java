package dev.tlang.errors;

/** One immutable TLang call frame, ordered from the failure outward. */
public record RuntimeStackFrame(String name, SourceLocation location, StackFrameType type) {
    public RuntimeStackFrame {
        name = name == null || name.isBlank() ? "<anonymous>" : name;
        location = location == null ? SourceLocation.unknown() : location;
        type = type == null ? StackFrameType.USER_FUNCTION : type;
    }

    public static RuntimeStackFrame userFunction(String name, SourceLocation location) {
        StackFrameType type = "<anonymous>".equals(name)
            ? StackFrameType.ANONYMOUS_FUNCTION
            : StackFrameType.USER_FUNCTION;
        return new RuntimeStackFrame(name, location, type);
    }

    public static RuntimeStackFrame nativeFunction(String name, SourceLocation location) {
        return new RuntimeStackFrame(name, location, StackFrameType.NATIVE_FUNCTION);
    }

    public static RuntimeStackFrame module(String name, SourceLocation location) {
        return new RuntimeStackFrame("<module " + name + ">", location, StackFrameType.MODULE);
    }

    public static RuntimeStackFrame httpHandler(String method, String path) {
        return new RuntimeStackFrame(method + " " + path, SourceLocation.unknown(), StackFrameType.HTTP_HANDLER);
    }

    public static RuntimeStackFrame taskSpawn(SourceLocation location) {
        return new RuntimeStackFrame("<spawn>", location, StackFrameType.TASK_SPAWN);
    }

    public static RuntimeStackFrame taskAwait(SourceLocation location) {
        return new RuntimeStackFrame("<await>", location, StackFrameType.TASK_AWAIT);
    }
}
