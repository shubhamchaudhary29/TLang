package dev.tlang.runtime.task;

import dev.tlang.errors.RuntimeError;

import java.util.concurrent.CountDownLatch;

/** Opaque TLang task value. Host synchronization details are not exposed. */
public final class TaskValue {
    private final long id;
    private final TaskRuntime owner;
    private final CountDownLatch completion = new CountDownLatch(1);
    private volatile TaskState state = TaskState.PENDING;
    private Object result;
    private RuntimeError failure;
    private Error fatalFailure;

    TaskValue(long id, TaskRuntime owner) {
        this.id = id;
        this.owner = owner;
    }

    long id() { return id; }
    TaskRuntime owner() { return owner; }

    public TaskState state() { return state; }

    void markRunning() {
        state = TaskState.RUNNING;
    }

    void succeed(Object value) {
        result = value;
        state = TaskState.SUCCEEDED;
        completion.countDown();
    }

    void fail(RuntimeError error) {
        failure = error;
        state = TaskState.FAILED;
        completion.countDown();
    }

    void failFatally(Error error) {
        fatalFailure = error;
        state = TaskState.FAILED;
        completion.countDown();
    }

    Object awaitCompletion() throws InterruptedException {
        completion.await();
        if (fatalFailure != null) {
            throw fatalFailure;
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }

    @Override
    public String toString() {
        return switch (state) {
            case PENDING -> "<task pending>";
            case RUNNING -> "<task running>";
            case SUCCEEDED -> "<task completed>";
            case FAILED -> "<task failed>";
        };
    }
}
