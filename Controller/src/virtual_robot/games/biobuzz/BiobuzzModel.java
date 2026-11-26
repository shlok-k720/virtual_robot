package virtual_robot.games.biobuzz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, dependency-free rules model, not a flight or rigid-body simulation.
 * Coordinates are centered inches, audience at y=-72, red on x<0; headings are radians.
 * Flower centers, zones and hive openings are nominal approximations, not CAD geometry.
 * Runtime physics supplies robot poses and floor-piece positions; scoring transfers require reach.
 * Held-piece centers follow the robot center; individual mechanism offsets are not simulated.
 */
public final class BiobuzzModel {
    public enum Alliance {
        RED, BLUE;
        public Alliance opponent() { return this == RED ? BLUE : RED; }
    }
    public enum Kind { POLLEN, RED_NECTAR, BLUE_NECTAR }
    public enum Place { GROUND, HELD, CELL, FLOWER, RESERVE }
    public enum Phase { PRACTICE, AUTO, TRANSITION, TELEOP, SETTLING, FINISHED }

    /** Detached immutable snapshot. target/cell are -1 when not applicable. */
    public static final class Piece {
        public final int id;
        public final Kind kind;
        public final Place place;
        public final double x, y;
        /** Flower index or hive alliance ordinal. */
        public final int target;
        /** Hive cell 0 faces audience; cell 1 faces rear. */
        public final int cell;
        /** Whether this flower piece intersects the nominal vertical scoring band. */
        public final boolean flowerScored;
        private Piece(Element e, boolean flowerScored) {
            id = e.id; kind = e.kind; place = e.place;
            x = e.x; y = e.y; target = e.target; cell = e.cell;
            this.flowerScored = flowerScored;
        }
    }

    /** Detached ranking snapshot; outcome points are awarded only after final rest. */
    public static final class Ranking {
        public final int swarm, pollinator1, pollinator2, outcome, total;
        public final boolean finalized;
        private Ranking(int movement, int tips, int outcome, boolean finalized) {
            swarm = movement >= 16 ? 1 : 0;
            pollinator1 = tips >= 4 ? 1 : 0;
            pollinator2 = tips >= 7 ? 1 : 0;
            this.outcome = outcome;
            total = swarm + pollinator1 + pollinator2 + outcome;
            this.finalized = finalized;
        }
    }

    public static final class Result {
        public final boolean ok;
        public final String message;
        private Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    private static final double EPS = 1e-9;
    /*
     * Approximate normalized load, NOT published masses: 7p and 3n+2p do not tip;
     * 8p and 3n+3p do. 3n+3p weighs less than 8p. Stable delay is also approximate.
     */
    private static final double NECTAR_WEIGHT = 1.6, TIP_THRESHOLD = 7.5, TIP_DELAY = 0.75;
    private static final double[][] FLOWERS = {{-68, -24}, {-24, 68}, {68, 24}, {24, -68}};
    private static final double[][] LOADING = {{-72, -61, 24.5, 47.5}, {61, 72, -47.5, -24.5}};
    private static final double[][] GARDENS = {{-72, -49, -72, -70}, {49, 72, 70, 72}};
    /*
     * Vertical stack approximation: the retrieval opening's nominal upper edge stands in
     * for the middle ring; ring thickness, zig-zagging and overflow require CAD/3D physics.
     * Nectar cannot pass the smaller lower opening and rests at least at this middle ring.
     */
    private static final double FLOWER_BOTTOM = 3.55, FLOWER_TOP = 21.5;

    private static final class Element {
        final int id;
        final Kind kind;
        Place place;
        double x, y;
        int target = -1, cell = -1;
        boolean entered;
        Element(int id, Kind kind) { this.id = id; this.kind = kind; }
    }

    private final List<Element> elements = new ArrayList<>();
    private final List<Element> held = new ArrayList<>();
    private final List<List<Element>> flowers = new ArrayList<>();
    private final int[] upward = new int[2], autoTip = new int[2], teleopTip = new int[2];
    private final int[] practiceTip = new int[2], credits = new int[2], penalties = new int[2];
    private final int[] movementPoints = new int[2], finalScores = new int[2];
    private final int[] otherMovementPoints = new int[2];
    private final double[] pending = {-1, -1};
    private Alliance alliance = Alliance.RED;
    private Phase phase;
    private boolean paused;
    private double remaining, robotX, robotY, heading, width, height;
    private String lastStatus;

