package dev.tlang.errors;

/** The kind of TLang execution boundary represented by a stack frame. */
public enum StackFrameType {
    USER_FUNCTION,
    ANONYMOUS_FUNCTION,
    MODULE,
    NATIVE_FUNCTION,
    HTTP_HANDLER
}
