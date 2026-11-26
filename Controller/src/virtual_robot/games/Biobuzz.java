package virtual_robot.games;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import org.dyn4j.dynamics.Body;
import org.dyn4j.dynamics.BodyFixture;
import org.dyn4j.geometry.Geometry;
import org.dyn4j.geometry.MassType;
import org.dyn4j.geometry.Transform;
import virtual_robot.controller.Filters;
import virtual_robot.controller.Game;
import virtual_robot.controller.VirtualField;
import virtual_robot.games.biobuzz.BiobuzzModel;
import virtual_robot.games.biobuzz.BiobuzzModel.Alliance;
import virtual_robot.games.biobuzz.BiobuzzModel.Kind;
import virtual_robot.games.biobuzz.BiobuzzModel.Phase;
import virtual_robot.games.biobuzz.BiobuzzModel.Piece;
import virtual_robot.games.biobuzz.BiobuzzModel.Place;
import virtual_robot.games.biobuzz.BiobuzzModel.Result;
import virtual_robot.games.biobuzz.BiobuzzPanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Single-robot BIOBUZZ practice and match adapter. Only floor pieces and nominal
 * support footprints collide; elevated transfers are explicit model actions.
 * The controller's world monitor serializes actions, rendering snapshots and physics.
 */
public final class Biobuzz extends Game {
    private static final double INCH = 0.0254;
    private static final double[][] FLOWERS = {{-68, -24}, {-24, 68}, {68, 24}, {24, -68}};
    private final BiobuzzModel model = new BiobuzzModel();
    private final Map<Integer, Body> ground = new LinkedHashMap<>();
    private final List<Body> supports = new ArrayList<>();
    private final Map<Integer, Circle> pieceNodes = new HashMap<>();
    private final Group overlay = new Group();
    private final Group pieceLayer = new Group();
    private final Group stackLayer = new Group();
    private final Text[] hiveLabels = {new Text(), new Text()};
    private final Map<Body, double[]> pausedVelocities = new HashMap<>();
    private BiobuzzPanel panel;
    private boolean humanAuto;
    private double humanElapsed;
    private double restingSeconds;
    private double settlingSeconds;
    private int lastTipCount;
    private String actionStatus = "Practice setup ready. INIT/START an OpMode to drive.";

    @Override public void initialize() {
        super.initialize();
        synchronized (world) {
            overlay.setId("biobuzz-overlay");
            overlay.setMouseTransparent(true);
            pieceLayer.setId("biobuzz-pieces");
            createSupports();
            overlay.getChildren().addAll(pieceLayer, stackLayer);
            for (Alliance side : Alliance.values()) {
                Text label = hiveLabels[side.ordinal()];
                label.setFill(side == Alliance.RED ? Color.DARKRED : Color.DARKBLUE);
                label.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
                overlay.getChildren().add(label);
            }
            fieldPane.getChildren().add(overlay);
            panel = new BiobuzzPanel(this);
            controller.installBiobuzzPanel(panel);
        }
    }

    private void createSupports() {
        // Nominal foot bars at the outer frame envelope; the center stays open.
        for (double x : new double[]{-24.23, 24.23}) {
            Body foot = new Body();
            addStaticFixture(foot, Geometry.createRectangle(INCH, 38.95 * INCH), x, 0);
            Rectangle node = new Rectangle(px(x - 0.5), py(19.475), scale(1), scale(38.95));
            node.setFill(Color.color(0.25, 0.25, 0.25, 0.75));
            overlay.getChildren().add(node);
        }
        for (double[] flower : FLOWERS) {
            Body post = new Body();
            addStaticFixture(post, Geometry.createCircle(2.4 * INCH), flower[0], flower[1]);
            Circle node = new Circle(px(flower[0]), py(flower[1]), scale(2.4), Color.TRANSPARENT);
            node.setStroke(Color.DARKGREEN);
            node.setStrokeWidth(2);
            overlay.getChildren().add(node);
        }
    }

    private void addStaticFixture(Body body, org.dyn4j.geometry.Convex shape, double x, double y) {
        body.addFixture(shape).setFilter(Filters.WALL_FILTER);
        body.setMass(MassType.INFINITE);
        body.translate(x * INCH, y * INCH);
        body.setUserData("BIOBUZZ nominal floor support");
        supports.add(body);
        world.addBody(body);
    }

