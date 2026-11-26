package virtual_robot.util;

import com.acmerobotics.roadrunner.ftc.LazyImu;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

import java.util.Objects;

/**
 * Lazy Road Runner IMU initialization without Android hub discovery or FlightRecorder.
 * Simulated IMUs initialize synchronously, so hardware acquisition polling is unnecessary.
 */
public final class SimulatedLazyImu implements LazyImu {
    private final HardwareMap hardwareMap;
    private final String name;
    private final ImuOrientationOnRobot orientation;
    private IMU imu;

    public SimulatedLazyImu(HardwareMap hardwareMap, String name, ImuOrientationOnRobot orientation) {
        this.hardwareMap = Objects.requireNonNull(hardwareMap, "hardwareMap");
        this.name = Objects.requireNonNull(name, "name");
        this.orientation = Objects.requireNonNull(orientation, "orientation");
    }

    @Override
    public synchronized IMU get() {
        if (imu == null) {
            IMU candidate = hardwareMap.get(IMU.class, name);
            if (!candidate.initialize(new IMU.Parameters(orientation))) {
                throw new IllegalStateException("Failed to initialize simulated IMU: " + name);
            }
            imu = candidate;
        }
        return imu;
    }
}
