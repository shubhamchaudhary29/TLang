package dev.tlang.runtime.task;

import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeErrorKind;
import dev.tlang.errors.RuntimeStackFrame;
import dev.tlang.errors.SourceLocation;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.lexer.Token;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Root-owned scheduler and wait graph for TLang background tasks. */
public final class TaskRuntime {
    public static final int DEFAULT_MAX_OUTSTANDING = 1024;

    private final int maxOutstanding;
    private final Object lifecycleLock = new Object();
    private final Map<TaskValue, TaskValue> waitDependencies = new IdentityHashMap<>();
    private final ThreadLocal<TaskValue> currentTask = new ThreadLocal<>();
    private int outstanding;
    private long nextTaskId;

    public TaskRuntime() {
        this(configuredLimit());
    }

    public TaskRuntime(int maxOutstanding) {
        if (maxOutstanding < 1) {
            throw new IllegalArgumentException("Task limit must be positive.");
        }
        this.maxOutstanding = maxOutstanding;
    }

    public TaskValue spawn(
            Interpreter executionCursor,
            Object callee,
            List<Object> arguments,
            Token callToken,
            Token spawnToken) {
        TaskValue task;
        synchronized (lifecycleLock) {
            if (outstanding >= maxOutstanding) {
                throw new RuntimeError(RuntimeErrorKind.TASK_ERROR, spawnToken,
                    "Outstanding task limit of " + maxOutstanding + " exceeded.");
            }
            outstanding++;
            task = new TaskValue(++nextTaskId, this);
        }

        List<Object> capturedArguments = Collections.unmodifiableList(new ArrayList<>(arguments));
        try {
            Thread.ofVirtual()
                .name("tlang-task-" + task.id())
                .start(() -> runTask(task, executionCursor, callee, capturedArguments, callToken, spawnToken));
        } catch (RuntimeException schedulingFailure) {
            releaseCapacity(task);
            throw new RuntimeError(RuntimeErrorKind.TASK_ERROR, spawnToken,
                "Unable to schedule background task.", schedulingFailure);
        }
        return task;
    }

    public Object await(TaskValue task, Token awaitToken) {
        if (task.owner() != this) {
            throw new RuntimeError(RuntimeErrorKind.TASK_ERROR, awaitToken,
                "Task belongs to a different interpreter runtime.");
        }

        TaskValue waiter = currentTask.get();
        if (waiter != null) {
            registerWait(waiter, task, awaitToken);
        }
        try {
            return task.awaitCompletion();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeError(RuntimeErrorKind.TASK_ERROR, awaitToken,
                "Task wait was interrupted.", interrupted)
                .withFrame(RuntimeStackFrame.taskAwait(SourceLocation.from(awaitToken)));
        } catch (RuntimeError failure) {
            throw failure.withFrame(RuntimeStackFrame.taskAwait(SourceLocation.from(awaitToken)));
        } finally {
            if (waiter != null) {
                synchronized (lifecycleLock) {
                    waitDependencies.remove(waiter);
                }
            }
        }
    }

    public int getOutstandingTaskCount() {
        synchronized (lifecycleLock) {
            return outstanding;
        }
    }

    public int getMaxOutstanding() {
        return maxOutstanding;
    }

    private void runTask(
            TaskValue task,
            Interpreter executionCursor,
            Object callee,
            List<Object> arguments,
            Token callToken,
            Token spawnToken) {
        currentTask.set(task);
        task.markRunning();
        try {
            Object result = executionCursor.executeCallDirect(callee, arguments, callToken);
            releaseCapacity(task);
            task.succeed(result);
        } catch (RuntimeError failure) {
            RuntimeError taskFailure = failure.withFrame(
                RuntimeStackFrame.taskSpawn(SourceLocation.from(spawnToken)));
            releaseCapacity(task);
            task.fail(taskFailure);
        } catch (Exception hostFailure) {
            RuntimeError failure = new RuntimeError(
                RuntimeErrorKind.TASK_ERROR,
                spawnToken,
                "Background task failed unexpectedly.",
                hostFailure
            ).withFrame(RuntimeStackFrame.taskSpawn(SourceLocation.from(spawnToken)));
            releaseCapacity(task);
            task.fail(failure);
        } catch (Error fatalFailure) {
            releaseCapacity(task);
            task.failFatally(fatalFailure);
            throw fatalFailure;
        } finally {
            currentTask.remove();
        }
    }

    private void registerWait(TaskValue waiter, TaskValue target, Token awaitToken) {
        synchronized (lifecycleLock) {
            if (waiter == target || reaches(target, waiter)) {
                throw new RuntimeError(RuntimeErrorKind.TASK_ERROR, awaitToken,
                    "Task dependency cycle detected.")
                    .withFrame(RuntimeStackFrame.taskAwait(SourceLocation.from(awaitToken)));
            }
            waitDependencies.put(waiter, target);
        }
    }

    private boolean reaches(TaskValue start, TaskValue target) {
        TaskValue cursor = start;
        Map<TaskValue, Boolean> visited = new IdentityHashMap<>();
        while (cursor != null && visited.put(cursor, Boolean.TRUE) == null) {
            if (cursor == target) {
                return true;
            }
            cursor = waitDependencies.get(cursor);
        }
        return false;
    }

    private void releaseCapacity(TaskValue task) {
        synchronized (lifecycleLock) {
            waitDependencies.remove(task);
            outstanding--;
        }
    }

    private static int configuredLimit() {
        int configured = Integer.getInteger(
            "tlang.tasks.maxOutstanding", DEFAULT_MAX_OUTSTANDING);
        return configured > 0 ? configured : DEFAULT_MAX_OUTSTANDING;
    }
}
