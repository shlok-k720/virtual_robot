package virtual_robot.games.biobuzz;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import virtual_robot.config.Config;
import virtual_robot.games.Biobuzz;
import virtual_robot.games.biobuzz.BiobuzzModel.Alliance;
import virtual_robot.games.biobuzz.BiobuzzModel.Piece;
import virtual_robot.games.biobuzz.BiobuzzModel.Place;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/** JavaFX controls for explicit game actions; all rules remain in the game/model. */
public final class BiobuzzPanel extends ScrollPane {
    private final Biobuzz game;
    private final Label scoreboard = label("biobuzz-scoreboard", "");
    private final Label clock = label("biobuzz-clock", "");
    private final Label credits = label("biobuzz-human-credits", "");
    private final Label tips = label("biobuzz-tips", "");
    private final Label rankings = label("biobuzz-rankings", "");
    private final Label actionStatus = label("biobuzz-action-status", "");
    private final Label modelStatus = label("biobuzz-model-status", "");
    private final Label held = label("biobuzz-held-inventory", "");
    private final Label flowerContents = label("biobuzz-flower-inventory", "");
    private final Label upward = label("biobuzz-upward-cell", "");
    private final ComboBox<Alliance> hive = allianceSelector("biobuzz-hive");
    private final ComboBox<String> flower = new ComboBox<>();
    private final CheckBox humanAuto = new CheckBox("Automate own human player");
    private final Button pause;

    public BiobuzzPanel(Biobuzz game) {
        this.game = game;
        setId("biobuzz-panel");
        setFitToWidth(true);
        setPrefWidth(245);
        setMinWidth(0);
        setPrefViewportHeight(Config.FIELD_WIDTH - 10);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        VBox content = new VBox(7);
        content.setPadding(new Insets(10));
        content.setMinWidth(0);
        setContent(content);

        Label title = label("biobuzz-season-title", "BIOBUZZ — Season Controls");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        scoreboard.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        actionStatus.setStyle("-fx-text-fill: #9b1c1c; -fx-font-weight: bold;"
                + " -fx-background-color: #fff3df; -fx-padding: 6;");

        ComboBox<Alliance> alliance = allianceSelector("biobuzz-alliance");
        synchronized (game.getPhysicsLock()) {
            Alliance initial = game.getModel().alliance();
            alliance.setValue(initial);
            hive.setValue(initial);
        }
        // Install after initialization, and never change this selection during refresh.
        alliance.setOnAction(event -> act(() -> game.selectAlliance(alliance.getValue())));
        pause = button("biobuzz-pause", "Pause match", game::togglePause);

        content.getChildren().addAll(title, scoreboard, clock, tips, rankings, credits,
                label("biobuzz-action-heading", "Last explicit action"), actionStatus, modelStatus,
                new Separator(), label("biobuzz-alliance-label", "Robot alliance (changing resets setup)"),
                alliance,
                button("biobuzz-reset", "Practice reset", game::resetGameElements),
                button("biobuzz-start", "Start official match", game::startMatch),
                pause, new Separator(),
                label("biobuzz-held-label", "Held inventory — FIFO actions"),
                held,
                label("biobuzz-fifo-note", "Oldest held piece acts first. Contents are listed by piece ID, not FIFO position."),
                button("biobuzz-intake", "Intake nearest", game::intakeNearest),
                button("biobuzz-drop", "Drop oldest (FIFO)", game::drop),
                label("biobuzz-hive-label", "Target hive"), hive, upward,
                button("biobuzz-launch", "Launch oldest into upward cell",
                        () -> game.launchHive(hive.getValue())),
                new Separator(), label("biobuzz-flower-label", "Target flower"));

        flower.setId("biobuzz-flower");
        flower.getItems().addAll("0 — left", "1 — rear", "2 — right", "3 — audience");
        flower.getSelectionModel().selectFirst();
        flower.setMinWidth(0);
        flower.setMaxWidth(Double.MAX_VALUE);
        flower.setOnAction(event -> refresh());
        hive.setOnAction(event -> refresh());
        content.getChildren().addAll(flower,
                button("biobuzz-deposit", "Deposit oldest through top",
                        () -> game.depositFlower(flower.getSelectionModel().getSelectedIndex())),
                button("biobuzz-retrieve", "Retrieve bottom pollen",
                        () -> game.retrieveFlower(flower.getSelectionModel().getSelectedIndex())),
                flowerContents,
                label("biobuzz-stack-note",
                        "Ordered bottom → top. Faded field dots / unmarked entries are outside the nominal scoring band."),
                new Separator(),
                button("biobuzz-human-load", "Human load — own alliance", game::humanLoad));

        humanAuto.setId("biobuzz-human-auto");
        humanAuto.setWrapText(true);
        humanAuto.setMinWidth(0);
        humanAuto.setOnAction(event -> act(() -> game.setHumanPlayerAuto(humanAuto.isSelected())));
        content.getChildren().addAll(humanAuto,
                button("biobuzz-preload", "Recover practice preload", game::preload),
                new Separator(), label("biobuzz-referee-label", "Referee — offending alliance"));

        ComboBox<Alliance> offender = allianceSelector("biobuzz-offender");
        offender.setValue(alliance.getValue());
        content.getChildren().addAll(offender,
                label("biobuzz-foul-note", "Points go to the offender's OPPONENT."),
                button("biobuzz-minor-foul", "Minor foul: opponent +5",
                        () -> game.refereeFoul(offender.getValue(), false)),
                button("biobuzz-major-foul", "Major foul: opponent +20",
                        () -> game.refereeFoul(offender.getValue(), true)),
                new Separator(),
                label("biobuzz-instructions",
                        "Independent match clock; INIT/START an OpMode to drive. "
                                + "Practice has no clock. AUTO is for autonomous OpModes, not driver input. "
                                + "Shots/top/bottom transfers are explicit, range checked. FIFO inventory."),
                label("biobuzz-approximations",
                        "Approximations: normalized tip/load/delay, nominal support shapes, "
                                + "floor-plane disks; no 3D flight."));
        refresh();
    }