    public BiobuzzModel() {
        for (int i = 0; i < 4; i++) flowers.add(new ArrayList<Element>());
        reset();
    }

    public Alliance alliance() { return alliance; }

    public void selectAlliance(Alliance value) {
        requireAlliance(value);
        alliance = value;
        reset();
    }

    /** Starts practice with exactly 40 pollen and 8 nectar of each color. */
    public void reset() {
        robotX = alliance == Alliance.RED ? -63 : 63;
        robotY = 0; heading = 0; width = 18; height = 18;
        seed();
    }

    private void seed() {
        elements.clear(); held.clear();
        for (List<Element> stack : flowers) stack.clear();
        for (int i = 0; i < 2; i++) {
            upward[i] = i; autoTip[i] = teleopTip[i] = practiceTip[i] = 0;
            credits[i] = penalties[i] = movementPoints[i] = finalScores[i] = 0;
            otherMovementPoints[i] = 0;
            pending[i] = -1;
        }
        phase = Phase.PRACTICE; paused = false; remaining = 0;
        lastStatus = "Practice: powered actions available; match clock is not running.";
        for (int f = 0; f < 4; f++) {
            for (int n = 0; n < 4; n++) {
                Element e = add(Kind.POLLEN, Place.FLOWER, FLOWERS[f][0], FLOWERS[f][1], f, -1);
                flowers.get(f).add(e);
            }
        }
        for (int a = 0; a < 2; a++) {
            for (int n = 0; n < 4; n++) {
                double sign = a == 0 ? -1 : 1;
                add(Kind.POLLEN, Place.GROUND, sign * (70 - 5 * n), sign * 71, -1, -1);
            }
        }
        for (int n = 0; n < 4; n++) {
            Element e = add(Kind.POLLEN, Place.HELD, robotX, robotY, -1, -1);
            held.add(e);
        }
        for (int group = 0; group < 3; group++) {
            Alliance side = group == 0 ? alliance : alliance.opponent();
            double sign = side == Alliance.RED ? -1 : 1;
            for (int n = 0; n < 4; n++) {
                add(Kind.POLLEN, Place.GROUND, sign * (70.6 - (group == 2 ? 3 : 0)),
                        -sign * (30 + 3 * n), -1, -1);
            }
        }
        for (Alliance a : Alliance.values()) {
            for (int n = 0; n < 3; n++) {
                Element e = add(nectar(a), Place.CELL, hiveX(a), openingY(upward[a.ordinal()]),
                        a.ordinal(), upward[a.ordinal()]);
                e.entered = true;
            }
            for (int n = 0; n < 5; n++) add(nectar(a), Place.RESERVE, loadingX(a), loadingY(a), -1, -1);
        }
    }

    private Element add(Kind kind, Place place, double x, double y, int target, int cell) {
        Element e = new Element(elements.size(), kind);
        move(e, place, x, y, target, cell);
        elements.add(e);
        return e;
    }

    public List<Piece> pieces() {
        List<Piece> result = new ArrayList<>(elements.size());
        for (Element e : elements) result.add(new Piece(e, flowerScored(e)));
        return Collections.unmodifiableList(result);
    }

    /** Bottom-to-top stack snapshot; unlike pieces(), this order survives transfers. */
    public List<Piece> flowerPieces(int flower) {
        requireFlower(flower);
        List<Piece> result = new ArrayList<>();
        for (Element e : flowers.get(flower)) result.add(new Piece(e, flowerScored(e)));
        return Collections.unmodifiableList(result);
    }

