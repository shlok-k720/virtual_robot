package virtual_robot.games.biobuzz;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Circle;
import org.dyn4j.dynamics.Body;
import org.dyn4j.geometry.Rectangle;
import virtual_robot.config.Config;
import virtual_robot.controller.VirtualRobotController;
import virtual_robot.games.Biobuzz;
import virtual_robot.games.biobuzz.BiobuzzModel.Alliance;
import virtual_robot.games.biobuzz.BiobuzzModel.Kind;
import virtual_robot.games.biobuzz.BiobuzzModel.Phase;
import virtual_robot.games.biobuzz.BiobuzzModel.Piece;
import virtual_robot.games.biobuzz.BiobuzzModel.Place;
import virtual_robot.games.biobuzz.BiobuzzModel.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plain-main JavaFX/FXML smoke test; requires the application's normal runtime classpath
 * and a graphical session. No Stage, OpMode, sleeps, or test-only simulation hooks.
 */
public final class BiobuzzIntegrationTest {
    private static final double INCH = 0.0254;
    private static final double EPS = 1e-8;
    private static int passed;

    private BiobuzzIntegrationTest() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            FXMLLoader loader = new FXMLLoader(BiobuzzIntegrationTest.class.getResource(
                    "/virtual_robot/controller/virtual_robot.fxml"));
            VirtualRobotController controller = null;
            try {
                Platform.setImplicitExit(false);
                Parent root = loader.load();
                controller = loader.getController();
                check(controller != null, "FXML controller missing");
                // Await executor termination before taking the world lock or resetting fixtures.
                controller.shutdownSimulation();
                controller.setConfig(null);
                check(Config.GAME instanceof Biobuzz, "Config.GAME must be Biobuzz");
                Biobuzz game = (Biobuzz) Config.GAME;
                game.resetGameElements();
                new Scene(root);
                root.applyCss();
                root.layout();
                run("startup, panel and shapes", () -> testStartup(root, game));
                VirtualRobotController loadedController = controller;
                run("physical drop, intake and preload", () -> testTransfers(loadedController, game));
                run("flower transfers", () -> testFlowers(loadedController, game));
                run("hive spill and escaped pieces", () -> testSpill(loadedController, game));
                run("official clock and pause", () -> testPause(loadedController, game));
                run("reset body lifecycle", () -> testReset(loadedController, game));
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                try {
                    if (controller == null) controller = loader.getController();
                    if (controller != null) {
                        controller.shutdownSimulation();
                        if (Config.GAME instanceof Biobuzz) Config.GAME.resetGameElements();
                    }
                } catch (Throwable cleanupFailure) {
                    if (failure.get() == null) failure.set(cleanupFailure);
                    else failure.get().addSuppressed(cleanupFailure);
                } finally {
                    finished.countDown();
                }
            }
        });
        try {
            check(finished.await(60, TimeUnit.SECONDS), "JavaFX smoke test timed out");
            if (failure.get() != null) throw new AssertionError("Biobuzz integration failed", failure.get());
            System.out.println("BiobuzzIntegrationTest: " + passed + " tests passed");
        } finally {
            Platform.exit();
        }
    }

    private static void testStartup(Parent root, Biobuzz game) {
        check(Platform.isFxApplicationThread(), "Assertions must run on JavaFX thread");
        synchronized (game.getPhysicsLock()) {
            check(root.lookup("#biobuzz-panel") == game.getPanel(), "Season panel not attached to FXML");
            for (String id : new String[] {"biobuzz-scoreboard", "biobuzz-clock",
                    "biobuzz-action-status", "biobuzz-held-inventory", "biobuzz-start",
                    "biobuzz-intake", "biobuzz-drop", "biobuzz-reset", "biobuzz-pause"}) {
                check(game.getPanel().lookup("#" + id) != null, "Missing panel control: " + id);
            }
            BiobuzzModel model = game.getModel();
            check(model.pieces().size() == 56, "Expected 56 pieces");
            check(count(game, Place.GROUND) == 20, "Expected 20 ground allocations");
            check(count(game, Place.HELD) == 4, "Expected four held preloads");
            check(count(game, Place.FLOWER) == 16, "Expected 16 flower pollen");
            check(count(game, Place.CELL) == 6, "Expected six hive nectar");
            check(count(game, Place.RESERVE) == 10, "Expected ten reserve nectar");
            check(game.getSupportBodies().size() == 6, "Only two narrow supports and four flower posts");
            int posts = 0;
            int rails = 0;
            for (Body body : game.getSupportBodies()) {
                check(body.getMass().getType() == org.dyn4j.geometry.MassType.INFINITE,
                        "Support must be static");
                check(body.getFixtureCount() == 1, "Support must not fill elevated hive geometry");
                Object shape = body.getFixture(0).getShape();
                if (shape instanceof org.dyn4j.geometry.Circle) {
                    posts++;
                    close(((org.dyn4j.geometry.Circle) shape).getRadius(), 2.4 * INCH,
                            "Nominal flower post radius");
                } else {
                    check(shape instanceof Rectangle, "Unexpected support shape");
                    rails++;
                    Rectangle rail = (Rectangle) shape;
                    check(rail.getWidth() < 4 * INCH && rail.getHeight() > 38 * INCH,
                            "Hive support must be narrow, not a filled center rectangle");
                    close(Math.abs(body.getWorldCenter().x), 24.23 * INCH, "Hive support x");
                }
            }
            check(posts == 4 && rails == 2, "Unexpected support geometry");
            assertGroundAndRendering(game);
        }
    }

    private static void testTransfers(VirtualRobotController controller, Biobuzz game) {
        game.selectAlliance(Alliance.RED);
        moveRobot(controller, -45, 0);
        synchronized (controller.getWorld()) {
            Piece held = first(game, Place.HELD, Kind.POLLEN);
            List<Node> nodes = new ArrayList<>(pieces(game).getChildren());
            success(game.drop());
            Body dropped = game.getGroundBodies().get(held.id);
            check(dropped != null, "Drop must create a physical body");
            check(controller.getWorld().containsBody(dropped), "Dropped body missing from world");
            success(game.intake(held.id));
            check(!controller.getWorld().containsBody(dropped), "Intake must remove physical body");
            check(piece(game, held.id).place == Place.HELD, "Intake did not update inventory");
            success(game.drop());
            success(game.preload());
            check(game.getModel().heldCount() == 4, "Preload must recover dropped allocation");
            check(!game.intake(first(game, Place.GROUND, Kind.POLLEN).id).ok,
                    "Full robot must reject intake");
            success(game.drop());
            success(game.intakeNearest());
            check(game.getModel().heldCount() == 4, "Nearest intake did not recover nearby drop");
            check(nodes.equals(pieces(game).getChildren()), "Transfers must reuse piece nodes");
            assertGroundAndRendering(game);
        }
    }

    private static void testFlowers(VirtualRobotController controller, Biobuzz game) {
        game.selectAlliance(Alliance.RED);
        moveRobot(controller, -54, -24);
        synchronized (controller.getWorld()) {
            int bottom = game.getModel().flowerPieces(0).get(0).id;
            int top = first(game, Place.HELD, Kind.POLLEN).id;
            success(game.depositFlower(0));
            check(piece(game, top).place == Place.FLOWER, "Top deposit must enter flower");
            success(game.retrieveFlower(0));
            check(piece(game, bottom).place == Place.HELD, "Retrieval must take original bottom pollen");
            check(piece(game, top).place == Place.FLOWER, "Retrieval must not take new top pollen");
            assertGroundAndRendering(game);
        }
    }

    private static void testSpill(VirtualRobotController controller, Biobuzz game) {
        game.selectAlliance(Alliance.RED);
        moveRobot(controller, -12.75, -35);
        synchronized (controller.getWorld()) {
            for (int i = 0; i < 3; i++) success(game.launchHive(Alliance.RED));
            check(game.getGroundBodies().size() == 20, "Launch must not immediately spill");
            game.updateGameElementState(800);
            check(game.getModel().tips(Alliance.RED) == 1, "Stable load must tip hive");
            check(game.getGroundBodies().size() == 26, "Tip must create six floor bodies");
            assertGroundAndRendering(game);
            Piece pollen = first(game, Place.GROUND, Kind.POLLEN);
            Body pollenBody = game.getGroundBodies().get(pollen.id);
            teleport(pollenBody, 90, 85);
            game.updateGameElementState(0);
            Piece returned = piece(game, pollen.id);
            check(returned.place == Place.GROUND && Math.abs(returned.x) <= 72
                    && Math.abs(returned.y) <= 72, "Escaped pollen must return with same ID");
            close(pollenBody.getWorldCenter().x, returned.x * INCH, "Returned pollen body x");
            close(pollenBody.getWorldCenter().y, returned.y * INCH, "Returned pollen body y");
            Piece nectar = first(game, Place.GROUND, Kind.RED_NECTAR);
            Body nectarBody = game.getGroundBodies().get(nectar.id);
            teleport(nectarBody, -90, 0);
            game.updateGameElementState(0);
            check(piece(game, nectar.id).place == Place.RESERVE, "Escaped nectar must return to reserve");
            check(!controller.getWorld().containsBody(nectarBody), "Reserve nectar must lose collider");
            // Exercise the real physics engine, then reconcile model coordinates under the same lock.
            pollenBody.setLinearVelocity(0.1, 0);
            controller.getWorld().updatev(0.02);
            game.updateGameElementState(20);
            close(piece(game, pollen.id).x * INCH, pollenBody.getWorldCenter().x, "Physics sync x");
            assertGroundAndRendering(game);
        }
    }

    private static void testPause(VirtualRobotController controller, Biobuzz game) {
        game.selectAlliance(Alliance.RED);
        moveRobot(controller, -63, 0);
        synchronized (controller.getWorld()) {
            success(game.startMatch());
            check(game.getModel().phase() == Phase.AUTO, "Official start must enter AUTO");
            game.updateGameElementState(1000);
            close(game.getModel().remainingSeconds(), 29, "Milliseconds must advance clock in seconds");
            ((Button) game.getPanel().lookup("#biobuzz-pause")).fire();
            check(game.isPhysicsFrozen() && game.getModel().paused(), "Pause must freeze physics");
            Map<Integer, double[]> positions = new HashMap<>();
            game.getGroundBodies().forEach((id, body) -> positions.put(id,
                    new double[] {body.getWorldCenter().x, body.getWorldCenter().y}));
            double clock = game.getModel().remainingSeconds();
            game.updateGameElementState(5000);
            check(!game.drop().ok, "Paused actions must be rejected");
            close(game.getModel().remainingSeconds(), clock, "Paused clock changed");
            game.getGroundBodies().forEach((id, body) -> {
                close(body.getWorldCenter().x, positions.get(id)[0], "Paused body x changed");
                close(body.getWorldCenter().y, positions.get(id)[1], "Paused body y changed");
            });
            game.updateDisplay();
            check(((Label) game.getPanel().lookup("#biobuzz-clock")).getText().contains("PAUSED"),
                    "Panel must show paused match");
            ((Button) game.getPanel().lookup("#biobuzz-pause")).fire();
            check(!game.isPhysicsFrozen(), "Resume must unfreeze physics");
            game.updateGameElementState(250);
            close(game.getModel().remainingSeconds(), clock - 0.25, "Resumed clock did not advance");
        }
    }

    private static void testReset(VirtualRobotController controller, Biobuzz game) {
        synchronized (controller.getWorld()) {
            List<Body> old = new ArrayList<>(game.getGroundBodies().values());
            int worldCount = controller.getWorld().getBodyCount();
            ((Button) game.getPanel().lookup("#biobuzz-reset")).fire();
            for (Body body : old) check(!controller.getWorld().containsBody(body), "Reset leaked old body");
            check(controller.getWorld().getBodyCount() == worldCount, "Reset leaked or lost world bodies");
            check(game.getModel().phase() == Phase.PRACTICE, "Reset must restore practice");
            check(game.getGroundBodies().size() == 20, "Reset ground allocation changed");
            check(game.getModel().heldCount() == 4, "Reset must restore preloads");
            assertGroundAndRendering(game);
            game.selectAlliance(Alliance.BLUE);
            check(game.getModel().alliance() == Alliance.BLUE, "Alliance selection did not reset setup");
            success(game.startMatch());
            assertGroundAndRendering(game);
        }
    }

    private static void assertGroundAndRendering(Biobuzz game) {
        game.updateDisplay();
        Map<Integer, Body> ground = game.getGroundBodies();
        check(ground.size() == count(game, Place.GROUND), "Ground map/model disagreement");
        Group group = pieces(game);
        check(group.getChildren().size() == 56, "All 56 render nodes must survive transfers/reset");
        long visible = group.getChildren().stream().filter(Node::isVisible).count();
        check(visible == ground.size(), "Only ground piece circles should be visible");
        for (Node node : group.getChildren()) check(node instanceof Circle, "Piece node must be a circle");
        for (Piece piece : game.getModel().pieces()) {
            Body body = ground.get(piece.id);
            check((body != null) == (piece.place == Place.GROUND), "Wrong collider for piece " + piece.id);
            if (body == null) continue;
            check(body.getFixtureCount() == 1, "Piece must have one fixture");
            check(body.getFixture(0).getShape() instanceof org.dyn4j.geometry.Circle,
                    "Floor pieces must use disk colliders");
            close(((org.dyn4j.geometry.Circle) body.getFixture(0).getShape()).getRadius(),
                    piece.kind == Kind.POLLEN ? 0.03556 : 0.04572, "Piece radius");
            close(body.getWorldCenter().x, piece.x * INCH, "Ground body/model x");
            close(body.getWorldCenter().y, piece.y * INCH, "Ground body/model y");
        }
    }

    private static Group pieces(Biobuzz game) {
        Node node = game.getOverlay().lookup("#biobuzz-pieces");
        check(node instanceof Group, "Missing persistent piece render group");
        return (Group) node;
    }

    private static void moveRobot(VirtualRobotController controller, double x, double y) {
        Pane pane = controller.getFieldPane();
        double px = (x + 72) * Config.FIELD_WIDTH / 144;
        double py = (72 - y) * Config.FIELD_WIDTH / 144;
        synchronized (controller.getWorld()) {
            check(((Biobuzz) Config.GAME).getPhysicsLock() == controller.getWorld(),
                    "Game must synchronize on controller world");
            pane.fireEvent(new MouseEvent(pane, pane, MouseEvent.MOUSE_PRESSED,
                    px, py, px, py, MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false,
                    false, false, true, new PickResult(pane, new Point3D(px, py, 0), 0)));
        }
    }

    private static void teleport(Body body, double x, double y) {
        body.translate(x * INCH - body.getWorldCenter().x, y * INCH - body.getWorldCenter().y);
        body.setLinearVelocity(0, 0);
        body.setAngularVelocity(0);
    }

    private static long count(Biobuzz game, Place place) {
        return game.getModel().pieces().stream().filter(piece -> piece.place == place).count();
    }

    private static Piece first(Biobuzz game, Place place, Kind kind) {
        return game.getModel().pieces().stream()
                .filter(piece -> piece.place == place && piece.kind == kind)
                .findFirst().orElseThrow(() -> new AssertionError("Missing " + place + " " + kind));
    }

    private static Piece piece(Biobuzz game, int id) {
        return game.getModel().pieces().stream().filter(piece -> piece.id == id)
                .findFirst().orElseThrow(() -> new AssertionError("Missing piece " + id));
    }

    private static void success(Result result) {
        check(result.ok, result.message);
    }

    private static void close(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < EPS, message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void run(String name, Runnable test) {
        try {
            test.run();
            passed++;
        } catch (AssertionError | RuntimeException failure) {
            throw new AssertionError(name, failure);
        }
    }
}
