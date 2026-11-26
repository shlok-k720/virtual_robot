package virtual_robot.dashboard;

import com.acmerobotics.dashboard.DashboardCore;
import com.acmerobotics.dashboard.FtcDashboard;
import com.acmerobotics.dashboard.canvas.Canvas;
import com.acmerobotics.dashboard.canvas.CanvasOp;
import com.acmerobotics.dashboard.config.Config;
import com.acmerobotics.dashboard.telemetry.MultipleTelemetry;
import com.acmerobotics.dashboard.telemetry.TelemetryPacket;
import com.acmerobotics.roadrunner.Action;
import com.acmerobotics.roadrunner.ftc.Actions;
import com.google.gson.JsonObject;
import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Plain-Java regression entry point; does not start a JavaFX toolkit or application. */
public final class DesktopDashboardTest {
    public static void main(String[] args) throws Exception {
        for (Class<?> published : new Class<?>[]{Canvas.class, CanvasOp.class, TelemetryPacket.class,
                Config.class, MultipleTelemetry.class, DashboardCore.class}) {
            check(published.getResource(published.getSimpleName() + ".class").getProtocol().equals("jar"),
                    "Class must originate in published jar: " + published);
        }
        backendDelivery();
        concurrentClear();
        consumerFailure();
        facadeAndRoadRunner();
        System.out.println("DesktopDashboardTest passed");
    }

    private static void backendDelivery() throws Exception {
        BlockingQueue<JsonObject> received = new LinkedBlockingQueue<>();
        DesktopDashboard backend = new DesktopDashboard(received::add);
        try {
            backend.setTelemetryTransmissionInterval(0);
            check(backend.getTelemetryTransmissionInterval() == 0, "Zero interval accepted");
            expect(IllegalArgumentException.class, () -> backend.setTelemetryTransmissionInterval(-1));
            expect(NullPointerException.class, () -> backend.sendTelemetryPacket(null));
            TelemetryPacket invalid = new TelemetryPacket(false);
            invalid.fieldOverlay().getOperations().add(null);
            expect(NullPointerException.class, () -> backend.sendTelemetryPacket(invalid));
            invalid.fieldOverlay().clear();
            invalid.fieldOverlay().strokeCircle(Double.NaN, 0, 1);
            expect(IllegalArgumentException.class, () -> backend.sendTelemetryPacket(invalid));
            TelemetryPacket packet = new TelemetryPacket(false);
            packet.put("speed", 42);
            packet.fieldOverlay().setStroke("green").strokeCircle(1, 2, 3);
            backend.sendTelemetryPacket(packet);
            packet.put("speed", 99);
            packet.fieldOverlay().clear();
            JsonObject delivered = take(received).getAsJsonArray("telemetry").get(0).getAsJsonObject();
            check(delivered.getAsJsonObject("data").get("speed").getAsString().equals("42"),
                    "Packet snapshot detached from caller");
            check(delivered.get("timestamp").getAsLong() > 0, "Core added timestamp");
            check(delivered.getAsJsonObject("fieldOverlay").getAsJsonArray("ops").size() == 2,
                    "Published canvas operations delivered");

            AtomicInteger count = new AtomicInteger();
            CountDownLatch complete = new CountDownLatch(1);
            backend.setConsumer(wire -> {
                if (count.addAndGet(wire.getAsJsonArray("telemetry").size()) == 300) complete.countDown();
            });
            for (int i = 0; i < 300; i++) backend.sendTelemetryPacket(new TelemetryPacket(false));
            await(complete, "Backpressure must not drop packets");
        } finally {
            backend.close();
            backend.close();
        }
        expect(IllegalStateException.class, backend::checkOpen);
        expect(IllegalStateException.class, () -> backend.sendTelemetryPacket(new TelemetryPacket()));
    }

    private static void concurrentClear() throws Exception {
        CountDownLatch inConsumer = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (DesktopDashboard backend = new DesktopDashboard(wire -> {
            if (wire.getAsJsonArray("telemetry").size() == 0) return;
            inConsumer.countDown();
            try { await(release, "Consumer release"); }
            catch (Exception e) { throw new IllegalStateException(e); }
        })) {
            backend.sendTelemetryPacket(new TelemetryPacket(false));
            await(inConsumer, "Consumer entered");
            Thread clear = new Thread(() -> {
                try { backend.clearTelemetry(); }
                catch (Throwable e) { failure.set(e); }
            }, "dashboard clear regression");
            clear.start();
            release.countDown();
            clear.join(5000);
            check(!clear.isAlive(), "Clear must not deadlock with delivery");
            check(failure.get() == null, "Clear failed: " + failure.get());
        }
    }

