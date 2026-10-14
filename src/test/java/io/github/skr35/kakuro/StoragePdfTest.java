package io.github.skr35.kakuro;

import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.pdf.PdfBooklet;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StoragePdfTest {
    @TempDir Path temp;

    @Test void persistenceRoundTripDeduplicationAndFailedSaveIsolation() throws Exception {
        Path file = temp.resolve("puzzles.db");
        Puzzle puzzle = Fixtures.unique();
        long id;
        try (var repository = new PuzzleRepository(file)) {
            var insert = repository.save(puzzle);
            assertTrue(insert.inserted()); id = insert.id();
            var duplicate = repository.save(new Puzzle(puzzle.board(), puzzle.solution(), puzzle.difficulty(),
                    puzzle.score(), 999, puzzle.nodes()));
            assertFalse(duplicate.inserted()); assertEquals(id, duplicate.id());
            var invalidStatistics = new Puzzle(puzzle.board(), puzzle.solution(), Difficulty.EASY, 0, 1, 1);
            assertThrows(IllegalArgumentException.class, () -> repository.save(invalidStatistics));
            assertEquals(1, repository.all().size());
        }
        try (var repository = new PuzzleRepository(file)) {
            Puzzle loaded = repository.find(id).orElseThrow().puzzle();
            assertEquals(puzzle.fingerprint(), loaded.fingerprint());
            assertArrayEquals(puzzle.solution(), loaded.solution());
            assertEquals(puzzle.score(), loaded.score());
            assertTrue(repository.find(999).isEmpty());
        }
    }

    @Test void unknownDatabasesAreNotModified() throws Exception {
        Path file = temp.resolve("unrelated.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var s = connection.createStatement()) {
            s.execute("CREATE TABLE unrelated(value TEXT)");
        }
        assertThrows(SQLException.class, () -> new PuzzleRepository(file));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var s = connection.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='puzzles'")) {
            assertEquals(0, r.getInt(1));
        }
    }

    @Test void bookletHasPuzzleAndSeparateSolutionPagesAndRenders() throws Exception {
        Path file = temp.resolve("booklet.pdf");
        Puzzle puzzle = Fixtures.unique();
        new PdfBooklet().write(file, List.of(puzzle,puzzle,puzzle,puzzle,puzzle));
        try (var pdf = Loader.loadPDF(file.toFile())) {
            assertEquals(7, pdf.getNumberOfPages());
            assertEquals(PDRectangle.A4.getWidth(), pdf.getPage(0).getMediaBox().getWidth());
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(pdf);
            assertTrue(text.contains("KAKURO")); assertTrue(text.contains("SOLUTIONS"));
            assertTrue(text.contains("Puzzle 5 - EASY"));
            var image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            assertTrue(image.getWidth() > 500 && image.getHeight() > 800);
        }
    }
}
