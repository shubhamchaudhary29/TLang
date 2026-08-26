package dev.tlang.errors;

/** Stable, user-facing categories for failures raised while executing TLang. */
public enum RuntimeErrorKind {
    RUNTIME_ERROR("RuntimeError"),
    TYPE_ERROR("TypeError"),
    NAME_ERROR("NameError"),
    IMPORT_ERROR("ImportError"),
    DATABASE_ERROR("DatabaseError"),
    HTTP_ERROR("HttpError"),
    VALIDATION_ERROR("ValidationError"),
    INDEX_ERROR("IndexError"),
    ARITY_ERROR("ArityError");

    private final String displayName;

    RuntimeErrorKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
