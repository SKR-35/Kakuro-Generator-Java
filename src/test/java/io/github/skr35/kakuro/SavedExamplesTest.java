package io.github.skr35.kakuro;

import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;
import java.util.EnumSet;
import static org.junit.jupiter.api.Assertions.*;

/** Optional acceptance audit of the actual CLI-created SQLite artifacts. */
class SavedExamplesTest {
    @Test
    @EnabledIfSystemProperty(named = "kakuro.acceptance.db", matches = ".+")
    void independentlyProvesEverySavedExampleUnique() throws Exception {
        EnumSet<Difficulty> found = EnumSet.noneOf(Difficulty.class);
        try (var repository = new PuzzleRepository(Path.of(System.getProperty("kakuro.acceptance.db")))) {
            for (var saved : repository.all()) {
                var puzzle = saved.puzzle();
                IndependentSolver solver = new IndependentSolver(puzzle.board());
                var solutions = solver.solve();
                assertEquals(1, solutions.size(), "ID " + saved.id());
                assertArrayEquals(puzzle.solution(), solutions.getFirst());
                puzzle.board().validateSolution(solutions.getFirst());
                assertTrue(puzzle.difficulty().accepts(puzzle.score()));
                found.add(puzzle.difficulty());
                System.out.println("Independent audit ID " + saved.id() + " " + puzzle.difficulty() + ": UNIQUE (" + solver.nodes + " nodes)");
            }
        }
        assertEquals(EnumSet.allOf(Difficulty.class), found);
    }
}
