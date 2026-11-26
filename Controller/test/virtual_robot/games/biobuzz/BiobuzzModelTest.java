package virtual_robot.games.biobuzz;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import virtual_robot.games.biobuzz.BiobuzzModel.Alliance;
import virtual_robot.games.biobuzz.BiobuzzModel.Kind;
import virtual_robot.games.biobuzz.BiobuzzModel.Phase;
import virtual_robot.games.biobuzz.BiobuzzModel.Piece;
import virtual_robot.games.biobuzz.BiobuzzModel.Place;
import virtual_robot.games.biobuzz.BiobuzzModel.Result;

/**
 * Dependency-free rules tests. Run this class with assertions enabled (-ea).
 * Coordinates are inches about the field center; robot headings are radians.
 */
public final class BiobuzzModelTest {
    private static final double EPS = 1e-8;
    private static final double[][] FLOWERS = {
        {-68, -24}, {-24, 68}, {68, 24}, {24, -68}
    };
    private static int passed;

    private BiobuzzModelTest() {
    }

    public static void main(String[] args) {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) {
            throw new IllegalStateException("Run BiobuzzModelTest with -ea");
        }
        run("seed, reset, alliance selection and snapshots", BiobuzzModelTest::testSeedAndSnapshots);
        run("invalid arguments and transactional rejection", BiobuzzModelTest::testInvalidArguments);
        run("capacity, reach, color and deterministic transfers", BiobuzzModelTest::testTransfers);
        run("drop releases held pieces and respects disabled power", BiobuzzModelTest::testDrop);
        run("practice preload cannot fabricate or descore", BiobuzzModelTest::testPreload);
        run("weighted threshold and delayed spill", BiobuzzModelTest::testWeightedTip);
        run("seven versus eight pollen in an empty cell", BiobuzzModelTest::testEmptyCellThreshold);
        run("flower ownership, bottom bonus and retrieval", BiobuzzModelTest::testFlowers);
        run("ground synchronization and partial gardens", BiobuzzModelTest::testGroundAndGardens);
        run("fresh unlock credits and exited nectar", BiobuzzModelTest::testUnlockAndReentry);
        run("endgame unlock boundary", BiobuzzModelTest::testEndgameUnlock);
        run("official starting geometry and reset", BiobuzzModelTest::testStartGeometry);
        run("phase boundaries and powered actions", BiobuzzModelTest::testPhases);
        run("pause freezes clock, actions and pending tips", BiobuzzModelTest::testPause);
        run("transition and large-step tip attribution", BiobuzzModelTest::testAutoAttribution);
        run("settling flush and immutable final score", BiobuzzModelTest::testSettling);
        run("rotated wall and loading-zone snapshots", BiobuzzModelTest::testRobotSnapshots);
        run("official referee and early nectar fouls", BiobuzzModelTest::testFouls);
        run("flower scoring band, overflow and ordered snapshots", BiobuzzModelTest::testFlowerScoringBand);
        run("held garden overlap and frozen final inventory", BiobuzzModelTest::testHeldGardens);
        run("fractional simulation clock boundaries", BiobuzzModelTest::testFractionalClock);
        run("ranking thresholds and absent-robot evidence", BiobuzzModelTest::testRanking);
        run("reset clears pending tips, pause and referee state", BiobuzzModelTest::testCompleteReset);
        run("human loading during transition versus final settling", BiobuzzModelTest::testTransitionLoading);
        System.out.println("BiobuzzModelTest: " + passed + " tests passed");
    }

    private static void run(String name, Runnable test) {
        try {
            test.run();
            passed++;
        } catch (AssertionError | RuntimeException failure) {
            throw new AssertionError(name, failure);
        }
    }

    private static void testSeedAndSnapshots() {
        BiobuzzModel m = new BiobuzzModel();
        assert m.alliance() == Alliance.RED;
        assert Alliance.RED.opponent() == Alliance.BLUE;
        assert Alliance.BLUE.opponent() == Alliance.RED;
        assert m.phase() == Phase.PRACTICE;
        assert m.poweredActionsAllowed() && !m.paused();
        assert m.status() != null && m.scoreboard() != null;
        String seed = inventory(m);
        List<Piece> old = m.pieces();
        Piece originalHeld = first(m, Place.HELD, Kind.POLLEN);
        for (Alliance a : Alliance.values()) {
            m.selectAlliance(a);
            assert m.alliance() == a;
            assert m.phase() == Phase.PRACTICE && !m.paused();
            assertSeed(m);
            String selectedSeed = inventory(m);
            shoot(m, a);
            assert originalHeld.place == Place.HELD;
            assert old.size() == 56;
            m.reset();
            assert inventory(m).equals(selectedSeed);
            assert m.alliance() == a;
            assertSeed(m);
        }
        m.selectAlliance(Alliance.RED);
        assert inventory(m).equals(seed);
        List<Piece> detached = m.pieces();
        try {
            detached.clear();
        } catch (UnsupportedOperationException expected) {
            // Both unmodifiable snapshots and mutable detached lists are safe.
        }
        assert m.pieces().size() == 56;
        assert inventory(m).equals(seed);
    }

    private static void assertSeed(BiobuzzModel m) {
        conserve(m);
        assert count(m, Place.HELD, Kind.POLLEN) == 4;
        assert count(m, Place.GROUND, Kind.POLLEN) == 20;
        assert count(m, Place.FLOWER, Kind.POLLEN) == 16;
        assert count(m, Place.CELL, Kind.RED_NECTAR) == 3;
        assert count(m, Place.CELL, Kind.BLUE_NECTAR) == 3;
        assert count(m, Place.RESERVE, Kind.RED_NECTAR) == 5;
        assert count(m, Place.RESERVE, Kind.BLUE_NECTAR) == 5;
        assert m.heldCount() == 4;
        assert m.upwardCell(Alliance.RED) == 0;
        assert m.upwardCell(Alliance.BLUE) == 1;
        for (Alliance a : Alliance.values()) {
            assert m.score(a) == 10 : "six cell points plus four garden points";
            assert m.tips(a) == 0 && m.autoTips(a) == 0 && m.teleopTips(a) == 0;
            assert m.unlocked(a) == 0 && m.gardenCount(a) == 4;
        }
        for (int flower = 0; flower < 4; flower++) {
            assert flowerSize(m, flower) == 4;
            assert m.flowerOwner(flower) == null;
            assert m.flowerBottomOwner(flower) == null;
        }
        for (Piece p : m.pieces()) {
            if (p.place == Place.GROUND || p.place == Place.HELD || p.place == Place.RESERVE) {
                assert p.target == -1 && p.cell == -1;
            } else if (p.place == Place.FLOWER) {
                assert p.target >= 0 && p.target < 4 && p.cell == -1;
            } else {
                assert p.cell == 0 || p.cell == 1;
            }
        }
        int ownLoading = 0;
        int otherLoading = 0;
        for (Piece p : m.pieces()) {
            if (p.place == Place.GROUND && p.kind == Kind.POLLEN && Math.abs(p.y) < 50) {
                if ((p.x < 0) == (m.alliance() == Alliance.RED)) {
                    ownLoading++;
                } else {
                    otherLoading++;
                }
            }
        }
        assert ownLoading == 4 && otherLoading == 8;
    }

    private static void testDrop() {
        BiobuzzModel m = new BiobuzzModel();
        m.setRobot(0, 0, 0, 18, 18);
        int id = first(m, Place.HELD, Kind.POLLEN).id;
        assert m.drop().ok;
        Piece dropped = piece(m, id);
        assert dropped.place == Place.GROUND && dropped.target == -1 && dropped.cell == -1;
        assert m.heldCount() == 3 && m.pieces().size() == 56;
        assert Math.abs(dropped.x) + 1.4 <= 72 && Math.abs(dropped.y) + 1.4 <= 72;
        for (int i = 0; i < 3; i++) assert m.drop().ok;
        rejected(m, m::drop);
        m.reset();
        assert m.startMatch().ok;
        assert m.togglePause().ok;
        rejected(m, m::drop);
        assert m.togglePause().ok;
        m.advance(30);
        rejected(m, m::drop);
    }

    private static void testInvalidArguments() {
        BiobuzzModel m = new BiobuzzModel();
        rejected(m, () -> m.intake(-1));
        rejected(m, () -> m.intake(Integer.MAX_VALUE));
        rejected(m, () -> m.launchHive(null));
        rejected(m, () -> m.depositFlower(-1));
        rejected(m, () -> m.depositFlower(4));
        rejected(m, () -> m.retrieveFlower(-1));
        rejected(m, () -> m.retrieveFlower(4));
        rejected(m, m::humanLoad);
        rejected(m, m::togglePause);
        rejected(m, () -> m.refereeFoul(Alliance.RED, false));
        String before = state(m);
        expectIllegal(() -> m.selectAlliance(null));
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            expectIllegal(() -> m.advance(bad));
            expectIllegal(() -> m.setRobot(bad, 0, 0, 18, 18));
            expectIllegal(() -> m.setRobot(0, bad, 0, 18, 18));
            expectIllegal(() -> m.setRobot(0, 0, bad, 18, 18));
            expectIllegal(() -> m.setRobot(0, 0, 0, bad, 18));
            expectIllegal(() -> m.setRobot(0, 0, 0, 18, bad));
            Piece ground = first(m, Place.GROUND, Kind.POLLEN);
            expectIllegal(() -> m.syncGround(ground.id, bad, 0));
            expectIllegal(() -> m.syncGround(ground.id, 0, bad));
        }
        expectIllegal(() -> m.advance(-1));
        expectIllegal(() -> m.setRobot(0, 0, 0, 0, 18));
        expectIllegal(() -> m.setRobot(0, 0, 0, 18, -1));
        expectIllegal(() -> m.syncGround(-1, 0, 0));
        expectIllegal(() -> m.syncGround(first(m, Place.HELD, Kind.POLLEN).id, 0, 0));
        expectIllegal(() -> m.syncGround(first(m, Place.FLOWER, Kind.POLLEN).id, 0, 0));
        expectIllegal(() -> m.syncGround(first(m, Place.CELL, Kind.RED_NECTAR).id, 0, 0));
        expectIllegal(() -> m.syncGround(first(m, Place.RESERVE, Kind.RED_NECTAR).id, 0, 0));
        expectIllegal(() -> m.score(null));
        expectIllegal(() -> m.tips(null));
        expectIllegal(() -> m.autoTips(null));
        expectIllegal(() -> m.teleopTips(null));
        expectIllegal(() -> m.unlocked(null));
        expectIllegal(() -> m.gardenCount(null));
        expectIllegal(() -> m.upwardCell(null));
        expectIllegal(() -> m.flowerOwner(-1));
        expectIllegal(() -> m.flowerOwner(4));
        expectIllegal(() -> m.flowerBottomOwner(-1));
        expectIllegal(() -> m.flowerBottomOwner(4));
        expectIllegal(() -> m.flowerPieces(-1));
        expectIllegal(() -> m.flowerPieces(4));
        expectIllegal(() -> m.flowerScoredCount(-1));
        expectIllegal(() -> m.flowerScoredCount(4));
        expectIllegal(() -> m.movementPoints(null));
        expectIllegal(() -> m.ranking(null));
        m.advance(0);
        m.completeSettling();
        assert state(m).equals(before);
    }

    private static void testTransfers() {
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            Piece ground = first(m, Place.GROUND, Kind.POLLEN);
            final int initialGroundId = ground.id;
            move(m, ground.x, ground.y);
            rejected(m, () -> m.intake(initialGroundId));
            move(m, 0, 0);
            rejected(m, () -> m.depositFlower(0));
            rejected(m, () -> m.retrieveFlower(0));
            moveToHive(m, a, 24.01);
            rejected(m, () -> m.launchHive(a));
            moveToHive(m, a, 24);
            Set<Integer> held = ids(m, Place.HELD);
            success(m.launchHive(a));
            int launched = removedHeld(m, held);
            assert piece(m, launched).place == Place.CELL;
            assert m.heldCount() == 3;
            ground = first(m, Place.GROUND, Kind.POLLEN);
            final int groundId = ground.id;
            double reach = Math.hypot(18, 18) / 2 + 3;
            move(m, ground.x + reach + 0.01, ground.y);
            rejected(m, () -> m.intake(groundId));
            move(m, ground.x + reach, ground.y);
            success(m.intake(groundId));
            assert piece(m, groundId).place == Place.HELD && m.heldCount() == 4;
            move(m, FLOWERS[0][0] + 18.01, FLOWERS[0][1]);
            rejected(m, () -> m.depositFlower(0));
            move(m, FLOWERS[0][0] + 18, FLOWERS[0][1]);
            success(m.depositFlower(0));
            move(m, FLOWERS[0][0] + 18.01, FLOWERS[0][1]);
            rejected(m, () -> m.retrieveFlower(0));
            move(m, FLOWERS[0][0] + 18, FLOWERS[0][1]);
            success(m.retrieveFlower(0));
            assert m.heldCount() == 4;
            conserve(m);

            BiobuzzModel repeat = model(a);
            moveToHive(repeat, a, 24);
            Set<Integer> repeatHeld = ids(repeat, Place.HELD);
            success(repeat.launchHive(a));
            assert removedHeld(repeat, repeatHeld) == launched : "reset held order must be deterministic";

            BiobuzzModel color = model(a);
            shootPollen(color, a.opponent(), 3);
            color.advance(0.75);
            emptyHeld(color, 0);
            Piece opponentNectar = first(color, Place.GROUND, nectar(a.opponent()));
            move(color, opponentNectar.x, opponentNectar.y);
            rejected(color, () -> color.intake(opponentNectar.id));
            assert color.heldCount() == 0;
            rejected(color, () -> color.launchHive(a));
            moveToFlower(color, 0);
            rejected(color, () -> color.depositFlower(0));
            conserve(color);
        }
    }

    private static void testPreload() {
        BiobuzzModel m = new BiobuzzModel();
        String seed = inventory(m);
        success(m.preload());
        assert inventory(m).equals(seed);
        Set<Integer> originals = ids(m, Place.HELD);
        shootPollen(m, Alliance.RED, 3);
        m.advance(0.75);
        for (int id : originals) {
            Piece p = piece(m, id);
            if (p.place == Place.GROUND) {
                m.syncGround(id, 0, 0);
            }
        }
        move(m, 30, 0);
        rejected(m, m::preload);
        move(m, 0, 0);
        success(m.preload());
        assert m.heldCount() == 4 && ids(m, Place.HELD).equals(originals);
        conserve(m);
        m.reset();
        shoot(m, Alliance.RED);
        move(m, 0, 0);
        String inCell = inventory(m);
        rejected(m, m::preload);
        assert inventory(m).equals(inCell);
        m.reset();
        emptyHeld(m, 0);
        moveToFlower(m, 0);
        rejected(m, m::preload);
        assert m.heldCount() == 0 && flowerSize(m, 0) == 8;
        m.reset();
        success(m.startMatch());
        rejected(m, m::preload);
    }

    private static void testWeightedTip() {
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            int up = m.upwardCell(a);
            shootPollen(m, a, 2);
            m.advance(2);
            assert m.tips(a) == 0 && m.upwardCell(a) == up;
            assert cellSize(m, a, up) == 5;
            shoot(m, a);
            m.advance(0.5);
            assert m.tips(a) == 0 && m.upwardCell(a) == up;
            assert cellSize(m, a, up) == 6;
            Set<Integer> pendingInventory = cellIds(m, a, up);
            shoot(m, a);
            pendingInventory.addAll(cellIds(m, a, up));
            assert cellSize(m, a, up) == 7;
            m.advance(0.25);
            assert m.tips(a) == 1 && m.upwardCell(a) == 1 - up;
            assert cellSize(m, a, up) == 0 && cellSize(m, a, 1 - up) == 0;
            for (int id : pendingInventory) {
                assert piece(m, id).place == Place.GROUND : "all pending load spills on completion";
            }
            assert m.unlocked(a) == 1;
            m.advance(10);
            assert m.tips(a) == 1;
            conserve(m);
        }
    }

    private static void testEmptyCellThreshold() {
        BiobuzzModel m = new BiobuzzModel();
        Alliance hive = Alliance.BLUE;
        shootPollen(m, hive, 3);
        m.advance(0.75);
        int emptyUp = m.upwardCell(hive);
        assert cellSize(m, hive, emptyUp) == 0 && m.tips(hive) == 1;
        shootPollen(m, hive, 7);
        m.advance(2);
        assert m.tips(hive) == 1 && cellSize(m, hive, emptyUp) == 7;
        assert m.score(hive) == 20 + 14 + m.gardenCount(hive)
                : "cell points belong to the hive, regardless of the shooting alliance";
        shootPollen(m, hive, 1);
        m.advance(0.5);
        assert m.tips(hive) == 1 && cellSize(m, hive, emptyUp) == 8;
        m.advance(0.25);
        assert m.tips(hive) == 2 && m.upwardCell(hive) == 1 - emptyUp;
        assert m.tips(Alliance.RED) == 0 && m.unlocked(Alliance.RED) == 0;
        conserve(m);
    }

    private static void testFlowers() {
        // Alliance selection resets the field, so both colors are tested in separate games.
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            shootPollen(m, a, 3);
            m.advance(0.75);
            emptyHeld(m, 1);
            for (int i = 0; i < 4; i++) {
                moveToFlower(m, 0);
                success(m.retrieveFlower(0));
                moveToFlower(m, 1);
                success(m.depositFlower(1));
            }
            assert flowerSize(m, 0) == 0;
            moveToFlower(m, 0);
            rejected(m, () -> m.retrieveFlower(0));
            Piece n = first(m, Place.GROUND, nectar(a));
            pickUp(m, n.id);
            int before = m.score(a);
            moveToFlower(m, 0);
            success(m.depositFlower(0));
            assert m.flowerOwner(0) == a && m.flowerBottomOwner(0) == a;
            assert m.score(a) == before + 7 : "one nectar earns ownership points and one bottom bonus";
            rejected(m, () -> m.retrieveFlower(0));
            n = first(m, Place.GROUND, nectar(a));
            pickUp(m, n.id);
            moveToFlower(m, 0);
            success(m.depositFlower(0));
            assert m.score(a) == before + 9 : "bottom bonus is once per flower, not once per nectar";
            pickUp(m, first(m, Place.GROUND, Kind.POLLEN).id);
            moveToFlower(m, 0);
            before = m.score(a);
            success(m.depositFlower(0));
            assert m.flowerOwner(0) == a && m.flowerBottomOwner(0) == a;
            assert m.score(a) == before + 2;
            rejected(m, () -> m.retrieveFlower(0));

            pickUp(m, first(m, Place.GROUND, nectar(a)).id);
            int originalSize = flowerSize(m, 2);
            before = m.score(a);
            moveToFlower(m, 2);
            success(m.depositFlower(2));
            assert m.flowerOwner(2) == a && m.flowerBottomOwner(2) == a;
            assert m.score(a) == before + 5 + 2 * originalSize
                    : "floor-ring pollen is below the flower scoring volume";
            for (int i = 0; i < originalSize; i++) {
                moveToFlower(m, 2);
                success(m.retrieveFlower(2));
                assert m.flowerOwner(2) == a && m.flowerBottomOwner(2) == a;
                moveToFlower(m, 3);
                success(m.depositFlower(3));
            }
            moveToFlower(m, 2);
            rejected(m, () -> m.retrieveFlower(2));
            assert flowerSize(m, 2) == 1;
            conserve(m);
        }
    }

    private static void testGroundAndGardens() {
        BiobuzzModel m = new BiobuzzModel();
        for (Piece p : m.pieces()) {
            if (p.place == Place.GROUND) {
                m.syncGround(p.id, 0, 0);
            }
        }
        assert m.gardenCount(Alliance.RED) == 0 && m.gardenCount(Alliance.BLUE) == 0;
        Piece pollen = first(m, Place.GROUND, Kind.POLLEN);
        Piece snapshot = pollen;
        m.syncGround(pollen.id, -60, -69);
        assert m.gardenCount(Alliance.RED) == 1 : "disk overlaps garden with center outside";
        assert snapshot.x == 0 && snapshot.y == 0;
        assert m.score(Alliance.RED) == 7;
        m.syncGround(pollen.id, -60, -68.5);
        assert m.gardenCount(Alliance.RED) == 0;
        m.syncGround(pollen.id, 60, 69);
        assert m.gardenCount(Alliance.BLUE) == 1;
        m.syncGround(pollen.id, 60, 68.5);
        assert m.gardenCount(Alliance.BLUE) == 0;
        m.syncGround(pollen.id, 1000, -1000);
        Piece returned = piece(m, pollen.id);
        assert returned.place == Place.GROUND;
        assert Math.abs(returned.x) <= 72 && Math.abs(returned.y) <= 72;
        assert returned.kind == Kind.POLLEN;
        shootPollen(m, Alliance.RED, 3);
        m.advance(0.75);
        Piece redNectar = first(m, Place.GROUND, Kind.RED_NECTAR);
        m.syncGround(redNectar.id, 60, 68.3);
        assert m.gardenCount(Alliance.BLUE) == 1 : "any-color nectar and its larger disk count";
        m.syncGround(redNectar.id, 60, 68.1);
        assert m.gardenCount(Alliance.BLUE) == 0;
        conserve(m);
    }

    private static void testUnlockAndReentry() {
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            rejected(m, m::humanLoad);
            Set<Integer> fresh = ids(m, Place.RESERVE, nectar(a));
            int lowestFresh = fresh.stream().min(Integer::compareTo).get();
            shootPollen(m, a, 3);
            assert m.unlocked(a) == 0;
            m.advance(0.75);
            assert m.unlocked(a) == 1;
            success(m.humanLoad());
            Piece loaded = piece(m, lowestFresh);
            assert loaded.place == Place.GROUND;
            near(loaded.x, a == Alliance.RED ? -66.5 : 66.5);
            near(loaded.y, a == Alliance.RED ? 36 : -36);
            assert m.unlocked(a) == 0;
            rejected(m, m::humanLoad);
            Piece exited = null;
            for (Piece p : m.pieces()) {
                if (p.place == Place.GROUND && p.kind == nectar(a) && !fresh.contains(p.id)) {
                    exited = p;
                    break;
                }
            }
            assert exited != null;
            int exitedId = exited.id;
            int reserves = count(m, Place.RESERVE, nectar(a));
            m.syncGround(exitedId, 90, 0);
            assert piece(m, exitedId).place == Place.RESERVE;
            assert count(m, Place.RESERVE, nectar(a)) == reserves + 1;
            assert m.unlocked(a) == 0;
            success(m.humanLoad());
            assert piece(m, exitedId).place == Place.GROUND;
            assert m.unlocked(a) == 0 : "reentry must not spend a fresh unlock";
            conserve(m);
        }
        BiobuzzModel capped = new BiobuzzModel();
        shootPollen(capped, Alliance.RED, 3);
        capped.advance(0.75);
        for (int tip = 2; tip <= 6; tip++) {
            shootPollen(capped, Alliance.RED, 8);
            capped.advance(0.75);
            assert capped.tips(Alliance.RED) == tip;
            assert capped.unlocked(Alliance.RED) == Math.min(tip, 5);
        }
        for (int i = 0; i < 5; i++) {
            success(capped.humanLoad());
        }
        shootPollen(capped, Alliance.RED, 8);
        capped.advance(0.75);
        assert capped.tips(Alliance.RED) == 7 && capped.unlocked(Alliance.RED) == 0;
        rejected(capped, capped::humanLoad);
        conserve(capped);
    }

    private static void testEndgameUnlock() {
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            success(m.startMatch());
            m.advance(38);
            assert m.phase() == Phase.TELEOP;
            m.advance(59.5);
            near(m.remainingSeconds(), 60.5);
            assert m.unlocked(a) == 0;
            rejected(m, m::humanLoad);
            m.advance(0.5);
            near(m.remainingSeconds(), 60);
            assert m.unlocked(a) == 5;
            List<Integer> fresh = new ArrayList<>(ids(m, Place.RESERVE, nectar(a)));
            fresh.sort(Integer::compareTo);
            for (int i = 0; i < 5; i++) {
                success(m.humanLoad());
                assert piece(m, fresh.get(i)).place == Place.GROUND;
                assert m.unlocked(a) == 4 - i;
                assert m.heldCount() == 4 : "human loading is floor introduction, not a handoff";
            }
            rejected(m, m::humanLoad);
            m.advance(1);
            assert m.unlocked(a) == 0 : "endgame unlock cannot replenish spent fresh reserves";
            conserve(m);
        }
    }

    private static void testStartGeometry() {
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            double sign = a == Alliance.RED ? -1 : 1;
            move(m, -sign * 63, 0);
            rejected(m, m::startMatch);
            move(m, sign * 40, 0);
            rejected(m, m::startMatch);
            move(m, sign * 63, a == Alliance.RED ? -24 : 24);
            rejected(m, m::startMatch);
            move(m, sign * 63, a == Alliance.RED ? 36 : -36);
            rejected(m, m::startMatch);
            m.setRobot(sign * 62.5, 0, 0, 19, 18);
            rejected(m, m::startMatch);
            m.setRobot(sign * 63, 0, 0, 18, 19);
            rejected(m, m::startMatch);
            m.reset();
            String seed = inventory(m);
            emptyHeld(m, 0);
            move(m, sign * 63, 0);
            success(m.startMatch());
            assert inventory(m).equals(seed) : "official start reseeds practice manipulations";
            assert m.phase() == Phase.AUTO && m.alliance() == a;
            near(m.remainingSeconds(), 30);
            rejected(m, m::startMatch);
            m.advance(158);
            m.completeSettling();
            assert m.phase() == Phase.FINISHED;
            move(m, 0, 0);
            rejected(m, m::startMatch);
            move(m, sign * 63, 0);
            success(m.startMatch());
            assert inventory(m).equals(seed) && m.score(a) == 10;
            m.advance(30);
            assert m.score(a) == 10 : "validated wall-contact pose must survive start reseeding";
        }
    }

    private static void testPhases() {
        BiobuzzModel m = new BiobuzzModel();
        success(m.startMatch());
        assert m.poweredActionsAllowed();
        m.completeSettling();
        assert m.phase() == Phase.AUTO;
        m.advance(29.5);
        near(m.remainingSeconds(), 0.5);
        m.advance(0.5);
        assert m.phase() == Phase.TRANSITION && !m.poweredActionsAllowed();
        near(m.remainingSeconds(), 8);
        noPoweredActions(m);
        m.advance(7.5);
        near(m.remainingSeconds(), 0.5);
        m.advance(0.5);
        assert m.phase() == Phase.TELEOP && m.poweredActionsAllowed();
        near(m.remainingSeconds(), 120);
        m.advance(120);
        assert m.phase() == Phase.SETTLING && !m.poweredActionsAllowed();
        near(m.remainingSeconds(), 0);
        noPoweredActions(m);
        m.advance(1000);
        assert m.phase() == Phase.SETTLING : "settling requires explicit completion";
        m.completeSettling();
        assert m.phase() == Phase.FINISHED && !m.poweredActionsAllowed();
        noPoweredActions(m);
        rejected(m, m::togglePause);
        conserve(m);
    }

    private static void testPause() {
        BiobuzzModel m = new BiobuzzModel();
        success(m.startMatch());
        shootPollen(m, Alliance.RED, 3);
        m.advance(0.5);
        success(m.togglePause());
        assert m.paused() && !m.poweredActionsAllowed();
        String frozen = state(m);
        m.advance(1000);
        m.completeSettling();
        assert state(m).equals(frozen);
        try {
            move(m, 0, 0);
            throw new AssertionError("Paused robot synchronization accepted");
        } catch (IllegalStateException expected) {
            assert state(m).equals(frozen);
        }
        noPoweredActions(m);
        success(m.togglePause());
        assert !m.paused() && m.poweredActionsAllowed();
        m.advance(0.25);
        assert m.tips(Alliance.RED) == 1 && m.autoTips(Alliance.RED) == 1;
        m.advance(29.25);
        assert m.phase() == Phase.TRANSITION;
        success(m.togglePause());
        frozen = state(m);
        m.advance(20);
        assert state(m).equals(frozen);
        success(m.togglePause());
        m.advance(8);
        assert m.phase() == Phase.TELEOP;
        success(m.togglePause());
        frozen = state(m);
        m.advance(200);
        noPoweredActions(m);
        assert state(m).equals(frozen);
        success(m.togglePause());
        m.advance(120);
        assert m.phase() == Phase.SETTLING;
        success(m.togglePause());
        frozen = state(m);
        m.completeSettling();
        m.advance(10);
        assert state(m).equals(frozen);
        success(m.togglePause());
        m.completeSettling();
        assert m.phase() == Phase.FINISHED && !m.paused();
    }

    private static void testAutoAttribution() {
        BiobuzzModel transition = new BiobuzzModel();
        success(transition.startMatch());
        transition.advance(29.5);
        shootPollen(transition, Alliance.RED, 3);
        transition.advance(0.5);
        assert transition.phase() == Phase.TRANSITION && transition.tips(Alliance.RED) == 0;
        transition.advance(0.25);
        assert transition.autoTips(Alliance.RED) == 1 && transition.teleopTips(Alliance.RED) == 0;

        BiobuzzModel large = new BiobuzzModel();
        success(large.startMatch());
        large.advance(29.5);
        shootPollen(large, Alliance.RED, 3);
        large.advance(8.5);
        assert large.phase() == Phase.TELEOP;
        near(large.remainingSeconds(), 120);
        assert large.autoTips(Alliance.RED) == 1 && large.teleopTips(Alliance.RED) == 0;
        transition.advance(7.75);
        assert state(transition).equals(state(large)) : "one large step must equal boundary-sized steps";

        BiobuzzModel exactAutoEnd = new BiobuzzModel();
        success(exactAutoEnd.startMatch());
        exactAutoEnd.advance(29.25);
        shootPollen(exactAutoEnd, Alliance.RED, 3);
        exactAutoEnd.advance(0.75);
        assert exactAutoEnd.phase() == Phase.TRANSITION;
        assert exactAutoEnd.autoTips(Alliance.RED) == 1;
        BiobuzzModel wholeMatch = new BiobuzzModel();
        success(wholeMatch.startMatch());
        shootPollen(wholeMatch, Alliance.RED, 3);
        wholeMatch.advance(158);
        assert wholeMatch.phase() == Phase.SETTLING;
        assert wholeMatch.autoTips(Alliance.RED) == 1 && wholeMatch.teleopTips(Alliance.RED) == 0;
    }

    private static void testSettling() {
        BiobuzzModel m = new BiobuzzModel();
        success(m.startMatch());
        m.advance(157.5);
        assert m.phase() == Phase.TELEOP;
        shootPollen(m, Alliance.RED, 3);
        m.advance(0.5);
        assert m.phase() == Phase.SETTLING && m.tips(Alliance.RED) == 0;
        assert cellSize(m, Alliance.RED, 0) == 6;
        success(m.togglePause());
        m.completeSettling();
        assert m.phase() == Phase.SETTLING && m.tips(Alliance.RED) == 0;
        success(m.togglePause());
        m.completeSettling();
        assert m.phase() == Phase.FINISHED;
        assert m.tips(Alliance.RED) == 1 && m.teleopTips(Alliance.RED) == 1;
        assert m.autoTips(Alliance.RED) == 0;
        int red = m.score(Alliance.RED);
        int blue = m.score(Alliance.BLUE);
        Piece ground = first(m, Place.GROUND, Kind.POLLEN);
        m.syncGround(ground.id, -60, -71);
        move(m, -66.5, 36);
        m.advance(1000);
        m.completeSettling();
        assert m.score(Alliance.RED) == red && m.score(Alliance.BLUE) == blue;
        rejected(m, () -> m.refereeFoul(Alliance.RED, true));
        conserve(m);
    }

    private static void testRobotSnapshots() {
        for (Alliance a : Alliance.values()) {
            double sign = a == Alliance.RED ? -1 : 1;
            BiobuzzModel wall = model(a);
            success(wall.startMatch());
            wall.setRobot(sign * 59.5, 0, Math.PI / 4, 18, 18);
            wall.advance(30);
            assert wall.score(a) == 10 : "rotated corner still contacts the wall: no LEAVE";

            BiobuzzModel clear = model(a);
            success(clear.startMatch());
            clear.setRobot(sign * 58, 0, Math.PI / 4, 18, 18);
            clear.advance(30);
            assert clear.score(a) == 13;

            BiobuzzModel park = model(a);
            success(park.startMatch());
            park.setRobot(sign * 51, a == Alliance.RED ? 36 : -36, Math.PI / 4, 18, 18);
            park.advance(30);
            assert park.score(a) == 18 : "rotated partial zone overlap earns LEAVE and AUTO PARK";
            move(park, 0, 0);
            park.advance(128);
            park.completeSettling();
            assert park.score(a) == 18 : "AUTO snapshot is not recomputed at TELEOP end";

            BiobuzzModel latePark = model(a);
            success(latePark.startMatch());
            move(latePark, 0, 0);
            latePark.advance(38);
            latePark.setRobot(sign * 51, a == Alliance.RED ? 36 : -36, Math.PI / 4, 18, 18);
            latePark.advance(120);
            move(latePark, 0, 0);
            latePark.completeSettling();
            assert latePark.score(a) == 18 : "TELEOP PARK is captured before settling pose changes";
        }
    }

    private static void testFouls() {
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            rejected(m, () -> m.refereeFoul(a, false));
            success(m.startMatch());
            int own = m.score(a);
            int other = m.score(a.opponent());
            success(m.refereeFoul(a, false));
            assert m.score(a) == own && m.score(a.opponent()) == other + 5;
            success(m.refereeFoul(a, true));
            assert m.score(a.opponent()) == other + 25;
            rejected(m, () -> m.refereeFoul(null, false));
            shootPollen(m, a, 3);
            m.advance(0.75);
            other = m.score(a.opponent());
            emptyHeld(m, 1);
            assert m.score(a.opponent()) == other : "early pollen does not incur a nectar foul";
            pickUp(m, first(m, Place.GROUND, nectar(a)).id);
            other = m.score(a.opponent());
            moveToFlower(m, 0);
            success(m.depositFlower(0));
            assert m.flowerOwner(0) == a;
            assert m.score(a.opponent()) == other + 20 : "early AUTO nectar scores but incurs a major";
            m.advance(37.25);
            assert m.phase() == Phase.TELEOP;
            pickUp(m, first(m, Place.GROUND, nectar(a)).id);
            other = m.score(a.opponent());
            moveToFlower(m, 0);
            success(m.depositFlower(0));
            assert m.score(a.opponent()) == other + 20 : "nectar is early above sixty seconds";
            m.advance(60);
            pickUp(m, first(m, Place.GROUND, nectar(a)).id);
            other = m.score(a.opponent());
            moveToFlower(m, 0);
            success(m.depositFlower(0));
            assert m.score(a.opponent()) == other : "nectar is legal at exactly sixty seconds";
            conserve(m);

            BiobuzzModel practice = model(a);
            shootPollen(practice, a, 3);
            practice.advance(0.75);
            emptyHeld(practice, 1);
            pickUp(practice, first(practice, Place.GROUND, nectar(a)).id);
            other = practice.score(a.opponent());
            moveToFlower(practice, 0);
            success(practice.depositFlower(0));
            assert practice.score(a.opponent()) == other : "practice must not award early-nectar fouls";
        }
    }

    private static void testFlowerScoringBand() {
        BiobuzzModel m = new BiobuzzModel();
        List<Piece> seed = m.flowerPieces(0);
        assert seed.size() == 4 && m.flowerScoredCount(0) == 3;
        assert !seed.get(0).flowerScored;
        for (int i = 1; i < 4; i++) assert seed.get(i).flowerScored;
        Set<Integer> original = new HashSet<>();
        for (Piece p : seed) original.add(p.id);
        emptyHeld(m, 0);
        assert m.flowerPieces(0).size() == 8 && m.flowerScoredCount(0) == 7;
        assert seed.size() == 4 : "old ordered snapshots must not gain newly deposited pieces";
        shootPollen(m, Alliance.RED, 3);
        m.advance(0.75);
        pickUp(m, first(m, Place.GROUND, Kind.RED_NECTAR).id);
        moveToFlower(m, 0);
        success(m.depositFlower(0));
        assert m.flowerOwner(0) == null && m.flowerBottomOwner(0) == null
                : "nectar above the top ring must not own the flower or receive a bonus";
        int nectarId = m.flowerPieces(0).get(8).id;
        assert !piece(m, nectarId).flowerScored;
        success(m.retrieveFlower(0));
        assert piece(m, seed.get(0).id).place == Place.HELD
                : "retrieval uses stack order, not global piece order";
        assert piece(m, nectarId).flowerScored : "retrieval lowers the waiting nectar into the scoring band";
        success(m.retrieveFlower(0));
        assert m.flowerOwner(0) == Alliance.RED && m.flowerBottomOwner(0) == Alliance.RED
                : "retrieval lowers the waiting nectar into the scoring band";
        assert piece(m, nectarId).flowerScored;
        assert m.flowerScoredCount(0) == 6;
        List<Piece> lowered = m.flowerPieces(0);
        assert lowered.get(0).id == seed.get(2).id && !lowered.get(0).flowerScored;
        assert original.contains(lowered.get(1).id);
        try {
            lowered.clear();
        } catch (UnsupportedOperationException expected) {
            // A detached mutable or unmodifiable snapshot is safe.
        }
        assert m.flowerPieces(0).size() == 7;
        conserve(m);
    }

    private static void testHeldGardens() {
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            for (Piece p : m.pieces())
                if (p.place == Place.GROUND) success(m.syncGround(p.id, 0, 0));
            double sign = a == Alliance.RED ? -1 : 1;
            move(m, sign * 60, sign * 69);
            assert m.gardenCount(a) == 4 : "infinitely tall gardens include overlapping held pollen";
            move(m, sign * 60, sign * 68.5);
            assert m.gardenCount(a) == 0;
            m.reset();
            success(m.startMatch());
            m.advance(158);
            move(m, sign * 60, sign * 69);
            int atRest = m.gardenCount(a);
            assert atRest == 8;
            m.completeSettling();
            String finalInventory = inventory(m);
            int finalScore = m.score(a);
            move(m, 0, 0);
            assert inventory(m).equals(finalInventory) : "restart pose preparation cannot move final pieces";
            assert m.gardenCount(a) == atRest && m.score(a) == finalScore;
            m.reset();
            assertSeed(m);
        }
    }

    private static void testFractionalClock() {
        BiobuzzModel m = new BiobuzzModel();
        success(m.startMatch());
        shootPollen(m, Alliance.RED, 3);
        for (int i = 0; i < 75; i++) m.advance(0.01);
        assert m.autoTips(Alliance.RED) == 1;
        for (int i = 0; i < 2925; i++) m.advance(0.01);
        assert m.phase() == Phase.TRANSITION && !m.poweredActionsAllowed();
        near(m.remainingSeconds(), 8);
        for (int i = 0; i < 400; i++) m.advance(0.02);
        assert m.phase() == Phase.TELEOP;
        near(m.remainingSeconds(), 120);
        for (int i = 0; i < 3000; i++) m.advance(0.02);
        near(m.remainingSeconds(), 60);
        assert m.unlocked(Alliance.RED) == 5 && m.unlocked(Alliance.BLUE) == 5;
        for (int i = 0; i < 3000; i++) m.advance(0.02);
        assert m.phase() == Phase.SETTLING && !m.poweredActionsAllowed();
        near(m.remainingSeconds(), 0);
        conserve(m);
    }

    private static void testRanking() {
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            rejected(m, () -> m.refereeOtherMovement(a, 1, 1, 1));
            assert !m.ranking(a).finalized && m.ranking(a).total == 0;
            shootPollen(m, a, 3);
            m.advance(0.75);
            for (int tip = 2; tip <= 7; tip++) {
                shootPollen(m, a, 8);
                m.advance(0.75);
            }
            assert m.tips(a) == 7 && m.ranking(a).pollinator1 == 0
                    : "practice tips must never award official ranking points";
            m.reset();
            success(m.startMatch());
            rejected(m, () -> m.refereeOtherMovement(null, 0, 0, 0));
            rejected(m, () -> m.refereeOtherMovement(a, 2, 0, 0));
            rejected(m, () -> m.refereeOtherMovement(a, 0, -1, 0));
            rejected(m, () -> m.refereeOtherMovement(a.opponent(), 0, 0, 3));
            success(m.refereeOtherMovement(a, 0, 1, 1));
            move(m, 0, 0);
            m.advance(30);
            assert m.movementPoints(a) == 13 && m.ranking(a).swarm == 0;
            m.advance(8);
            success(m.refereeOtherMovement(a, 1, 1, 1));
            assert m.movementPoints(a) == 16 && m.ranking(a).swarm == 1;
            success(m.refereeOtherMovement(a.opponent(), 0, 2, 1));
            assert m.movementPoints(a.opponent()) == 15 && m.ranking(a.opponent()).swarm == 0;
            success(m.refereeOtherMovement(a.opponent(), 2, 1, 1));
            assert m.movementPoints(a.opponent()) == 16 && m.ranking(a.opponent()).swarm == 1;
            success(m.refereeOtherMovement(a.opponent(), 0, 0, 0));
            assert m.ranking(a.opponent()).swarm == 0 : "referee evidence replaces, never accumulates";
            for (int tip = 1; tip <= 7; tip++) {
                shootPollen(m, a, tip == 1 ? 3 : 8);
                m.advance(0.75);
                BiobuzzModel.Ranking r = m.ranking(a);
                assert r.pollinator1 == (tip >= 4 ? 1 : 0);
                assert r.pollinator2 == (tip >= 7 ? 1 : 0);
                assert r.outcome == 0 && !r.finalized;
            }
            success(m.togglePause());
            rejected(m, () -> m.refereeOtherMovement(a, 0, 0, 0));
            success(m.togglePause());
            m.advance(120);
            m.completeSettling();
            BiobuzzModel.Ranking win = m.ranking(a);
            assert win.finalized && win.total == 6 && win.outcome == 3;
            assert m.ranking(a.opponent()).outcome == 0;
            rejected(m, () -> m.refereeOtherMovement(a, 0, 0, 0));
            m.reset();
            assert win.total == 6 : "ranking results are detached snapshots";
            success(m.startMatch());
            m.advance(158);
            m.completeSettling();
            assert m.ranking(a).outcome == 1 && m.ranking(a.opponent()).outcome == 1;
            assert m.ranking(a).total == 1;
        }
    }

    private static void testCompleteReset() {
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            String seed = state(m);
            success(m.startMatch());
            shootPollen(m, a, 3);
            m.advance(0.1);
            success(m.refereeFoul(a, true));
            success(m.refereeOtherMovement(a.opponent(), 2, 2, 2));
            success(m.togglePause());
            m.reset();
            assert state(m).equals(seed) : "reset must clear pending delay, pause, clock and every score pool";
            m.advance(1000);
            assert state(m).equals(seed) : "no pending pre-reset tip can survive";
            assert m.movementPoints(a.opponent()) == 0;
            assert m.ranking(a).total == 0 && !m.ranking(a).finalized;
            assertSeed(m);
        }
    }

    private static void testTransitionLoading() {
        for (Alliance a : Alliance.values()) {
            BiobuzzModel m = model(a);
            success(m.startMatch());
            shootPollen(m, a, 3);
            m.advance(30);
            assert m.phase() == Phase.TRANSITION && !m.poweredActionsAllowed();
            assert m.unlocked(a) == 1;
            int held = m.heldCount();
            success(m.humanLoad());
            assert m.unlocked(a) == 0 && m.heldCount() == held
                    : "no-power transition does not prohibit legal human floor introduction";
            Piece exited = first(m, Place.GROUND, nectar(a));
            success(m.syncGround(exited.id, 80, 0));
            success(m.humanLoad());
            assert piece(m, exited.id).place == Place.GROUND && m.unlocked(a) == 0;
            m.advance(128);
            assert m.phase() == Phase.SETTLING;
            success(m.syncGround(exited.id, 80, 0));
            rejected(m, m::humanLoad);
            m.completeSettling();
            rejected(m, m::humanLoad);
            conserve(m);
        }
    }

    private static BiobuzzModel model(Alliance a) {
        BiobuzzModel m = new BiobuzzModel();
        m.selectAlliance(a);
        return m;
    }

    private static Kind nectar(Alliance a) {
        return a == Alliance.RED ? Kind.RED_NECTAR : Kind.BLUE_NECTAR;
    }

    private static void move(BiobuzzModel m, double x, double y) {
        m.setRobot(x, y, 0, 18, 18);
    }

    private static void moveToFlower(BiobuzzModel m, int flower) {
        move(m, FLOWERS[flower][0], FLOWERS[flower][1]);
    }

    private static void moveToHive(BiobuzzModel m, Alliance a, double distance) {
        move(m, (a == Alliance.RED ? -12.75 : 12.75) + distance,
                m.upwardCell(a) == 0 ? -20 : 20);
    }

    private static void shoot(BiobuzzModel m, Alliance hive) {
        moveToHive(m, hive, 0);
        success(m.launchHive(hive));
        conserve(m);
    }

    private static void shootPollen(BiobuzzModel m, Alliance hive, int amount) {
        for (int i = 0; i < amount; i++) {
            if (m.heldCount() == 0) {
                pickUp(m, first(m, Place.GROUND, Kind.POLLEN).id);
            }
            for (Piece p : m.pieces()) {
                if (p.place == Place.HELD) {
                    assert p.kind == Kind.POLLEN;
                }
            }
            shoot(m, hive);
        }
    }

    private static void pickUp(BiobuzzModel m, int id) {
        Piece p = piece(m, id);
        assert p.place == Place.GROUND;
        move(m, p.x, p.y);
        success(m.intake(id));
        assert piece(m, id).place == Place.HELD;
        conserve(m);
    }

    private static void emptyHeld(BiobuzzModel m, int flower) {
        moveToFlower(m, flower);
        while (m.heldCount() > 0) {
            success(m.depositFlower(flower));
        }
        conserve(m);
    }

    private static Piece first(BiobuzzModel m, Place place, Kind kind) {
        return m.pieces().stream().filter(p -> p.place == place && p.kind == kind)
                .min(Comparator.comparingInt(p -> p.id))
                .orElseThrow(() -> new AssertionError("No " + kind + " in " + place));
    }

    private static Piece piece(BiobuzzModel m, int id) {
        for (Piece p : m.pieces()) {
            if (p.id == id) {
                return p;
            }
        }
        throw new AssertionError("Missing piece " + id);
    }

    private static int count(BiobuzzModel m, Place place, Kind kind) {
        return ids(m, place, kind).size();
    }

    private static Set<Integer> ids(BiobuzzModel m, Place place) {
        return ids(m, place, null);
    }

    private static Set<Integer> ids(BiobuzzModel m, Place place, Kind kind) {
        Set<Integer> result = new HashSet<>();
        for (Piece p : m.pieces()) {
            if (p.place == place && (kind == null || p.kind == kind)) {
                result.add(p.id);
            }
        }
        return result;
    }

    private static int flowerSize(BiobuzzModel m, int flower) {
        int result = 0;
        for (Piece p : m.pieces()) {
            if (p.place == Place.FLOWER && p.target == flower) {
                result++;
            }
        }
        return result;
    }

    private static Set<Integer> cellIds(BiobuzzModel m, Alliance a, int cell) {
        Set<Integer> result = new HashSet<>();
        int target = a == Alliance.RED ? 0 : 1;
        for (Piece p : m.pieces()) {
            if (p.place == Place.CELL && p.target == target && p.cell == cell) {
                result.add(p.id);
            }
        }
        return result;
    }

    private static int cellSize(BiobuzzModel m, Alliance a, int cell) {
        return cellIds(m, a, cell).size();
    }

    private static int removedHeld(BiobuzzModel m, Set<Integer> before) {
        Set<Integer> removed = new HashSet<>(before);
        removed.removeAll(ids(m, Place.HELD));
        assert removed.size() == 1;
        return removed.iterator().next();
    }

    private static void conserve(BiobuzzModel m) {
        List<Piece> pieces = m.pieces();
        assert pieces.size() == 56 : "piece transfers cannot fabricate or delete";
        Set<Integer> unique = new HashSet<>();
        int pollen = 0;
        int red = 0;
        int blue = 0;
        for (Piece p : pieces) {
            assert unique.add(p.id) : "duplicate id " + p.id;
            assert p.place != null && p.kind != null;
            assert Double.isFinite(p.x) && Double.isFinite(p.y);
            switch (p.kind) {
                case POLLEN: pollen++; break;
                case RED_NECTAR: red++; break;
                case BLUE_NECTAR: blue++; break;
                default: throw new AssertionError("Unexpected kind");
            }
        }
        assert pollen == 40 && red == 8 && blue == 8;
        assert m.heldCount() == ids(m, Place.HELD).size();
        assert m.heldCount() <= 4;
    }

    private static String inventory(BiobuzzModel m) {
        List<Piece> sorted = new ArrayList<>(m.pieces());
        sorted.sort(Comparator.comparingInt(p -> p.id));
        StringBuilder out = new StringBuilder();
        for (Piece p : sorted) {
            out.append(p.id).append(':').append(p.kind).append(':').append(p.place)
                    .append(':').append(p.x).append(':').append(p.y)
                    .append(':').append(p.target).append(':').append(p.cell).append(';');
        }
        return out.toString();
    }

    private static String state(BiobuzzModel m) {
        StringBuilder out = new StringBuilder(inventory(m));
        out.append(m.alliance()).append(':').append(m.phase()).append(':')
                .append(m.paused()).append(':').append(m.poweredActionsAllowed())
                .append(':').append(m.remainingSeconds());
        for (Alliance a : Alliance.values()) {
            out.append(':').append(m.score(a)).append(':').append(m.tips(a))
                    .append(':').append(m.autoTips(a)).append(':').append(m.teleopTips(a))
                    .append(':').append(m.unlocked(a)).append(':').append(m.gardenCount(a))
                    .append(':').append(m.upwardCell(a));
            out.append(':').append(m.movementPoints(a)).append(':').append(m.ranking(a).total);
        }
        for (int i = 0; i < 4; i++) {
            out.append(':').append(m.flowerOwner(i)).append(':').append(m.flowerBottomOwner(i));
            out.append(':').append(m.flowerScoredCount(i));
        }
        return out.toString();
    }

    private interface Action {
        Result perform();
    }

    private static void success(Result result) {
        assert result != null && result.ok : result == null ? "null result" : result.message;
        assert result.message != null;
    }

    private static void rejected(BiobuzzModel m, Action action) {
        String before = state(m);
        Result result = action.perform();
        assert result != null && !result.ok : "invalid action accepted";
        assert result.message != null && !result.message.trim().isEmpty();
        assert state(m).equals(before) : "rejected action changed rules state: " + result.message;
        conserve(m);
    }

    private static void expectIllegal(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void noPoweredActions(BiobuzzModel m) {
        Piece ground = first(m, Place.GROUND, Kind.POLLEN);
        rejected(m, () -> m.intake(ground.id));
        rejected(m, () -> m.launchHive(m.alliance()));
        rejected(m, () -> m.depositFlower(0));
        rejected(m, () -> m.retrieveFlower(0));
        rejected(m, m::humanLoad);
        rejected(m, m::preload);
    }

    private static void near(double actual, double expected) {
        assert Math.abs(actual - expected) <= EPS : actual + " != " + expected;
    }
}
