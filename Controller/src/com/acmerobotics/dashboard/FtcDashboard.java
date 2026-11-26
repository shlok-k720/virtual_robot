package com.acmerobotics.dashboard;

import com.acmerobotics.dashboard.telemetry.TelemetryPacket;
import org.firstinspires.ftc.robotcore.external.Telemetry;
import com.google.gson.JsonObject;
import virtual_robot.controller.VirtualRobotApplication;
import virtual_robot.dashboard.DashboardTelemetry;
import virtual_robot.dashboard.DashboardView;
import virtual_robot.dashboard.DesktopDashboard;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Binary-compatible desktop entry point. The Android facade needs Activity/Dalvik/FTC
 * webserver APIs; the published DashboardCore supplies this adapter's functional backend.
 */
public final class FtcDashboard {
    private static final class Holder {
        private static final FtcDashboard INSTANCE = new FtcDashboard();
    }

    private final Telemetry telemetry = new DashboardTelemetry(this::sendTelemetryPacket,
            milliseconds -> requireBackend().setTelemetryTransmissionInterval(milliseconds),
            this::getTelemetryTransmissionInterval);
    private volatile DesktopDashboard backend;
    private DashboardView view;
    private volatile boolean customConsumer;

    private FtcDashboard() {
        start();
    }

    public static FtcDashboard getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized void start() {
        if (backend != null) return;
        view = new DashboardView();
        backend = new DesktopDashboard(view);
        customConsumer = false;
    }

    public void sendTelemetryPacket(TelemetryPacket packet) {
        DesktopDashboard current = requireBackend();
        if (!customConsumer && VirtualRobotApplication.getControllerHandle() == null) {
            throw new IllegalStateException("Simulator controller is not initialized");
        }
        current.sendTelemetryPacket(packet);
    }

    public Telemetry getTelemetry() {
        return telemetry;
    }

    public void setTelemetryTransmissionInterval(int milliseconds) {
        requireBackend().setTelemetryTransmissionInterval(milliseconds);
    }

    public int getTelemetryTransmissionInterval() {
        return requireBackend().getTelemetryTransmissionInterval();
    }

    public void clearTelemetry() {
        requireBackend().clearTelemetry();
    }

    /** Replaces the JavaFX destination with an in-process published JSON wire consumer. */
    public synchronized void setTelemetryConsumer(Consumer<JsonObject> consumer) {
        requireBackend().setConsumer(Objects.requireNonNull(consumer, "consumer"));
        customConsumer = true;
    }

    private DesktopDashboard requireBackend() {
        DesktopDashboard current = backend;
        if (current == null) throw new IllegalStateException("Dashboard is shut down; call start() to restart");
        current.checkOpen();
        return current;
    }

    public synchronized void shutdown() {
        if (backend == null) return;
        try {
            backend.close();
        } finally {
            backend = null;
            view.close();
        }
    }
}