    /**
     * Updates the external robot pose (including while power is disabled for passive motion).
     * Invalid/nonfinite geometry throws without changing state. Paused updates throw.
     * Finished poses may be prepared for restart, but final held-piece snapshots stay frozen.
     * Out-of-field poses are observable.
     */
    public void setRobot(double x, double y, double heading, double width, double height) {
        finite(x); finite(y); finite(heading); finite(width); finite(height);
        if (width <= 0 || height <= 0 || width > 144 || height > 144)
            throw new IllegalArgumentException("Robot width/height must be in (0,144] inches.");
        if (paused) throw new IllegalStateException("Robot updates are frozen while paused.");
        this.robotX = x; this.robotY = y; this.heading = heading % (2 * Math.PI);
        this.width = width; this.height = height;
        if (phase != Phase.FINISHED)
            for (Element e : held) { e.x = x; e.y = y; }
    }

    /**
     * Physics-only synchronization, never a scoring transfer. Invalid IDs, non-ground pieces
     * or nonfinite coordinates throw. Exited nectar becomes reusable reserve; pollen is returned
     * to the nearest safe floor point. Paused/finished models reject updates.
     */
    public Result syncGround(int id, double x, double y) {
        finite(x); finite(y);
        Element e = element(id);
        if (e == null || e.place != Place.GROUND)
            throw new IllegalArgumentException("syncGround requires an existing ground piece.");
        if (paused || phase == Phase.FINISHED) return fail("Ground updates are frozen.");
        if (Math.abs(x) > 72 || Math.abs(y) > 72) {
            if (e.kind != Kind.POLLEN) {
                Alliance side = owner(e.kind);
                move(e, Place.RESERVE, loadingX(side), loadingY(side), -1, -1);
                return success("Exited nectar returned to its alliance; reentry needs no new unlock.");
            }
            x = clamp(x, -70.6, 70.6); y = clamp(y, -70.6, 70.6);
        }
        move(e, Place.GROUND, x, y, -1, -1);
        return success("Ground position updated.");
    }

    public Result intake(int pieceId) {
        if (!poweredActionsAllowed()) return fail("Powered actions are disabled.");
        Element e = element(pieceId);
        if (e == null || e.place != Place.GROUND) return fail("Intake requires a ground piece.");
        if (held.size() >= 4) return fail("Capacity is four pieces.");
        if (e.kind != Kind.POLLEN && owner(e.kind) != alliance) return fail("Cannot control opponent nectar.");
        if (!near(e.x, e.y, Math.hypot(width, height) / 2 + 3)) return fail("Piece is out of intake reach.");
        hold(e);
        return success("Piece " + e.id + " intaken.");
    }

    /** Releases the oldest held piece beside the robot, preferring its front edge. */
    public Result drop() {
        if (!poweredActionsAllowed()) return fail("Powered actions are disabled.");
        if (held.isEmpty()) return fail("No held piece.");
        Element e = held.get(0);
        double radius = e.kind == Kind.POLLEN ? 1.4 : 1.8;
        double c = Math.cos(heading), s = Math.sin(heading);
        double front = height / 2 + radius + 0.5;
        double side = width / 2 + radius + 0.5;
        double[][] offsets = {{-s * front, c * front}, {c * side, s * side},
                {s * front, -c * front}, {-c * side, -s * side}};
        for (double[] offset : offsets) {
            double x = robotX + offset[0], y = robotY + offset[1];
            if (Math.abs(x) + radius > 72 || Math.abs(y) + radius > 72) continue;
            boolean occupied = false;
            for (Element other : elements) {
                if (other.place == Place.GROUND && Math.hypot(x - other.x, y - other.y) <
                        radius + (other.kind == Kind.POLLEN ? 1.4 : 1.8)) {
                    occupied = true;
                    break;
                }
            }
            if (occupied) continue;
            held.remove(0);
            move(e, Place.GROUND, x, y, -1, -1);
            e.entered = true;
            return success("Dropped piece " + e.id + " onto the floor.");
        }
        return fail("No clear floor space beside the robot.");
    }

