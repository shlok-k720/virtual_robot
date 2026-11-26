package virtual_robot.dashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.transform.Affine;
import virtual_robot.controller.VirtualRobotApplication;
import virtual_robot.controller.VirtualRobotController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Desktop consumer of DashboardCore ReceiveTelemetry JSON, without the web field background.
 * Images are unsupported (no asset server or network access). Fonts support px/pt sizes,
 * optional normal/bold/italic/oblique/numeric weight, and a single installed font family.
 * Telemetry is displayed as plain text, not HTML. Splines use 250 samples; grids require
 * 2 to 10,000 ticks per axis. Colors use JavaFX's CSS color syntax.
 */
public final class DashboardView implements Consumer<JsonObject>, AutoCloseable {
    private static final Pattern FONT = Pattern.compile(
            "^((?:(?:normal|bold|italic|oblique|[1-9]00)\\s+)*)"
                    + "(\\d+(?:\\.\\d+)?)(px|pt)\\s+([^,]+)$",
            Pattern.CASE_INSENSITIVE);
    private final Object lock = new Object();
    private final Map<String, String> data = new LinkedHashMap<>();
    private List<String> log = new ArrayList<>();
    private JsonArray overlay = new JsonArray();
    private VirtualRobotController controller;
    private boolean scheduled;
    private boolean closed;

    // These nodes and listeners are created/used only on the FX application thread.
    private Group group;
    private Canvas canvas;
    private Pane pane;
    private final InvalidationListener sizeListener = ignored -> requestRefresh();
    private final ListChangeListener<Node> childrenListener = change -> {
        if (group != null && pane != null && !pane.getChildren().contains(group)) {
            requestRefresh();
        }
    };

    public DashboardView() {
    }