    private static void consumerFailure() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        try (DesktopDashboard backend = new DesktopDashboard(wire -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> reported.countDown());
            throw new IllegalArgumentException("consumer regression");
        })) {
            backend.sendTelemetryPacket(new TelemetryPacket(false));
            await(reported, "Consumer errors explicitly reported");
            expect(IllegalStateException.class, backend::checkOpen);
        }
        AtomicReference<DesktopDashboard> self = new AtomicReference<>();
        CountDownLatch rejected = new CountDownLatch(1);
        try (DesktopDashboard backend = new DesktopDashboard(wire -> {
            expect(IllegalStateException.class,
                    () -> self.get().sendTelemetryPacket(new TelemetryPacket(false)));
            rejected.countDown();
        })) {
            self.set(backend);
            backend.sendTelemetryPacket(new TelemetryPacket(false));
            await(rejected, "Reentrant send fails explicitly, rather than deadlocking");
        }
    }

    private static void facadeAndRoadRunner() throws Exception {
        FtcDashboard dashboard = FtcDashboard.getInstance();
        BlockingQueue<JsonObject> received = new LinkedBlockingQueue<>();
        try {
            check(dashboard == FtcDashboard.getInstance(), "Stable singleton");
            dashboard.start();
            dashboard.setTelemetryConsumer(received::add);
            Telemetry telemetry = dashboard.getTelemetry();
            check(telemetry != null, "Non-null Telemetry");
            new MultipleTelemetry(telemetry).setMsTransmissionInterval(0);
            check(dashboard.getTelemetryTransmissionInterval() == 0, "Telemetry interval reaches core");
            check(telemetry.getMsTransmissionInterval() == 0, "Telemetry interval getter agrees");
            telemetry.addData("answer", () -> 42);
            check(telemetry.update(), "Telemetry update queued");
            check(take(received).getAsJsonArray("telemetry").get(0).getAsJsonObject()
                    .getAsJsonArray("log").get(0).getAsString().contains("42"), "Producer evaluated");
            telemetry.clearAll();
            telemetry.update();
            check(take(received).getAsJsonArray("telemetry").get(0).getAsJsonObject()
                    .getAsJsonArray("log").size() == 0, "Empty snapshot clears telemetry lines");
            Actions.runBlocking(new Action() {
                @Override public void preview(Canvas canvas) { canvas.strokeCircle(0, 0, 1); }
                @Override public boolean run(TelemetryPacket packet) {
                    packet.put("action", "done");
                    return false;
                }
            });
            JsonObject packet = take(received).getAsJsonArray("telemetry").get(0).getAsJsonObject();
            check(packet.getAsJsonObject("data").get("action").getAsString().equals("done"),
                    "Published Actions.runBlocking links to facade");
            check(packet.getAsJsonObject("fieldOverlay").getAsJsonArray("ops").size() == 1,
                    "Action preview operations delivered");
            dashboard.shutdown();
            expect(IllegalStateException.class, () -> dashboard.sendTelemetryPacket(new TelemetryPacket()));
            dashboard.start();
            dashboard.setTelemetryConsumer(received::add);
            dashboard.sendTelemetryPacket(new TelemetryPacket(false));
            take(received);
        } finally {
            dashboard.shutdown();
        }
        check(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().equals("dash telemetry")),
                "All published core workers stopped");
    }

    private static JsonObject take(BlockingQueue<JsonObject> queue) throws Exception {
        JsonObject value = queue.poll(5, TimeUnit.SECONDS);
        check(value != null, "Packet delivery timed out");
        return value;
    }

    private static void await(CountDownLatch latch, String message) throws Exception {
        check(latch.await(5, TimeUnit.SECONDS), message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable error) {
            check(type.isInstance(error), "Expected " + type + ", got " + error);
            return;
        }
        throw new AssertionError("Expected " + type);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