    /** Deterministic FIFO shot abstraction into the currently upward opening, not a teleport. */
    public Result launchHive(Alliance target) {
        if (target == null) return fail("Select a valid hive alliance.");
        if (!poweredActionsAllowed()) return fail("Powered actions are disabled.");
        if (held.isEmpty()) return fail("No held piece.");
        int a = target.ordinal(), cell = upward[a];
        if (!near(hiveX(target), openingY(cell), 24)) return fail("Hive opening is beyond 24 inches.");
        Element e = held.remove(0);
        move(e, Place.CELL, hiveX(target), openingY(cell), a, cell);
        e.entered = true;
        if (pending[a] < 0 && cellWeight(a, cell) + EPS >= TIP_THRESHOLD) pending[a] = TIP_DELAY;
        return success("Launched piece " + e.id + (pending[a] >= 0 ? "; stable tip pending (~0.75s)." : "."));
    }

    public Result depositFlower(int flower) {
        if (!validFlower(flower)) return fail("Flower index must be 0..3.");
        if (!poweredActionsAllowed()) return fail("Powered actions are disabled.");
        if (held.isEmpty()) return fail("No held piece.");
        if (!near(FLOWERS[flower][0], FLOWERS[flower][1], 18)) return fail("Flower is beyond 18 inches.");
        Element e = held.remove(0);
        move(e, Place.FLOWER, FLOWERS[flower][0], FLOWERS[flower][1], flower, -1);
        flowers.get(flower).add(e);
        if (e.kind != Kind.POLLEN && phase != Phase.PRACTICE &&
                !(phase == Phase.TELEOP && remaining <= 60 + EPS)) {
            penalties[alliance.opponent().ordinal()] += 20;
            return success("Nectar deposited early: major foul, 20 points to opponent.");
        }
        return success("Piece " + e.id + " deposited through flower top.");
    }

    public Result retrieveFlower(int flower) {
        if (!validFlower(flower)) return fail("Flower index must be 0..3.");
        if (!poweredActionsAllowed()) return fail("Powered actions are disabled.");
        if (held.size() >= 4) return fail("Capacity is four pieces.");
        if (!near(FLOWERS[flower][0], FLOWERS[flower][1], 18)) return fail("Flower is beyond 18 inches.");
        List<Element> stack = flowers.get(flower);
        if (stack.isEmpty()) return fail("Flower is empty.");
        if (stack.get(0).kind != Kind.POLLEN) return fail("Bottom nectar blocks pollen retrieval.");
        Element e = stack.remove(0);
        hold(e);
        return success("Retrieved bottom pollen " + e.id + ".");
    }

    /** Places eligible own nectar onto a tile in the loading zone, never directly in the robot. */
    public Result humanLoad() {
        if (paused || phase == Phase.SETTLING || phase == Phase.FINISHED)
            return fail("Human loading is frozen.");
        Element fresh = null;
        for (Element e : elements) {
            if (e.place != Place.RESERVE || e.kind != nectar(alliance)) continue;
            if (e.entered) {
                move(e, Place.GROUND, loadingX(alliance), loadingY(alliance), -1, -1);
                return success("Previously entered nectar returned to loading-zone floor.");
            }
            if (fresh == null) fresh = e;
        }
        if (fresh == null) return fail("No reserve nectar remains.");
        int a = alliance.ordinal();
        if (credits[a] <= 0) return fail("Initial nectar is locked until a hive tip or the last 60 seconds.");
        credits[a]--;
        fresh.entered = true;
        move(fresh, Place.GROUND, loadingX(alliance), loadingY(alliance), -1, -1);
        return success("Unlocked nectar placed on loading-zone floor.");
    }

    /** Practice-only recovery of the four original robot allocations; never creates pieces. */
    public Result preload() {
        if (phase != Phase.PRACTICE || paused) return fail("Preload is a practice setup action only.");
        if (held.size() == 4) return success("Robot already holds four pieces.");
        List<Element> candidates = new ArrayList<>();
        for (int id = 24; id < 28; id++) {
            Element e = elements.get(id);
            if (e.place == Place.GROUND && near(e.x, e.y, Math.hypot(width, height) / 2 + 3))
                candidates.add(e);
        }
        if (candidates.isEmpty()) return fail("No original preload pollen is on the floor within reach.");
        for (Element e : candidates) {
            if (held.size() == 4) break;
            hold(e);
        }
        return success("Recovered original preload pollen within reach.");
    }