    /** Read-only access: callers must hold getPhysicsLock() while reading the model. */
    public BiobuzzModel getModel() { return model; }
    public Object getPhysicsLock() { return world; }
    public Group getOverlay() { return overlay; }
    public BiobuzzPanel getPanel() { return panel; }
    public String getActionStatus() { synchronized (world) { return actionStatus; } }
    public Map<Integer, Body> getGroundBodies() {
        synchronized (world) { return Collections.unmodifiableMap(new LinkedHashMap<>(ground)); }
    }
    public List<Body> getSupportBodies() {
        synchronized (world) { return Collections.unmodifiableList(new ArrayList<>(supports)); }
    }

    @Override public void resetGameElements() {
        synchronized (world) {
            clearFloor();
            model.reset();
            controller.resetBiobuzzRobotPose(model.alliance() == Alliance.RED ? -63 : 63, 0);
            resetViewState();
            reconcile();
            actionStatus = "Practice reset: 56 pieces; four preloaded. Match clock stopped.";
        }
        requestDisplay();
    }

    public void selectAlliance(Alliance side) {
        synchronized (world) {
            model.selectAlliance(side);
            resetGameElements();
            actionStatus = side + " selected; practice setup reset.";
        }
        requestDisplay();
    }

    private void resetViewState() {
        humanElapsed = restingSeconds = settlingSeconds = 0;
        lastTipCount = 0;
        pausedVelocities.clear();
    }

    private void clearFloor() {
        for (Body body : ground.values()) world.removeBody(body);
        ground.clear();
    }

    private void syncRobot() {
        if (model.paused()) return;
        double[] pose = controller.getBiobuzzRobotPose();
        if (pose != null) model.setRobot(pose[0], pose[1], pose[2], pose[3], pose[4]);
    }

    /** Import actual floor positions, including safe recovery of escaped pieces. */
    private void syncFloor() {
        if (isPhysicsFrozen()) return;
        for (Map.Entry<Integer, Body> entry : ground.entrySet()) {
            Body body = entry.getValue();
            model.syncGround(entry.getKey(), body.getWorldCenter().x / INCH, body.getWorldCenter().y / INCH);
        }
    }

    private Result action(Supplier<Result> action) {
        Result result;
        synchronized (world) {
            syncRobot();
            syncFloor();
            result = action.get();
            actionStatus = (result.ok ? "" : "Not allowed: ") + result.message;
            reconcile();
        }
        requestDisplay();
        return result;
    }

    public Result intake(int id) { return action(() -> model.intake(id)); }
    public Result intakeNearest() {
        return action(() -> {
            double[] pose = controller.getBiobuzzRobotPose();
            int nearest = -1;
            double distance = Double.POSITIVE_INFINITY;
            for (Piece piece : model.pieces()) {
                if (piece.place != Place.GROUND || piece.kind == (model.alliance() == Alliance.RED
                        ? Kind.BLUE_NECTAR : Kind.RED_NECTAR)) continue;
                double d = Math.hypot(piece.x - pose[0], piece.y - pose[1]);
                if (d < distance) { nearest = piece.id; distance = d; }
            }
            return model.intake(nearest);
        });
    }
    public Result drop() { return action(model::drop); }
    public Result launchHive(Alliance side) { return action(() -> model.launchHive(side)); }
    public Result depositFlower(int flower) { return action(() -> model.depositFlower(flower)); }
    public Result retrieveFlower(int flower) { return action(() -> model.retrieveFlower(flower)); }
    public Result humanLoad() { return action(model::humanLoad); }
    public Result preload() { return action(model::preload); }
    public Result refereeFoul(Alliance offender, boolean major) {
        return action(() -> model.refereeFoul(offender, major));
    }

    public Result startMatch() {
        return action(() -> {
            Result result = model.startMatch();
            if (result.ok) {
                controller.stopBiobuzzRobotMotion();
                clearFloor();
                resetViewState();
            }
            return result;
        });
    }

