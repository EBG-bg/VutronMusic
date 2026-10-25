package io.github.vutronmusic.personal;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One cancellable library task; playback and lightweight bridge calls use other workers. */
final class LibraryTaskRunner {
    interface Work { void run() throws Exception; }
    private static final ThreadLocal<Task> CURRENT = new ThreadLocal<>();
    private static final class Task {
        volatile String kind;
        volatile boolean cancelled;
        volatile Thread thread;
        volatile int done, total;
        final Consumer<JSObject> changed;
        Task(String kind, Consumer<JSObject> changed) { this.kind = kind; this.changed = changed; }
        JSObject state() { return new JSObject().put("kind", kind).put("running", true).put("cancelled", cancelled).put("done", done).put("total", total); }
    }
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Consumer<JSObject> changed;
    private Task active;
    LibraryTaskRunner(Consumer<JSObject> changed) { this.changed = changed; }
    synchronized void execute(String kind, PluginCall call, Work work) {
        if (active != null) { call.reject("另一个本地资料任务尚未结束，请稍后重试"); return; }
        Task task = new Task(kind, changed); active = task; changed.accept(task.state());
        executor.execute(() -> {
            task.thread = Thread.currentThread(); CURRENT.set(task);
            try { checkCancelled(); work.run(); }
            catch (Exception failure) {
                if (isCancelled() || failure instanceof CancellationException) call.resolve(new JSObject().put("cancelled", true));
                else call.reject(failure.getMessage() == null ? "本地资料任务失败" : failure.getMessage());
            } finally {
                CURRENT.remove(); Thread.interrupted();
                synchronized (this) { if (active == task) active = null; }
                changed.accept(new JSObject().put("kind", task.kind).put("running", false).put("cancelled", task.cancelled).put("done", task.done).put("total", task.total));
            }
        });
    }
    synchronized JSObject state() { return active == null ? new JSObject().put("running", false).put("kind", "").put("done", 0).put("total", 0) : active.state(); }
    synchronized void cancel() {
        if (active == null) return;
        active.cancelled = true;
        if (active.thread != null) active.thread.interrupt();
        changed.accept(active.state());
    }
    static boolean isCancelled() { Task task = CURRENT.get(); return Thread.currentThread().isInterrupted() || task != null && task.cancelled; }
    static void checkCancelled() { if (isCancelled()) throw new CancellationException("任务已取消"); }
    static void progress(int done, int total) {
        checkCancelled(); Task task = CURRENT.get();
        if (task != null) { task.done = done; task.total = total; task.changed.accept(task.state()); }
    }
    static void phase(String kind) { checkCancelled(); Task task = CURRENT.get(); if (task != null) { task.kind = kind; task.done = 0; task.total = 0; task.changed.accept(task.state()); } }
    synchronized void close() { cancel(); executor.shutdownNow(); }
}