    public Result refereeFoul(Alliance offender, boolean major) {
        if (offender == null) return fail("Select a valid offending alliance.");
        if (phase == Phase.PRACTICE || phase == Phase.FINISHED || paused)
            return fail("Referee fouls require an active official match.");
        penalties[offender.opponent().ordinal()] += major ? 20 : 5;
        return success((major ? "Major" : "Minor") + " foul against " + offender + ".");
    }

    /**
     * Explicit referee snapshots for absent robots, never inferred from missing physics.
     * Counts replace prior evidence: at most one teammate, or two opposing robots.
     * The selected robot's phase snapshots remain automatic and are added separately.
     */
    public Result refereeOtherMovement(Alliance side, int leaves, int autoParks, int teleopParks) {
        if (side == null) return fail("Select a valid alliance.");
        int max = side == alliance ? 1 : 2;
        if (leaves < 0 || leaves > max || autoParks < 0 || autoParks > max ||
                teleopParks < 0 || teleopParks > max)
            return fail("Movement counts exceed the number of absent robots.");
        if (phase == Phase.PRACTICE || phase == Phase.FINISHED || paused)
            return fail("Referee movement requires an active official match.");
        otherMovementPoints[side.ordinal()] = 3 * leaves + 5 * (autoParks + teleopParks);
        return success("Absent-robot LEAVE/PARK evidence updated for " + side + ".");
    }

    /**
     * Validates a start, then restores official inventory while preserving the validated pose.
     * Practice never starts a clock implicitly.
     */
    public Result startMatch() {
        if (phase != Phase.PRACTICE && phase != Phase.FINISHED) return fail("A match is already active.");
        if (width > 18 + EPS || height > 18 + EPS) return fail("Starting envelope exceeds 18 by 18 inches.");
        double rx = extentX(), ry = extentY();
        if (Math.abs(robotX) + rx > 72 + EPS || Math.abs(robotY) + ry > 72 + EPS)
            return fail("Starting robot must be fully inside the field.");
        if ((alliance == Alliance.RED && robotX + rx > EPS) ||
                (alliance == Alliance.BLUE && robotX - rx < -EPS))
            return fail("Starting robot must be fully on its own half.");
        if (!wallContact()) return fail("Starting robot must touch a perimeter wall.");
        if (robotOverlaps(LOADING[alliance.ordinal()])) return fail("Starting robot overlaps its loading zone.");
        for (double[] flower : FLOWERS) {
            if (robotDiskOverlap(flower[0], flower[1], 2.4)) return fail("Starting robot overlaps a flower.");
        }
        seed();
        phase = Phase.AUTO; remaining = 30;
        return success("Official AUTO started: 30 seconds.");
    }

    public Result togglePause() {
        if (phase == Phase.PRACTICE || phase == Phase.FINISHED) return fail("Only active official matches can pause.");
        paused = !paused;
        return success(paused ? "Match paused." : "Match resumed.");
    }

    public boolean poweredActionsAllowed() {
        return !paused && (phase == Phase.PRACTICE || phase == Phase.AUTO || phase == Phase.TELEOP);
    }
    public boolean paused() { return paused; }
    public Phase phase() { return phase; }
    public double remainingSeconds() { return remaining; }
    public String status() { return lastStatus; }
    public int heldCount() { return held.size(); }
    public int upwardCell(Alliance side) { return upward[index(side)]; }
    /** Available unspent initial-reserve introductions; exited nectar needs no credit. */
    public int unlocked(Alliance side) { return credits[index(side)]; }
    public int autoTips(Alliance side) { return autoTip[index(side)]; }
    public int teleopTips(Alliance side) { return teleopTip[index(side)]; }
    public int tips(Alliance side) {
        int a = index(side);
        return autoTip[a] + teleopTip[a] + practiceTip[a];
    }

    public int movementPoints(Alliance side) {
        int a = index(side);
        return movementPoints[a] + otherMovementPoints[a];
    }

