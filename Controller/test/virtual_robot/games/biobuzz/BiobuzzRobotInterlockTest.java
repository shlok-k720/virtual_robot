package virtual_robot.games.biobuzz;

import org.dyn4j.dynamics.Body;
import org.dyn4j.dynamics.joint.PrismaticJoint;
import org.dyn4j.dynamics.joint.RevoluteJoint;
import org.dyn4j.geometry.Geometry;
import org.dyn4j.geometry.MassType;
import org.dyn4j.geometry.Vector2;
import org.dyn4j.world.World;

/** Headless plain-main integration tests; only dyn4j 4.1.4 is required. */
public final class BiobuzzRobotInterlockTest {
    private static final double EPS = 1e-8;

    private BiobuzzRobotInterlockTest() {
    }

    public static void main(String[] args) {
        testSlideTarget(true);
        testSlideTarget(false);
        testHingeTarget();
        testRawRevolute();
        testReplacement();
        testClear();
        System.out.println("BiobuzzRobotInterlockTest: 6 tests passed");
    }

    private static void testSlideTarget(boolean motorEnabled) {
        Fixture fixture = new Fixture();
        // Reproduce Slide's powered target limits without loading simulator/JavaFX classes.
        PrismaticJoint<Body> slide = fixture.slide();
        fixture.moving.translate(0.6, 0.0);
        slide.setMotorEnabled(motorEnabled);
        slide.setMotorSpeed(2.0);
        slide.setMaximumMotorForce(100.0);
        slide.setLimitsEnabled(1.195, 1.205);
        double position = slide.getJointTranslation();
        Body groundPiece = body();
        groundPiece.translate(5.0, 5.0);
        groundPiece.setLinearVelocity(0.5, 0.0);
        fixture.world.addBody(groundPiece);
        double groundX = groundPiece.getWorldCenter().x;

        synchronized (fixture.world) {
            for (int tick = 0; tick < 120; tick++) {
                fixture.interlock.suspend(fixture.world);
                check(!slide.isMotorEnabled(), "Slide motor remains powered");
                close(slide.getMotorSpeed(), 0.0, "Slide speed remains powered");
                fixture.world.step(1);
                close(slide.getJointTranslation(), position, "Slide moved toward disabled target");
            }
            check(groundPiece.getWorldCenter().x > groundX + 0.1, "Ground world stopped");
            fixture.interlock.resume(fixture.world);
            check(slide.isMotorEnabled() == motorEnabled, "Slide motor flag not restored");
            close(slide.getMotorSpeed(), 2.0, "Slide speed not restored");
            check(slide.isLimitEnabled(), "Slide limits not restored");
            close(slide.getLowerLimit(), 1.195, "Slide lower target not restored");
            close(slide.getUpperLimit(), 1.205, "Slide upper target not restored");
            fixture.world.step(60);
            check(slide.getJointTranslation() > position + 0.2, "Slide did not resume target motion");
            fixture.interlock.resume(fixture.world);
            close(slide.getLowerLimit(), 1.195, "Repeated resume changed target");
        }
    }

    private static void testHingeTarget() {
        Fixture fixture = new Fixture();
        RevoluteJoint<Body> hinge = fixture.hinge();
        fixture.moving.rotateAboutCenter(0.4);
        // Hinge.setPosition changes the reference angle, rather than its small target limits.
        hinge.setReferenceAngle(1.1);
        hinge.setLimits(-0.005, 0.005);
        hinge.setLimitEnabled(true);
        hinge.setMotorEnabled(true);
        hinge.setMotorSpeed(2.0);
        hinge.setMaximumMotorTorque(100.0);
        double angle = hinge.getJointAngle();
        synchronized (fixture.world) {
            for (int tick = 0; tick < 120; tick++) {
                fixture.interlock.suspend(fixture.world);
                check(!hinge.isMotorEnabled(), "Hinge motor remains powered");
                close(hinge.getReferenceAngle(), 1.1, "Hinge reference was changed");
                close(hinge.getLowerLimit(), angle, "Hold angle not reference-relative");
                fixture.world.step(1);
                close(hinge.getJointAngle(), angle, "Hinge moved toward disabled target");
            }
            fixture.interlock.resume(fixture.world);
            check(hinge.isMotorEnabled() && hinge.isLimitEnabled(), "Hinge flags not restored");
            close(hinge.getMotorSpeed(), 2.0, "Hinge speed not restored");
            close(hinge.getLowerLimit(), -0.005, "Hinge lower target not restored");
            close(hinge.getUpperLimit(), 0.005, "Hinge upper target not restored");
            close(hinge.getReferenceAngle(), 1.1, "Hinge reference not preserved");
            fixture.world.step(60);
            check(hinge.getJointAngle() > angle + 0.2, "Hinge did not resume target motion");
        }
    }