    @Override
    public void accept(JsonObject message) {
        VirtualRobotController handle = VirtualRobotApplication.getControllerHandle();
        if (handle == null) {
            throw new IllegalStateException("DashboardView requires an initialized robot controller");
        }
        Objects.requireNonNull(message, "message");
        synchronized (lock) {
            if (closed) {
                return;
            }
            // Copy and validate the entire batch before mutating cumulative state.
            JsonArray packets = array(message, "telemetry").deepCopy();
            for (JsonElement element : packets) {
                JsonObject packet = object(element, "telemetry packet");
                number(packet, "timestamp");
                for (Map.Entry<String, JsonElement> entry : object(
                        required(packet, "data"), "data").entrySet()) {
                    string(entry.getValue(), "data." + entry.getKey());
                }
                for (JsonElement line : array(packet, "log")) {
                    string(line, "log line");
                }
                JsonArray ops = array(object(required(packet, "fieldOverlay"), "fieldOverlay"), "ops");
                for (JsonElement op : ops) {
                    validateOp(object(op, "canvas operation"));
                }
            }
            controller = handle;
            if (packets.size() == 0) {
                data.clear();
                log.clear();
                overlay = new JsonArray();
            }
            for (JsonElement element : packets) {
                JsonObject packet = element.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : packet.getAsJsonObject("data").entrySet()) {
                    data.put(entry.getKey(), entry.getValue().getAsString());
                }
                JsonArray lines = packet.getAsJsonArray("log");
                log = new ArrayList<>();
                for (JsonElement line : lines) {
                    log.add(line.getAsString());
                }
                JsonArray ops = packet.getAsJsonObject("fieldOverlay").getAsJsonArray("ops");
                if (ops.size() != 0) {
                    overlay = ops;
                }
            }
            scheduleLocked();
        }
    }

    private void requestRefresh() {
        synchronized (lock) {
            if (!closed && controller != null) {
                scheduleLocked();
            }
        }
    }

    private void scheduleLocked() {
        if (!scheduled) {
            scheduled = true;
            try {
                Platform.runLater(this::refresh);
            } catch (RuntimeException e) {
                scheduled = false;
                throw e;
            }
        }
    }

    private void refresh() {
        synchronized (lock) {
            scheduled = false;
            if (closed) {
                return;
            }
            Pane field = controller.getFieldPane();
            if (field == null) {
                throw new IllegalStateException("Robot controller field pane is not initialized");
            }
            if (canvas == null) {
                canvas = new Canvas();
                canvas.setManaged(false);
                canvas.setMouseTransparent(true);
                group = new Group(canvas);
                group.setManaged(false);
                group.setMouseTransparent(true);
                group.setPickOnBounds(false);
            }
            if (pane != field) {
                detach();
                pane = field;
                pane.widthProperty().addListener(sizeListener);
                pane.heightProperty().addListener(sizeListener);
                pane.getChildren().addListener(childrenListener);
            }
            if (!pane.getChildren().contains(group)) {
                pane.getChildren().add(group);
            }
            StringBuilder text = new StringBuilder();
            data.forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
            if (!data.isEmpty() && !log.isEmpty()) {
                text.append('\n');
            }
            log.forEach(line -> text.append(line).append('\n'));
            controller.updateTelemetryDisplay(text.toString());
            if (!closed) {
                render();
            }
        }
    }

    private void detach() {
        if (pane != null) {
            pane.widthProperty().removeListener(sizeListener);
            pane.heightProperty().removeListener(sizeListener);
            pane.getChildren().removeListener(childrenListener);
            pane.getChildren().remove(group);
            pane = null;
        }
    }

    @Override
    public void close() {
        boolean hasNodes;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            scheduled = false;
            data.clear();
            log.clear();
            overlay = new JsonArray();
            controller = null;
            hasNodes = group != null;
        }
        if (!hasNodes) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            detach();
        } else {
            Platform.runLater(this::detach);
        }
    }

    private void render() {
        double width = pane.getWidth() > 0 ? pane.getWidth() : pane.getPrefWidth();
        double height = pane.getHeight() > 0 ? pane.getHeight() : pane.getPrefHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        canvas.setWidth(width);
        canvas.setHeight(height);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.save();
        try {
            gc.setTransform(new Affine());
            gc.clearRect(0, 0, width, height);
            gc.setGlobalAlpha(1);
            gc.setStroke(Color.BLACK);
            gc.setFill(Color.BLACK);
            gc.setLineWidth(1);
            gc.setLineCap(StrokeLineCap.BUTT);
            double x = 0, y = 0, rotation = 0, scaleX = 1, scaleY = 1;
            setWorldTransform(gc, width, height, x, y, rotation, scaleX, scaleY);
            for (JsonElement element : overlay) {
                JsonObject op = element.getAsJsonObject();
                String type = string(op, "type");
                switch (type) {
                    // Core's setters replace individual transform components, rather than concatenate.
                    case "translate":
                        x = number(op, "x");
                        y = number(op, "y");
                        break;
                    case "rotation":
                        rotation = number(op, "rotation");
                        break;
                    case "scale":
                        scaleX = number(op, "scaleX");
                        scaleY = number(op, "scaleY");
                        break;
                    case "stroke":
                        gc.setStroke(Color.web(string(op, "color")));
                        break;
                    case "fill":
                        gc.setFill(Color.web(string(op, "color")));
                        break;
                    case "strokeWidth":
                        gc.setLineWidth(number(op, "width"));
                        break;
                    case "alpha":
                        gc.setGlobalAlpha(number(op, "alpha"));
                        break;
                    case "circle":
                        double radius = number(op, "radius");
                        double left = number(op, "x") - radius;
                        double top = number(op, "y") - radius;
                        if (bool(op, "stroke")) {
                            gc.strokeOval(left, top, radius * 2, radius * 2);
                        } else {
                            gc.fillOval(left, top, radius * 2, radius * 2);
                        }
                        break;
                    case "polygon":
                    case "polyline":
                        drawPoints(gc, op, type);
                        break;
                    case "spline":
                        drawSpline(gc, op);
                        break;
                    case "text":
                    case "grid":
                        drawFramed(gc, op, type, width, height);
                        break;
                    default:
                        throw new UnsupportedOperationException("Unsupported canvas operation: " + type);
                }
                if (type.equals("translate") || type.equals("rotation") || type.equals("scale")) {
                    setWorldTransform(gc, width, height, x, y, rotation, scaleX, scaleY);
                }
            }
        } finally {
            gc.restore();
        }
    }

    private static void setWorldTransform(GraphicsContext gc, double width, double height,
                                          double x, double y, double rotation,
                                          double scaleX, double scaleY) {
        gc.setTransform(width / 144, 0, 0, -height / 144, width / 2, height / 2);
        gc.translate(x, y);
        gc.rotate(Math.toDegrees(rotation));
        gc.scale(scaleX, scaleY);
    }

    private static void drawPoints(GraphicsContext gc, JsonObject op, String type) {
        double[] xs = coordinates(op, "xPoints");
        double[] ys = coordinates(op, "yPoints");
        if (xs.length == 0) {
            return;
        }
        if (type.equals("polyline")) {
            gc.strokePolyline(xs, ys, xs.length);
        } else if (bool(op, "stroke")) {
            gc.strokePolygon(xs, ys, xs.length);
        } else {
            gc.fillPolygon(xs, ys, xs.length);
        }
    }

    private static void drawSpline(GraphicsContext gc, JsonObject op) {
        double[] xs = coefficients(op, "x");
        double[] ys = coefficients(op, "y");
        gc.beginPath();
        gc.moveTo(xs[5], ys[5]);
        for (int i = 1; i <= 250; i++) {
            double t = i / 250.0;
            gc.lineTo(polynomial(xs, t), polynomial(ys, t));
        }
        gc.stroke();
    }

    private static double[] coefficients(JsonObject op, String axis) {
        return new double[]{number(op, "a" + axis), number(op, "b" + axis),
                number(op, "c" + axis), number(op, "d" + axis),
                number(op, "e" + axis), number(op, "f" + axis)};
    }

    private static double polynomial(double[] coefficients, double t) {
        double result = coefficients[0];
        for (int i = 1; i < coefficients.length; i++) {
            result = result * t + coefficients[i];
        }
        return result;
    }

    private static void drawFramed(GraphicsContext gc, JsonObject op, String type,
                                   double width, double height) {
        gc.save();
        try {
            boolean page = bool(op, "usePageFrame");
            if (page) {
                // Page frame uses inches from the top-left, with Y down.
                gc.setTransform(width / 144, 0, 0, height / 144, 0, 0);
            }
            gc.translate(number(op, "x"), number(op, "y"));
            if (!page) {
                gc.scale(1, -1);
            }
            gc.rotate(Math.toDegrees(number(op, "theta")));
            if (type.equals("text")) {
                gc.setFont(font(string(op, "font")));
                if (bool(op, "stroke")) {
                    gc.strokeText(string(op, "text"), 0, 0);
                } else {
                    gc.fillText(string(op, "text"), 0, 0);
                }
            } else {
                double left = -number(op, "pivotX"), top = -number(op, "pivotY");
                double gridWidth = number(op, "width"), gridHeight = number(op, "height");
                int ticksX = ticks(op, "numTicksX"), ticksY = ticks(op, "numTicksY");
                Affine transform = gc.getTransform();
                double pixelScaleX = Math.hypot(transform.getMxx(), transform.getMyx());
                double pixelScaleY = Math.hypot(transform.getMxy(), transform.getMyy());
                if (pixelScaleX == 0 || pixelScaleY == 0) {
                    return;
                }
                gc.setStroke(Color.rgb(120, 120, 120));
                gc.setLineWidth(1 / pixelScaleX);
                for (int i = 0; i < ticksX; i++) {
                    double lineX = left + gridWidth * i / (ticksX - 1);
                    gc.strokeLine(lineX, top, lineX, top + gridHeight);
                }
                gc.setLineWidth(1 / pixelScaleY);
                for (int i = 0; i < ticksY; i++) {
                    double lineY = top + gridHeight * i / (ticksY - 1);
                    gc.strokeLine(left, lineY, left + gridWidth, lineY);
                }
            }
        } finally {
            gc.restore();
        }
    }

    private static Font font(String css) {
        Matcher match = fontMatch(css);
        String style = match.group(1).toLowerCase(java.util.Locale.ROOT);
        FontWeight weight = FontWeight.NORMAL;
        for (String token : style.trim().split("\\s+")) {
            if (token.equals("bold")) {
                weight = FontWeight.BOLD;
            } else if (token.matches("[1-9]00")) {
                weight = FontWeight.findByWeight(Integer.parseInt(token));
            }
        }
        FontPosture posture = style.contains("italic") || style.contains("oblique")
                ? FontPosture.ITALIC : FontPosture.REGULAR;
        String family = match.group(4).trim().replaceAll("^['\"]|['\"]$", "");
        if (family.equalsIgnoreCase("sans-serif")) {
            family = "System";
        } else if (family.equalsIgnoreCase("serif")) {
            family = "Serif";
        } else if (family.equalsIgnoreCase("monospace")) {
            family = "Monospaced";
        }
        double size = Double.parseDouble(match.group(2));
        if (match.group(3).equalsIgnoreCase("pt")) {
            size *= 96.0 / 72.0;
        }
        return Font.font(family, weight, posture, size);
    }

    private static Matcher fontMatch(String css) {
        Matcher match = FONT.matcher(css.trim());
        if (!match.matches() || !Double.isFinite(Double.parseDouble(match.group(2)))
                || Double.parseDouble(match.group(2)) <= 0) {
            throw new UnsupportedOperationException("Unsupported canvas CSS font: " + css);
        }
        return match;
    }

    private static void validateOp(JsonObject op) {
        String type = string(op, "type");
        switch (type) {
            case "translate":
                number(op, "x");
                number(op, "y");
                break;
            case "rotation":
                number(op, "rotation");
                break;
            case "scale":
                number(op, "scaleX");
                number(op, "scaleY");
                break;
            case "stroke":
            case "fill":
                Color.web(string(op, "color"));
                break;
            case "strokeWidth":
                if (number(op, "width") <= 0) {
                    throw new IllegalArgumentException("Canvas stroke width must be positive");
                }
                break;
            case "alpha":
                double alpha = number(op, "alpha");
                if (alpha < 0 || alpha > 1) {
                    throw new IllegalArgumentException("Canvas alpha must be in [0, 1]");
                }
                break;
            case "circle":
                number(op, "x");
                number(op, "y");
                bool(op, "stroke");
                if (number(op, "radius") < 0) {
                    throw new IllegalArgumentException("Canvas radius must be nonnegative");
                }
                break;
            case "polygon":
                bool(op, "stroke");
                // Fall through to the shared point-array validation.
            case "polyline":
                if (coordinates(op, "xPoints").length != coordinates(op, "yPoints").length) {
                    throw new IllegalArgumentException("Canvas point arrays must have equal lengths");
                }
                break;
            case "spline":
                coefficients(op, "x");
                coefficients(op, "y");
                break;
            case "text":
                string(op, "text");
                fontMatch(string(op, "font"));
                bool(op, "stroke");
                validateFrame(op);
                break;
            case "grid":
                validateFrame(op);
                number(op, "width");
                number(op, "height");
                number(op, "pivotX");
                number(op, "pivotY");
                ticks(op, "numTicksX");
                ticks(op, "numTicksY");
                break;
            case "image":
                throw new UnsupportedOperationException(
                        "Canvas image operations are unsupported; DashboardView does not fetch assets");
            default:
                throw new UnsupportedOperationException("Unsupported canvas operation: " + type);
        }
    }

    private static void validateFrame(JsonObject op) {
        number(op, "x");
        number(op, "y");
        number(op, "theta");
        bool(op, "usePageFrame");
    }

    private static int ticks(JsonObject op, String name) {
        double value = number(op, name);
        if (value < 2 || value > 10000 || value != Math.rint(value)) {
            throw new IllegalArgumentException("Canvas " + name + " must be an integer in [2, 10000]");
        }
        return (int) value;
    }

    private static double[] coordinates(JsonObject op, String name) {
        JsonArray array = array(op, name);
        double[] result = new double[array.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = number(array.get(i), name + "[" + i + "]");
        }
        return result;
    }

    private static JsonElement required(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("Missing dashboard JSON field: " + name);
        }
        return value;
    }

    private static JsonObject object(JsonElement value, String name) {
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("Dashboard " + name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = required(object, name);
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException("Dashboard " + name + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonObject object, String name) {
        return string(required(object, name), name);
    }

    private static String string(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Dashboard " + name + " must be a string");
        }
        return value.getAsString();
    }

    private static double number(JsonObject object, String name) {
        return number(required(object, name), name);
    }

    private static double number(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !Double.isFinite(value.getAsDouble())) {
            throw new IllegalArgumentException("Dashboard " + name + " must be a finite number");
        }
        return value.getAsDouble();
    }

    private static boolean bool(JsonObject object, String name) {
        JsonElement value = required(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Dashboard " + name + " must be a boolean");
        }
        return value.getAsBoolean();
    }
}