    /** Ordinary/regional thresholds; Championship TBA and premier overrides are not inferred. */
    public Ranking ranking(Alliance side) {
        requireAlliance(side);
        boolean finalized = phase == Phase.FINISHED;
        int outcome = !finalized ? 0 : score(side) > score(side.opponent()) ? 3 :
                score(side) == score(side.opponent()) ? 1 : 0;
        return new Ranking(movementPoints(side), autoTips(side) + teleopTips(side), outcome, finalized);
    }

    /**
     * Advances exact event boundaries, so one large step matches many small steps.
     * Pausing freezes both the official clock and approximate passive tip delay.
     */
    public void advance(double seconds) {
        finite(seconds);
        if (seconds < 0) throw new IllegalArgumentException("Elapsed seconds must be nonnegative.");
        if (paused || phase == Phase.FINISHED || seconds == 0) return;
        double left = seconds;
        while (left > 0) {
            double dt = left;
            if (timedPhase()) dt = Math.min(dt, remaining);
            if (phase == Phase.TELEOP && remaining > 60) dt = Math.min(dt, remaining - 60);
            for (double delay : pending) if (delay >= 0) dt = Math.min(dt, delay);
            if (timedPhase()) remaining = Math.max(0, remaining - dt);
            if (timedPhase() && remaining <= EPS) remaining = 0;
            if (phase == Phase.TELEOP && Math.abs(remaining - 60) <= EPS) remaining = 60;
            for (int a = 0; a < 2; a++) {
                if (pending[a] >= 0) {
                    pending[a] = Math.max(0, pending[a] - dt);
                    if (pending[a] <= EPS) pending[a] = 0;
                }
            }
            left = Math.max(0, left - dt);
            // Complete stable contact before period transitions at the same timestamp.
            for (int a = 0; a < 2; a++) if (pending[a] == 0) finishTip(a);
            if (phase == Phase.TELEOP && remaining <= 60) unlockRemaining();
            if (timedPhase() && remaining == 0) nextPhase();
            if (dt == 0 && !timedPhase() && pending[0] < 0 && pending[1] < 0) break;
        }
    }

    /**
     * Runtime signals that passive physics is at rest. Pending approximate damper contacts
     * complete first, then final inventories freeze. No automatic finish merely from time elapsed.
     */
    public void completeSettling() {
        if (phase != Phase.SETTLING || paused) return;
        for (int a = 0; a < 2; a++) if (pending[a] >= 0) finishTip(a);
        for (Alliance side : Alliance.values()) finalScores[side.ordinal()] = liveScore(side);
        phase = Phase.FINISHED;
        lastStatus = "Finished at rest; final inventories and scores frozen.";
    }

    private boolean timedPhase() {
        return phase == Phase.AUTO || phase == Phase.TRANSITION || phase == Phase.TELEOP;
    }

    private void nextPhase() {
        if (phase == Phase.AUTO) {
            if (!wallContact()) movementPoints[alliance.ordinal()] += 3;
            if (robotOverlaps(LOADING[alliance.ordinal()])) movementPoints[alliance.ordinal()] += 5;
            phase = Phase.TRANSITION; remaining = 8;
            lastStatus = "Transition: 8 seconds, powered actions disabled.";
        } else if (phase == Phase.TRANSITION) {
            phase = Phase.TELEOP; remaining = 120;
            lastStatus = "TELEOP: 120 seconds.";
        } else if (phase == Phase.TELEOP) {
            if (robotOverlaps(LOADING[alliance.ordinal()])) movementPoints[alliance.ordinal()] += 5;
            phase = Phase.SETTLING; remaining = 0;
            lastStatus = "Settling: powered actions disabled; awaiting explicit rest.";
        }
    }

    private void finishTip(int a) {
        pending[a] = -1;
        Alliance side = Alliance.values()[a];
        int old = upward[a];
        int n = 0;
        for (Element e : elements) {
            if (e.place == Place.CELL && e.target == a && e.cell == old) {
                // Spilled pieces have touched the floor before any subsequent robot intake.
                move(e, Place.GROUND, hiveX(side) + (n % 3 - 1) * 4,
                        openingY(old) + (n / 3) * (old == 0 ? -4 : 4), -1, -1);
                n++;
            }
        }
        upward[a] = 1 - old;
        if (phase == Phase.PRACTICE) practiceTip[a]++;
        else if (phase == Phase.AUTO || phase == Phase.TRANSITION) autoTip[a]++;
        else teleopTip[a]++;
        credits[a] = Math.min(freshReserveCount(side), credits[a] + 1);
        lastStatus = side + " hive stable tip completed (~0.75s approximation); old cell spilled to floor.";
    }

