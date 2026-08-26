package dev.tlang.runtime.task;

/** Observable lifecycle states used for stable task stringification. */
public enum TaskState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