    private static void testRawRevolute() {
        Fixture fixture = new Fixture();
        RevoluteJoint<Body> hinge = fixture.hinge();
        fixture.moving.rotateAboutCenter(-0.3);
        hinge.setLimits(-1.0, 0.8);
        hinge.setLimitEnabled(false);
        hinge.setMotorEnabled(true);
        hinge.setMotorSpeed(-1.5);
        hinge.setMaximumMotorTorque(50.0);
        double angle = hinge.getJointAngle();
        synchronized (fixture.world) {
            fixture.interlock.suspend(fixture.world);
            fixture.world.step(60);
            close(hinge.getJointAngle(), angle, "Raw revolute joint moved while suspended");
            fixture.interlock.resume(fixture.world);
            check(!hinge.isLimitEnabled(), "Originally disabled limits not restored");
            check(hinge.isMotorEnabled(), "Raw revolute motor not restored");
            close(hinge.getMotorSpeed(), -1.5, "Raw revolute speed not restored");
            close(hinge.getLowerLimit(), -1.0, "Raw revolute lower limit lost");
            close(hinge.getUpperLimit(), 0.8, "Raw revolute upper limit lost");
            fixture.world.step(30);
            check(hinge.getJointAngle() < angle - 0.1, "Raw revolute did not resume");
        }
    }

    private static void testReplacement() {
        Fixture fixture = new Fixture();
        PrismaticJoint<Body> old = fixture.slide();
        old.setMotorEnabled(true);
        old.setMotorSpeed(3.0);
        synchronized (fixture.world) {
            fixture.interlock.suspend(fixture.world);
            fixture.world.removeJoint(old);
            PrismaticJoint<Body> replacement = fixture.slide();
            replacement.setMotorEnabled(true);
            replacement.setMotorSpeed(-4.0);
            replacement.setLimits(-2.0, 3.0);
            fixture.interlock.suspend(fixture.world);
            // A removed joint must have been forgotten, even if later reinserted.
            fixture.world.removeJoint(replacement);
            fixture.world.addJoint(old);
            fixture.interlock.resume(fixture.world);
            check(!old.isMotorEnabled(), "Removed joint's stale state was restored");
            check(!replacement.isMotorEnabled(), "Absent replacement was restored");
            fixture.world.removeJoint(old);
            fixture.world.addJoint(replacement);
            replacement.setMotorSpeed(7.0);
            fixture.interlock.suspend(fixture.world);
            fixture.interlock.resume(fixture.world);
            close(replacement.getMotorSpeed(), 7.0, "Absent replacement's stale state retained");
        }
    }

    private static void testClear() {
        Fixture fixture = new Fixture();
        PrismaticJoint<Body> slide = fixture.slide();
        slide.setMotorEnabled(true);
        slide.setMotorSpeed(3.0);
        synchronized (fixture.world) {
            fixture.interlock.suspend(fixture.world);
            fixture.interlock.clear();
            fixture.interlock.resume(fixture.world);
            check(!slide.isMotorEnabled(), "Clear restored a discarded robot");
            close(slide.getMotorSpeed(), 0.0, "Clear retained old speed");
        }
    }

    private static Body body() {
        Body body = new Body();
        body.addFixture(Geometry.createRectangle(0.1, 0.1));
        body.setMass(MassType.NORMAL);
        body.setLinearDamping(0.0);
        body.setAngularDamping(0.0);
        return body;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void close(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < EPS, message + ": " + actual + " != " + expected);
    }

    private static final class Fixture {
        final World<Body> world = new World<>();
        final BiobuzzRobotInterlock interlock = new BiobuzzRobotInterlock();
        final Body moving = body();
        final Body base = body();

        Fixture() {
            world.setGravity(0.0, 0.0);
            base.setMass(MassType.INFINITE);
            world.addBody(moving);
            world.addBody(base);
        }

        PrismaticJoint<Body> slide() {
            PrismaticJoint<Body> joint =
                    new PrismaticJoint<>(moving, base, new Vector2(), new Vector2(1.0, 0.0));
            world.addJoint(joint);
            return joint;
        }

        RevoluteJoint<Body> hinge() {
            RevoluteJoint<Body> joint = new RevoluteJoint<>(moving, base, new Vector2());
            world.addJoint(joint);
            return joint;
        }
    }
}