    private double cellWeight(int a, int cell) {
        double weight = 0;
        for (Element e : elements) {
            if (e.place == Place.CELL && e.target == a && e.cell == cell)
                weight += e.kind == Kind.POLLEN ? 1 : NECTAR_WEIGHT;
        }
        return weight;
    }

    private void unlockRemaining() {
        for (Alliance side : Alliance.values()) credits[side.ordinal()] = freshReserveCount(side);
    }

    private int freshReserveCount(Alliance side) {
        int count = 0;
        for (Element e : elements)
            if (e.kind == nectar(side) && e.place == Place.RESERVE && !e.entered) count++;
        return count;
    }

    public Alliance flowerOwner(int flower) {
        requireFlower(flower);
        List<Element> stack = flowers.get(flower);
        for (int i = stack.size() - 1; i >= 0; i--)
            if (stack.get(i).kind != Kind.POLLEN && flowerScored(stack.get(i)))
                return owner(stack.get(i).kind);
        return null;
    }

    public Alliance flowerBottomOwner(int flower) {
        requireFlower(flower);
        for (Element e : flowers.get(flower))
            if (e.kind != Kind.POLLEN && flowerScored(e)) return owner(e.kind);
        return null;
    }

    public int flowerScoredCount(int flower) {
        requireFlower(flower);
        int count = 0;
        for (Element e : flowers.get(flower)) if (flowerScored(e)) count++;
        return count;
    }

    private boolean flowerScored(Element piece) {
        if (piece.place != Place.FLOWER) return false;
        double bottom = 0;
        for (Element e : flowers.get(piece.target)) {
            if (e.kind != Kind.POLLEN) bottom = Math.max(bottom, FLOWER_BOTTOM);
            double top = bottom + (e.kind == Kind.POLLEN ? 2.8 : 3.6);
            if (e == piece) return top >= FLOWER_BOTTOM - EPS && bottom <= FLOWER_TOP + EPS;
            bottom = top;
        }
        throw new IllegalStateException("Flower piece missing from its ordered stack: " + piece.id);
    }

    /** Partial overlap in the infinitely tall garden, including pieces supported by the robot. */
    public int gardenCount(Alliance side) {
        double[] zone = GARDENS[index(side)];
        int count = 0;
        for (Element e : elements) {
            if (e.place != Place.RESERVE && diskOverlaps(e.x, e.y,
                    e.kind == Kind.POLLEN ? 1.4 : 1.8, zone)) count++;
        }
        return count;
    }

    /** Provisional inventory score until rest; FINISHED returns the immutable final snapshot. */
    public int score(Alliance side) {
        int a = index(side);
        return phase == Phase.FINISHED ? finalScores[a] : liveScore(side);
    }

    private int liveScore(Alliance side) {
        int a = side.ordinal();
        int score = movementPoints(side) + penalties[a] + 20 * tips(side) + gardenCount(side);
        for (Element e : elements)
            if (e.place == Place.CELL && e.target == a && e.cell == upward[a]) score += 2;
        for (int f = 0; f < 4; f++) {
            if (flowerOwner(f) == side) score += 2 * flowerScoredCount(f);
            if (flowerBottomOwner(f) == side) score += 5;
        }
        return score;
    }

    public String scoreboard() {
        return String.format(Locale.ROOT,
                "RED %d (AUTO tips %d, TELEOP tips %d, reserve unlocked %d) | " +
                "BLUE %d (AUTO tips %d, TELEOP tips %d, reserve unlocked %d) | %s %.2fs%s",
                score(Alliance.RED), autoTip[0], teleopTip[0], credits[0],
                score(Alliance.BLUE), autoTip[1], teleopTip[1], credits[1],
                phase, remaining, paused ? " PAUSED" : "");
    }