    /** Called on the JavaFX application thread. Never mutates the model. */
    public void refresh() {
        synchronized (game.getPhysicsLock()) {
            BiobuzzModel model = game.getModel();
            scoreboard.setText("RED " + model.score(Alliance.RED)
                    + "   |   BLUE " + model.score(Alliance.BLUE));
            tips.setText("Tips RED " + model.tips(Alliance.RED) + " (AUTO "
                    + model.autoTips(Alliance.RED) + " / TELEOP " + model.teleopTips(Alliance.RED)
                    + ")\nTips BLUE " + model.tips(Alliance.BLUE) + " (AUTO "
                    + model.autoTips(Alliance.BLUE) + " / TELEOP " + model.teleopTips(Alliance.BLUE) + ")");
            rankings.setText((model.phase() == BiobuzzModel.Phase.FINISHED ? "Final" : "Provisional")
                    + " RP — RED " + model.ranking(Alliance.RED).total
                    + " | BLUE " + model.ranking(Alliance.BLUE).total
                    + "\nSingle robot only; no absent-robot movement is assumed.");
            String time = model.phase() == BiobuzzModel.Phase.PRACTICE
                    ? "no clock" : String.format(Locale.ROOT, "%.1f s", model.remainingSeconds());
            clock.setText(model.phase() + " — " + time + (model.paused() ? " — PAUSED" : ""));
            credits.setText("Human credits — RED " + model.unlocked(Alliance.RED)
                    + " | BLUE " + model.unlocked(Alliance.BLUE)
                    + "\nRobot alliance: " + model.alliance());
            String status = game.getActionStatus();
            actionStatus.setText(status == null || status.isBlank() ? "No explicit action yet." : status);
            modelStatus.setText("Model: " + model.status());
            pause.setText(model.paused() ? "Resume match" : "Pause match");
            humanAuto.setSelected(game.isHumanPlayerAuto());
            Alliance target = hive.getValue();
            if (target == null) {
                upward.setText("Select a target hive.");
            } else {
                int cell = model.upwardCell(target);
                upward.setText(target + " upward cell: " + cell
                        + (cell == 0 ? " (audience)" : " (rear)"));
            }
            List<Piece> pieces = model.pieces();
            held.setText(model.heldCount() + " held: " + contents(pieces, Place.HELD, -1));
            int selectedFlower = flower.getSelectionModel().getSelectedIndex();
            flowerContents.setText(selectedFlower < 0 ? "Select a flower."
                    : "Flower " + selectedFlower + " bottom → top: "
                    + stackContents(model.flowerPieces(selectedFlower))
                    + "\nOwner: " + model.flowerOwner(selectedFlower)
                    + "; bottom bonus: " + model.flowerBottomOwner(selectedFlower));
        }
    }

    private void act(Runnable action) {
        action.run();
        refresh();
    }

    private Button button(String id, String text, Runnable action) {
        Button button = new Button(text);
        button.setId(id);
        button.setWrapText(true);
        button.setMinWidth(0);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> act(action));
        return button;
    }

    private static Label label(String id, String text) {
        Label label = new Label(text);
        label.setId(id);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static ComboBox<Alliance> allianceSelector(String id) {
        ComboBox<Alliance> selector = new ComboBox<>();
        selector.setId(id);
        selector.getItems().setAll(Alliance.values());
        selector.setMinWidth(0);
        selector.setMaxWidth(Double.MAX_VALUE);
        return selector;
    }

    private static String contents(List<Piece> pieces, Place place, int target) {
        StringJoiner text = new StringJoiner(", ");
        pieces.stream()
                .filter(piece -> piece.place == place && (target < 0 || piece.target == target))
                .sorted((left, right) -> Integer.compare(left.id, right.id))
                .forEach(piece -> text.add("#" + piece.id + " " + piece.kind.name().replace('_', ' ')));
        return text.length() == 0 ? "empty" : text.toString();
    }

    private static String stackContents(List<Piece> stack) {
        StringJoiner text = new StringJoiner(" → ");
        for (Piece piece : stack) text.add("#" + piece.id + " " + piece.kind.name().replace('_', ' ')
                + (piece.flowerScored ? " [scored]" : ""));
        return text.length() == 0 ? "empty" : text.toString();
    }
}