    public Result togglePause() {
        return action(() -> {
            Result result = model.togglePause();
            if (!result.ok) return result;
            if (model.paused()) {
                controller.stopBiobuzzRobotMotion();
                for (Body body : ground.values()) {
                    pausedVelocities.put(body, new double[]{body.getLinearVelocity().x,
                            body.getLinearVelocity().y, body.getAngularVelocity()});
                    body.setLinearVelocity(0, 0);
                    body.setAngularVelocity(0);
                    body.clearAccumulatedForce();
                    body.clearAccumulatedTorque();
                }
            } else {
                for (Map.Entry<Body, double[]> entry : pausedVelocities.entrySet()) {
                    double[] v = entry.getValue();
                    entry.getKey().setLinearVelocity(v[0], v[1]);
                    entry.getKey().setAngularVelocity(v[2]);
                }
                pausedVelocities.clear();
            }
            return result;
        });
    }

    public boolean isPhysicsFrozen() {
        synchronized (world) { return model.paused() || model.phase() == Phase.FINISHED; }
    }

    public boolean isRobotPowerAllowed() {
        synchronized (world) { return model.poweredActionsAllowed(); }
    }

    @Override public void updateGameElementState(double millis) {
        synchronized (world) {
            if (isPhysicsFrozen()) return;
            syncRobot();
            syncFloor();
            model.advance(millis / 1000.0);
            reconcile();
            if (humanAuto) {
                humanElapsed += millis / 1000.0;
                if (humanElapsed >= 1) {
                    humanElapsed = 0;
                    // Locked/empty reserves are normal automation state, not an explicit user error.
                    model.humanLoad();
                    reconcile();
                }
            }
            if (model.phase() == Phase.SETTLING) {
                settlingSeconds += millis / 1000.0;
                int tips = model.tips(Alliance.RED) + model.tips(Alliance.BLUE);
                boolean atRest = controller.isBiobuzzRobotAtRest();
                for (Body body : ground.values()) {
                    atRest &= body.getLinearVelocity().getMagnitude() < 0.01
                            && Math.abs(body.getAngularVelocity()) < 0.05;
                }
                restingSeconds = atRest && tips == lastTipCount ? restingSeconds + millis / 1000.0 : 0;
                // Allow the approximate 0.75s damper delay and newly spilled floor disks to settle.
                if (settlingSeconds >= 1 && restingSeconds >= 1) model.completeSettling();
                lastTipCount = tips;
            }
        }
    }

    /** Adds/removes only actual floor bodies; surviving bodies retain their momentum. */
    private void reconcile() {
        Map<Integer, Piece> floor = new LinkedHashMap<>();
        List<Piece> pieces = model.pieces();
        for (Piece piece : pieces) if (piece.place == Place.GROUND) floor.put(piece.id, piece);
        ground.entrySet().removeIf(entry -> {
            if (floor.containsKey(entry.getKey())) return false;
            world.removeBody(entry.getValue());
            pausedVelocities.remove(entry.getValue());
            return true;
        });
        for (Piece piece : floor.values()) {
            Body body = ground.get(piece.id);
            if (body == null) {
                body = new Body();
                BodyFixture fixture = body.addFixture(Geometry.createCircle(
                        (piece.kind == Kind.POLLEN ? 1.4 : 1.8) * INCH));
                fixture.setFriction(0.35);
                fixture.setRestitution(0.15);
                // Relative density is a floor-plane approximation, not measured ball mass.
                fixture.setDensity(1);
                body.setMass(MassType.NORMAL);
                body.setLinearDamping(2.5);
                body.setAngularDamping(2.5);
                body.setBullet(true);
                body.setUserData("BIOBUZZ piece " + piece.id);
                ground.put(piece.id, body);
                world.addBody(body);
                teleport(body, piece.x, piece.y);
            } else if (Math.hypot(body.getWorldCenter().x / INCH - piece.x,
                    body.getWorldCenter().y / INCH - piece.y) > 0.001) {
                teleport(body, piece.x, piece.y);
            }
        }
    }

    private static void teleport(Body body, double x, double y) {
        Transform pose = new Transform();
        pose.translate(x * INCH, y * INCH);
        body.setTransform(pose);
        body.setLinearVelocity(0, 0);
        body.setAngularVelocity(0);
        body.clearAccumulatedForce();
        body.clearAccumulatedTorque();
        body.setAtRest(false);
    }