    private void hold(Element e) {
        move(e, Place.HELD, robotX, robotY, -1, -1);
        held.add(e);
    }
    private static void move(Element e, Place place, double x, double y, int target, int cell) {
        e.place = place; e.x = x; e.y = y; e.target = target; e.cell = cell;
    }
    private Element element(int id) { return id >= 0 && id < elements.size() ? elements.get(id) : null; }
    private boolean near(double x, double y, double reach) { return Math.hypot(x - robotX, y - robotY) <= reach + EPS; }
    private double extentX() { return Math.abs(Math.cos(heading)) * width / 2 + Math.abs(Math.sin(heading)) * height / 2; }
    private double extentY() { return Math.abs(Math.sin(heading)) * width / 2 + Math.abs(Math.cos(heading)) * height / 2; }
    private boolean wallContact() { return Math.abs(robotX) + extentX() >= 72 - EPS || Math.abs(robotY) + extentY() >= 72 - EPS; }

    /** Separating-axis test for rotated robot against an axis-aligned inclusive tape volume. */
    private boolean robotOverlaps(double[] zone) {
        double zx = (zone[0] + zone[1]) / 2, zy = (zone[2] + zone[3]) / 2;
        double zw = (zone[1] - zone[0]) / 2, zh = (zone[3] - zone[2]) / 2;
        double dx = robotX - zx, dy = robotY - zy, c = Math.cos(heading), s = Math.sin(heading);
        if (Math.abs(dx) > extentX() + zw + EPS || Math.abs(dy) > extentY() + zh + EPS) return false;
        return Math.abs(dx * c + dy * s) <= width / 2 + zw * Math.abs(c) + zh * Math.abs(s) + EPS &&
                Math.abs(-dx * s + dy * c) <= height / 2 + zw * Math.abs(s) + zh * Math.abs(c) + EPS;
    }

    private boolean robotDiskOverlap(double x, double y, double radius) {
        double dx = x - robotX, dy = y - robotY, c = Math.cos(heading), s = Math.sin(heading);
        double localX = dx * c + dy * s, localY = -dx * s + dy * c;
        return Math.hypot(Math.max(0, Math.abs(localX) - width / 2),
                Math.max(0, Math.abs(localY) - height / 2)) <= radius + EPS;
    }
    private static boolean diskOverlaps(double x, double y, double r, double[] zone) {
        return Math.hypot(x - clamp(x, zone[0], zone[1]), y - clamp(y, zone[2], zone[3])) <= r + EPS;
    }
    private static double clamp(double n, double min, double max) { return Math.max(min, Math.min(max, n)); }
    private static double hiveX(Alliance side) { return side == Alliance.RED ? -12.75 : 12.75; }
    private static double openingY(int cell) { return cell == 0 ? -20 : 20; }
    private static double loadingX(Alliance side) { return side == Alliance.RED ? -66.5 : 66.5; }
    private static double loadingY(Alliance side) { return side == Alliance.RED ? 36 : -36; }
    private static Kind nectar(Alliance side) { return side == Alliance.RED ? Kind.RED_NECTAR : Kind.BLUE_NECTAR; }
    private static Alliance owner(Kind kind) { return kind == Kind.RED_NECTAR ? Alliance.RED : Alliance.BLUE; }
    private static int index(Alliance side) { requireAlliance(side); return side.ordinal(); }
    private static void requireAlliance(Alliance side) {
        if (side == null) throw new IllegalArgumentException("Alliance must not be null.");
    }
    private static boolean validFlower(int flower) { return flower >= 0 && flower < 4; }
    private static void requireFlower(int flower) {
        if (!validFlower(flower)) throw new IllegalArgumentException("Flower index must be 0..3.");
    }
    private static void finite(double n) {
        if (!Double.isFinite(n)) throw new IllegalArgumentException("Inputs must be finite.");
    }
    private Result success(String message) { lastStatus = message; return new Result(true, message); }
    private Result fail(String message) { lastStatus = message; return new Result(false, message); }
}
