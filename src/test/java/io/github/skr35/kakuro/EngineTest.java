package io.github.skr35.kakuro;

import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.generator.Generator;
import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.solver.Solver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class EngineTest {
    @Test void knownUniqueMultipleAndUnsatisfiable() {
        Solver solver = new Solver();
        var unique = solver.solve(Fixtures.tiny(3,7,4,6), 100);
        assertEquals(Solver.Status.UNIQUE, unique.status());
        assertArrayEquals(new int[]{0,0,0,0,1,2,0,3,4}, unique.solutions().getFirst());
        assertEquals(Solver.Status.MULTIPLE, solver.solve(Fixtures.tiny(3,3,3,3), 100).status());
        assertEquals(Solver.Status.UNSATISFIABLE, solver.solve(Fixtures.tiny(3,3,3,4), 100).status());
    }

    @Test void budgetExhaustionIsNeverUniqueEvenAfterFindingOneSolution() {
        Solver solver = new Solver();
        assertEquals(Solver.Status.INCONCLUSIVE, solver.solve(Fixtures.tiny(3,3,3,3), 1).status());
        var partial = solver.solve(Fixtures.tiny(3,3,3,3), 2);
        assertEquals(1, partial.solutions().size());
        assertEquals(Solver.Status.INCONCLUSIVE, partial.status());
    }

    @Test void crossChecksManySmallPuzzlesAgainstIndependentSearch() {
        Random random = new Random(12);
        for (int i = 0; i < 150; i++) {
            Board board = Fixtures.tiny(3 + random.nextInt(15), 3 + random.nextInt(15),
                    3 + random.nextInt(15), 3 + random.nextInt(15));
            var independent = new IndependentSolver(board).solve();
            var actual = new Solver().solve(board, 10_000);
            assertEquals(independent.size(), actual.solutions().size());
            assertNotEquals(Solver.Status.INCONCLUSIVE, actual.status());
            if (independent.size() == 1) assertArrayEquals(independent.getFirst(), actual.solutions().getFirst());
        }
    }

    @Test void geometryInvariantsAcrossSizesAndSeeds() {
        for (int size = 7; size <= 10; size++) for (int seed = 0; seed < 40; seed++) {
            boolean[] mask = Generator.geometry(size, new Random(seed), Difficulty.EVIL);
            var runs = Board.geometry(size, mask);
            assertFalse(runs.isEmpty());
            int[] membership = new int[mask.length];
            for (var run : runs) {
                assertTrue(run.cells().size() >= 2 && run.cells().size() <= 9);
                assertFalse(mask[run.clueCell()]);
                for (int cell : run.cells()) membership[cell]++;
            }
            for (int i = 0; i < mask.length; i++) assertEquals(mask[i] ? 2 : 0, membership[i]);
        }
    }

    @Test void invalidGeometryAndSolutionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Board.geometry(3, new boolean[9]));
        boolean[] singleton = new boolean[9]; singleton[4] = true;
        assertThrows(IllegalArgumentException.class, () -> Board.geometry(3, singleton));
        boolean[] disconnected = new boolean[49];
        for (int c : new int[]{8,9,15,16,32,33,39,40}) disconnected[c] = true;
        assertThrows(IllegalArgumentException.class, () -> Board.geometry(7, disconnected));
        Board board = Fixtures.tiny(3,7,4,6);
        assertThrows(IllegalArgumentException.class, () -> board.validateSolution(new int[]{0,0,0,0,1,1,0,3,4}));
        assertThrows(IllegalArgumentException.class, () -> board.validateSolution(new int[]{0,0,0,0,1,2,0,4,3}));
        assertThrows(IllegalArgumentException.class, () -> Generator.geometry(21, new Random(1), Difficulty.EASY));
    }

    @Test void scoringHasExplicitNonoverlappingBands() {
        double expected = 10 * (StrictMath.log(3) / StrictMath.log(2) + 1) / 4;
        assertEquals(expected, Difficulty.score(Fixtures.tiny(3,7,4,6), 1), 1e-10);
        for (double score : new double[]{0,11.999,12,19.999,20,27.999,28,100}) {
            assertEquals(1, Arrays.stream(Difficulty.values()).filter(d -> d.accepts(score)).count());
        }
    }

    @ParameterizedTest @EnumSource(Difficulty.class)
    void generatesUniqueReproduciblePuzzlesInEveryBand(Difficulty difficulty) {
        int size = switch (difficulty) { case EASY -> 8; case MEDIUM -> 9; default -> 10; };
        long seed = switch (difficulty) {
            case EASY, EVIL -> -7871804806913026055L;
            case MEDIUM -> 3628289955520214741L;
            case HARD -> -1785764124361111993L;
        };
        // Broad two-dimensional masks need more candidates for some retained regression seeds.
        var generated = new Generator().generate(difficulty, size, seed, 5_000);
        var repeat = new Generator().generate(difficulty, size, seed, 5_000);
        var puzzle = generated.puzzle();
        assertEquals(puzzle.fingerprint(), repeat.puzzle().fingerprint());
        assertEquals(generated.attempts(), repeat.attempts());
        assertTrue(difficulty.accepts(puzzle.score()));
        var independent = new IndependentSolver(puzzle.board()).solve();
        assertEquals(1, independent.size());
        assertArrayEquals(puzzle.solution(), independent.getFirst());
        puzzle.board().validateSolution(independent.getFirst());
    }

    @Test void cancellationIsExplicit() {
        Thread.currentThread().interrupt();
        try { assertThrows(CancellationException.class, () -> new Generator().generate(Difficulty.EASY, 8, 1, 100)); }
        finally { Thread.interrupted(); }
    }
}