    private void requestDisplay() {
        if (Platform.isFxApplicationThread()) updateDisplay();
        else Platform.runLater(this::updateDisplay);
    }

    @Override public void updateDisplay() {
        synchronized (world) {
            List<Piece> pieces = model.pieces();
            for (Piece piece : pieces) {
                Circle node = pieceNodes.get(piece.id);
                if (node == null) {
                    node = new Circle();
                    node.setId("biobuzz-piece-" + piece.id);
                    node.setStroke(Color.BLACK);
                    node.setStrokeWidth(0.6);
                    pieceNodes.put(piece.id, node);
                    pieceLayer.getChildren().add(node);
                }
                node.setFill(pieceColor(piece.kind));
                node.setRadius(scale(piece.kind == Kind.POLLEN ? 1.4 : 1.8));
                node.setCenterX(px(piece.x));
                node.setCenterY(py(piece.y));
                node.setVisible(piece.place == Place.GROUND);
            }
            stackLayer.getChildren().clear();
            for (int f = 0; f < 4; f++) {
                List<Piece> order = model.flowerPieces(f);
                double sx = FLOWERS[f][0] > 45 ? FLOWERS[f][0] - 25 : FLOWERS[f][0] + 3;
                double sy = Math.max(-62, Math.min(60, FLOWERS[f][1]));
                Text label = new Text(px(sx), py(sy), "F" + f + " bottom → top");
                label.setStyle("-fx-font-size: 9px; -fx-font-weight: bold;");
                stackLayer.getChildren().add(label);
                for (int n = 0; n < order.size(); n++) {
                    Piece piece = order.get(n);
                    double x = px(sx) + (n % 8) * scale(2.8);
                    double y = py(sy) + 10 + (n / 8) * scale(3.2);
                    Circle node = new Circle(x, y, scale(1.3), pieceColor(piece.kind));
                    node.setStroke(Color.BLACK);
                    node.setOpacity(piece.flowerScored ? 1 : 0.4);
                    stackLayer.getChildren().add(node);
                }
            }
            for (Alliance side : Alliance.values()) {
                int up = model.upwardCell(side);
                int count = 0;
                StringBuilder contents = new StringBuilder();
                for (Piece piece : pieces) {
                    if (piece.place == Place.CELL && piece.target == side.ordinal() && piece.cell == up) {
                        count++;
                        contents.append(piece.kind == Kind.POLLEN ? "P" : "N");
                    }
                }
                Text label = hiveLabels[side.ordinal()];
                label.setX(px(side == Alliance.RED ? -22 : 3));
                label.setY(py(up == 0 ? -23 : 27));
                label.setText(side + " ↑ " + (up == 0 ? "audience" : "rear") + "\n"
                        + count + " [" + contents + "]  tips " + model.tips(side));
            }
            if (panel != null) panel.refresh();
        }
    }

    private static Color pieceColor(Kind kind) {
        return kind == Kind.POLLEN ? Color.GOLD : kind == Kind.RED_NECTAR ? Color.CRIMSON : Color.DODGERBLUE;
    }
    private static double px(double inches) { return VirtualField.HALF_FIELD_WIDTH + scale(inches); }
    private static double py(double inches) { return VirtualField.HALF_FIELD_WIDTH - scale(inches); }
    private static double scale(double inches) { return inches * VirtualField.PIXELS_PER_INCH; }

    @Override public boolean hasHumanPlayer() { return true; }
    @Override public boolean isHumanPlayerAuto() { synchronized (world) { return humanAuto; } }
    @Override public void setHumanPlayerAuto(boolean selected) {
        synchronized (world) {
            humanAuto = selected;
            actionStatus = "Own nectar automation " + (selected ? "enabled (one attempt/second)." : "disabled.");
        }
        requestDisplay();
    }
    @Override public void requestHumanPlayerAction() { humanLoad(); }
    @Override public boolean isHumanPlayerActionRequested() { return false; }
    @Override public void updateHumanPlayerState(double millis) { humanLoad(); }
    @Override public void stopGameElements() {
        synchronized (world) {
            controller.stopBiobuzzRobotMotion();
            actionStatus = "OpMode stopped. Independent match clock continues; use Pause or Practice reset.";
        }
        requestDisplay();
    }
}
