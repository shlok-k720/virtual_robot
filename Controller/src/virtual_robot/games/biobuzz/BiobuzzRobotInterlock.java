package virtual_robot.games.biobuzz;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.dyn4j.dynamics.Body;
import org.dyn4j.dynamics.joint.Joint;
import org.dyn4j.dynamics.joint.PrismaticJoint;
import org.dyn4j.dynamics.joint.RevoluteJoint;
import org.dyn4j.world.World;

/**
 * Suspends robot joint drives while the rest of the BIOBUZZ world keeps stepping.
 * All calls must be serialized externally, under the world's monitor.
 */
public final class BiobuzzRobotInterlock {
    private final Map<Joint<Body>, JointState> saved = new IdentityHashMap<>();

    public void suspend(World<Body> world) {
        Set<Joint<Body>> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(world.getJoints());
        saved.keySet().retainAll(present);

        for (Joint<Body> joint : world.getJoints()) {
            if (joint instanceof PrismaticJoint<?>) {
                PrismaticJoint<?> slide = (PrismaticJoint<?>) joint;
                double position = slide.getJointTranslation();
                // dyn4j 4.1.4's equal-limit prismatic solver corrects toward zero,
                // not toward the limit. Avoid that branch with a tolerance-wide hold.
                double tolerance = world.getSettings().getLinearTolerance();
                double lower = Math.nextDown(position - tolerance);
                double upper = Math.nextUp(position + tolerance);
                if (slide.isMotorEnabled() || slide.getMotorSpeed() != 0.0
                        || !slide.isLimitEnabled()
                        || slide.getLowerLimit() != lower || slide.getUpperLimit() != upper) {
                    if (!saved.containsKey(joint)) saved.put(joint, new JointState(slide));
                    slide.setMotorEnabled(false);
                    slide.setMotorSpeed(0.0);
                    slide.setLimitsEnabled(lower, upper);
                }
            } else if (joint instanceof RevoluteJoint<?>) {
                RevoluteJoint<?> hinge = (RevoluteJoint<?>) joint;
                // getJointAngle is already relative to the reference angle.
                // Leave that reference (including a Hinge's target) untouched.
                double angle = hinge.getJointAngle();
                if (hinge.isMotorEnabled() || hinge.getMotorSpeed() != 0.0
                        || !hinge.isLimitEnabled()
                        || hinge.getLowerLimit() != angle || hinge.getUpperLimit() != angle) {
                    if (!saved.containsKey(joint)) saved.put(joint, new JointState(hinge));
                    hinge.setMotorEnabled(false);
                    hinge.setMotorSpeed(0.0);
                    hinge.setLimits(angle, angle);
                    hinge.setLimitEnabled(true);
                }
            }
        }
    }

    public void resume(World<Body> world) {
        for (Joint<Body> joint : world.getJoints()) {
            JointState state = saved.get(joint);
            if (state == null) continue;
            if (joint instanceof PrismaticJoint<?>) {
                PrismaticJoint<?> slide = (PrismaticJoint<?>) joint;
                slide.setLimits(state.lower, state.upper);
                slide.setLimitEnabled(state.limitEnabled);
                slide.setMotorSpeed(state.speed);
                slide.setMotorEnabled(state.motorEnabled);
            } else if (joint instanceof RevoluteJoint<?>) {
                RevoluteJoint<?> hinge = (RevoluteJoint<?>) joint;
                hinge.setLimits(state.lower, state.upper);
                hinge.setLimitEnabled(state.limitEnabled);
                hinge.setMotorSpeed(state.speed);
                hinge.setMotorEnabled(state.motorEnabled);
            }
        }
        clear();
    }

    public void clear() {
        saved.clear();
    }

    private static final class JointState {
        final boolean motorEnabled;
        final double speed;
        final boolean limitEnabled;
        final double lower;
        final double upper;

        JointState(PrismaticJoint<?> joint) {
            motorEnabled = joint.isMotorEnabled();
            speed = joint.getMotorSpeed();
            limitEnabled = joint.isLimitEnabled();
            lower = joint.getLowerLimit();
            upper = joint.getUpperLimit();
        }

        JointState(RevoluteJoint<?> joint) {
            motorEnabled = joint.isMotorEnabled();
            speed = joint.getMotorSpeed();
            limitEnabled = joint.isLimitEnabled();
            lower = joint.getLowerLimit();
            upper = joint.getUpperLimit();
        }
    }
}
