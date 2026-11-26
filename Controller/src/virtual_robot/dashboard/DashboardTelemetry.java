package virtual_robot.dashboard;

import com.acmerobotics.dashboard.telemetry.TelemetryPacket;
import org.firstinspires.ftc.robotcore.internal.opmode.TelemetryImpl;

import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reuses simulator item/line/log/action semantics, replacing only its display transport. */
public final class DashboardTelemetry extends TelemetryImpl {
    private final Consumer<TelemetryPacket> sender;
    private final IntConsumer intervalSetter;
    private final IntSupplier intervalGetter;
    private final PacketLog packetLog = new PacketLog();

    public DashboardTelemetry(Consumer<TelemetryPacket> sender, IntConsumer intervalSetter,
                              IntSupplier intervalGetter) {
        super(null);
        this.sender = sender;
        this.intervalSetter = intervalSetter;
        this.intervalGetter = intervalGetter;
    }

    @Override
    protected boolean tryUpdate(UpdateReason reason) {
        synchronized (theLock) {
            if (reason == UpdateReason.IFDIRTY && !isDirty && !packetLog.dirty) return false;
            for (Runnable action : actions) action.run();
            boolean recompose = reason == UpdateReason.USER || isDirty;
            if (recompose) {
                composedLines.clear();
                for (Lineable line : lines) composedLines.add(line.getComposed(true));
            }
            TelemetryPacket packet = new TelemetryPacket(false);
            for (String line : composedLines) packet.addLine(line);
            for (int i = 0; i < packetLog.entries.size(); i++) {
                int index = packetLog.order == Log.DisplayOrder.OLDEST_FIRST
                        ? i : packetLog.entries.size() - i - 1;
                packet.addLine(packetLog.entries.get(index));
            }
            sender.accept(packet);
            packetLog.dirty = false;
            isDirty = false;
            if (reason == UpdateReason.USER) clearOnAdd = isAutoClear();
            return true;
        }
    }

    @Override
    public void setMsTransmissionInterval(int milliseconds) {
        if (milliseconds < 0) throw new IllegalArgumentException("Transmission interval must be nonnegative");
        intervalSetter.accept(milliseconds);
        super.setMsTransmissionInterval(milliseconds);
    }

    @Override public int getMsTransmissionInterval() {
        return intervalGetter.getAsInt();
    }

    @Override public Log log() {
        return packetLog;
    }

    // TelemetryImpl's LogImpl readers are package-private; retain a small desktop log
    // rather than reflecting its implementation or duplicating the whole Telemetry API.
    private final class PacketLog implements Log {
        private final List<String> entries = new ArrayList<>();
        private int capacity = 9;
        private DisplayOrder order = DisplayOrder.OLDEST_FIRST;
        private boolean dirty;

        @Override public int getCapacity() {
            synchronized (theLock) { return capacity; }
        }
        @Override public void setCapacity(int capacity) {
            if (capacity < 0) throw new IllegalArgumentException("Log capacity must be nonnegative");
            synchronized (theLock) {
                this.capacity = capacity;
                prune();
            }
        }
        @Override public DisplayOrder getDisplayOrder() {
            synchronized (theLock) { return order; }
        }
        @Override public void setDisplayOrder(DisplayOrder order) {
            synchronized (theLock) { this.order = Objects.requireNonNull(order, "order"); }
        }
        @Override public void add(String entry) {
            synchronized (theLock) {
                entries.add(Objects.requireNonNull(entry, "entry"));
                dirty = true;
                prune();
                tryUpdate(UpdateReason.LOG);
            }
        }
        @Override public void add(String format, Object... args) {
            add(String.format(format, args));
        }
        @Override public void clear() {
            synchronized (theLock) {
                entries.clear();
                dirty = true;
            }
        }
        private void prune() {
            while (entries.size() > capacity) entries.remove(0);
        }
    }
}
