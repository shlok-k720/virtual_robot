package virtual_robot.util;

import com.acmerobotics.roadrunner.ftc.LazyImu;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

public final class SimulatedLazyImuTest {
    public static void main(String[] args) {
        ImuOrientationOnRobot orientation = new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP,
                RevHubOrientationOnRobot.UsbFacingDirection.FORWARD);
        HardwareMap hardwareMap = new HardwareMap();
        FakeImu imu = new FakeImu();
        hardwareMap.put("imu", imu);
        LazyImu lazy = new SimulatedLazyImu(hardwareMap, "imu", orientation);

        check(imu.initializations == 0, "IMU initialized before first access");
        hardwareMap.setActive(true);
        check(lazy.get() == imu, "Wrong IMU instance returned");
        check(lazy.get() == imu, "IMU was not cached");
        check(imu.initializations == 1, "IMU initialized more than once");
        check(imu.parameters.imuOrientationOnRobot == orientation, "Mounting orientation lost");

        FakeImu retryImu = new FakeImu();
        retryImu.initializeSuccessfully = false;
        hardwareMap.put("retry", retryImu);
        LazyImu retry = new SimulatedLazyImu(hardwareMap, "retry", orientation);
        expect(IllegalStateException.class, retry::get);
        retryImu.initializeSuccessfully = true;
        check(retry.get() == retryImu, "Failed initialization was cached");
        check(retryImu.initializations == 2, "Failed initialization was not retried");

        LazyImu missing = new SimulatedLazyImu(hardwareMap, "missing", orientation);
        expect(IllegalArgumentException.class, missing::get);
        FakeImu added = new FakeImu();
        hardwareMap.put("missing", added);
        check(missing.get() == added, "Failed lookup was cached");

        expect(NullPointerException.class, () -> new SimulatedLazyImu(null, "imu", orientation));
        expect(NullPointerException.class, () -> new SimulatedLazyImu(hardwareMap, null, orientation));
        expect(NullPointerException.class, () -> new SimulatedLazyImu(hardwareMap, "imu", null));
        System.out.println("SimulatedLazyImuTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends RuntimeException> expected, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            if (expected.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private static final class FakeImu implements IMU {
        private int initializations;
        private Parameters parameters;
        private boolean initializeSuccessfully = true;

        @Override
        public boolean initialize(Parameters parameters) {
            initializations++;
            this.parameters = parameters;
            return initializeSuccessfully;
        }

        @Override
        public void resetYaw() {
            throw new UnsupportedOperationException();
        }

        @Override
        public YawPitchRollAngles getRobotYawPitchRollAngles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Orientation getRobotOrientation(AxesReference reference, AxesOrder order, AngleUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Quaternion getRobotOrientationAsQuaternion() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AngularVelocity getRobotAngularVelocity(AngleUnit unit) {
            throw new UnsupportedOperationException();
        }
    }
}
