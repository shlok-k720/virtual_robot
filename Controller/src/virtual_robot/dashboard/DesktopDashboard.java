package virtual_robot.dashboard;

import com.acmerobotics.dashboard.DashboardCore;
import com.acmerobotics.dashboard.SocketHandler;
import com.acmerobotics.dashboard.canvas.Canvas;
import com.acmerobotics.dashboard.canvas.CanvasOp;
import com.acmerobotics.dashboard.message.redux.ReceiveTelemetry;
import com.acmerobotics.dashboard.telemetry.TelemetryPacket;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** In-process transport for the published dashboard core; no Android or web server is required. */
public final class DesktopDashboard implements AutoCloseable {
    private final Object lock = new Object();
    private final Object coreAccess = new Object();
    private final ThreadLocal<Boolean> delivering = ThreadLocal.withInitial(() -> false);
    private final DashboardCore core;
    private final ExecutorService worker;
    private final List<?> pending;
    private final SocketHandler socket;
    private volatile Consumer<JsonObject> consumer;
    private RuntimeException failure;
    private boolean closed;
    private int outstanding;

    public DesktopDashboard(Consumer<JsonObject> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        // Core 0.4.16 owns a non-daemon executor but exposes no close method. Keep this
        // version-specific lifecycle adaptation here, rather than copying its worker.
        Field executorField;
        Field pendingField;
        try {
            executorField = DashboardCore.class.getDeclaredField("telemetryExecutorService");
            executorField.setAccessible(true);
            pendingField = DashboardCore.class.getDeclaredField("pendingTelemetry");
            pendingField.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Dashboard core 0.4.16 lifecycle API changed", e);
        }
        core = new DashboardCore();
        try {
            worker = (ExecutorService) executorField.get(core);
            pending = (List<?>) pendingField.get(core);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access Dashboard core worker", e);
        }
        core.enabled = true;
        socket = core.newSocket(message -> {
            if (!(message instanceof ReceiveTelemetry)) return;
            JsonObject wire = DashboardCore.GSON.toJsonTree(message).getAsJsonObject();
            try {
                delivering.set(true);
                this.consumer.accept(wire);
            } catch (RuntimeException e) {
                synchronized (lock) {
                    failure = e;
                    lock.notifyAll();
                }
                // The core submits its worker as a Future, so explicitly report failures too.
                Thread thread = Thread.currentThread();
                thread.getUncaughtExceptionHandler().uncaughtException(thread, e);
                throw e;
            } finally {
                delivering.remove();
            }
            synchronized (lock) {
                outstanding = Math.max(0, outstanding - wire.getAsJsonArray("telemetry").size());
                lock.notifyAll();
            }
        });
        socket.onOpen();
    }

    public void setConsumer(Consumer<JsonObject> consumer) {
        synchronized (lock) {
            checkOpen();
            this.consumer = Objects.requireNonNull(consumer, "consumer");
        }
    }

    public void sendTelemetryPacket(TelemetryPacket packet) {
        checkNotDelivering();
        Objects.requireNonNull(packet, "packet");
        TelemetryPacket snapshot = snapshot(packet);
        synchronized (coreAccess) {
            synchronized (lock) {
                checkOpen();
                // The published core silently drops packets once its queue exceeds 100.
                // Backpressure keeps that limit unreachable, including tight RR action loops.
                while (outstanding >= 100) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while queuing dashboard telemetry", e);
                    }
                    checkOpen();
                }
                outstanding++;
            }
            // Never hold the adapter lock while entering core's pending/sockets locks.
            try {
                core.sendTelemetryPacket(snapshot);
            } catch (RuntimeException e) {
                synchronized (lock) {
                    outstanding--;
                    lock.notifyAll();
                }
                throw e;
            }
        }
    }

    public int getTelemetryTransmissionInterval() {
        synchronized (lock) {
            checkOpen();
            return core.getTelemetryTransmissionInterval();
        }
    }

    public void setTelemetryTransmissionInterval(int milliseconds) {
        if (milliseconds < 0) throw new IllegalArgumentException("Transmission interval must be nonnegative");
        synchronized (lock) {
            checkOpen();
            core.setTelemetryTransmissionInterval(milliseconds);
        }
    }

    public void clearTelemetry() {
        checkNotDelivering();
        synchronized (coreAccess) {
            checkOpen();
            // Account only for queued packets, not a batch already owned by the worker.
            // Keep the core queue locked across counting and its public clear operation.
            synchronized (pending) {
                int removed = pending.size();
                core.clearTelemetry();
                synchronized (lock) {
                    outstanding -= removed;
                    lock.notifyAll();
                }
            }
        }
    }

    private void checkNotDelivering() {
        if (delivering.get()) {
            throw new IllegalStateException("Dashboard consumers must not submit, clear, or stop synchronously");
        }
    }

    public void checkOpen() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Dashboard is shut down; call start() to restart");
            if (failure != null) throw new IllegalStateException("Dashboard consumer failed", failure);
        }
    }

    private static TelemetryPacket snapshot(TelemetryPacket source) {
        JsonObject wire = DashboardCore.GSON.toJsonTree(source).getAsJsonObject();
        validateNumbers(wire);
        TelemetryPacket copy = new TelemetryPacket(false);
        for (Map.Entry<String, JsonElement> entry : wire.getAsJsonObject("data").entrySet()) {
            copy.put(entry.getKey(), entry.getValue().getAsString());
        }
        for (JsonElement line : wire.getAsJsonArray("log")) {
            if (line.isJsonNull()) throw new IllegalArgumentException("Telemetry log lines must not be null");
            copy.addLine(line.getAsString());
        }
        copyCanvas(source.field(), copy.field());
        copyCanvas(source.fieldOverlay(), copy.fieldOverlay());
        return copy;
    }

    private static void copyCanvas(Canvas source, Canvas target) {
        for (CanvasOp op : source.getOperations()) {
            Objects.requireNonNull(op, "canvas operation");
            // CanvasOp is abstract and has no getters. Deserialize the actual published
            // concrete operation, not CanvasOp, to preserve types and detach mutable arrays.
            target.getOperations().add(DashboardCore.GSON.fromJson(
                    DashboardCore.GSON.toJsonTree(op), op.getClass()));
        }
    }

    private static void validateNumbers(JsonElement element) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                validateNumbers(entry.getValue());
            }
        } else if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) validateNumbers(value);
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                && !Double.isFinite(element.getAsDouble())) {
            throw new IllegalArgumentException("Dashboard canvas coordinates must be finite");
        }
    }

    @Override
    public void close() {
        checkNotDelivering();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            lock.notifyAll();
        }
        synchronized (coreAccess) {
            core.enabled = false;
            worker.shutdownNow();
        }
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Dashboard worker did not terminate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping dashboard", e);
        } finally {
            socket.onClose();
        }
    }
}
