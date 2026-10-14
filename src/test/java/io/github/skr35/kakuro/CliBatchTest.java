package io.github.skr35.kakuro;

import io.github.skr35.kakuro.batch.BatchGenerator;
import io.github.skr35.kakuro.cli.KakuroCommand;
import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CliBatchTest {
    @TempDir Path temp;
    private record Execution(int code, String out, String err) { }
    private Execution execute(String... args) {
        StringWriter out = new StringWriter(), err = new StringWriter();
        int code = new CommandLine(new KakuroCommand()).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(args);
        return new Execution(code, out.toString(), err.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--size 21", "--size 6", "--workers 0", "--max-attempts 0", "--easy -1",
            "--easy-size 21", "--export-ids 1", "--verify-db", "--unknown", "--seed abc"})
    void invalidArgumentsFail(String args) { assertEquals(2, execute(args.split(" ")).code()); }

    @Test void zeroCountIsANoOp() { assertEquals(0, execute("--easy", "0", "--no-pdf").code()); }

    @Test void generationPersistenceDuplicateAndExportCommands() throws Exception {
        String db = temp.resolve("puzzles.db").toString();
        // This retained seed needs 2,657 candidates with the additional solution-quality gate.
        // The contract tested here is persistence/deduplication, not a 500-candidate success promise.
        String[] args = {"--easy", "1", "--size", "8", "--seed", "20261012", "--max-attempts", "5000", "--db", db, "--no-pdf"};
        var first = execute(args);
        assertEquals(0, first.code(), first.err());
        assertTrue(first.out().contains("EASY 8x8"));
        var duplicate = execute(args);
        assertEquals(1, duplicate.code());
        assertTrue(duplicate.err().contains("DUPLICATE"));
        assertEquals(0, execute("--db", db, "--verify-db").code());
        String pdf = temp.resolve("export.pdf").toString();
        assertEquals(0, execute("--db", db, "--export-ids", "1,1", "--outfile", pdf).code());
        assertTrue(Files.size(Path.of(pdf)) > 1000);
        assertEquals(1, execute("--db", db, "--export-ids", "999", "--outfile", pdf).code());
        try (var repository = new PuzzleRepository(Path.of(db))) { assertEquals(1, repository.all().size()); }
    }

    @Test void failureDoesNotWriteAnEmptyPdf() {
        Path output = temp.resolve("failed.pdf");
        var result = execute("--evil", "1", "--size", "10", "--seed", "20261012", "--max-attempts", "1", "--outfile", output.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("FAILED"));
        assertFalse(Files.exists(output));
    }

    @Test void parallelOutputIsIndependentOfWorkerCountAndFailuresStayInOrder() throws Exception {
        var tasks = List.of(
                new BatchGenerator.Task(1, Difficulty.MEDIUM, 9, 3628289955520214741L, 500),
                new BatchGenerator.Task(2, Difficulty.EVIL, 10, -7871804806913026055L, 1),
                new BatchGenerator.Task(3, Difficulty.HARD, 10, -1785764124361111993L, 500));
        List<String> serial = new ArrayList<>(), parallel = new ArrayList<>();
        new BatchGenerator().run(tasks, 1, r -> serial.add(r.failure() == null ? r.generated().puzzle().fingerprint() : r.failure()));
        new BatchGenerator().run(tasks, 3, r -> parallel.add(r.failure() == null ? r.generated().puzzle().fingerprint() : r.failure()));
        assertEquals(serial, parallel);
        assertEquals(3, serial.size());
        assertTrue(serial.get(1).contains("Could not generate"));
    }

    @Test void cancellationPreservesPreviouslyCommittedPuzzles() throws Exception {
        Path db = temp.resolve("cancel.db");
        var task = new BatchGenerator.Task(1, Difficulty.MEDIUM, 9, 3628289955520214741L, 500);
        try (var repository = new PuzzleRepository(db)) {
            assertThrows(InterruptedException.class, () -> new BatchGenerator().run(List.of(task, task), 2, result -> {
                repository.save(result.generated().puzzle());
                throw new InterruptedException("Test cancellation after first commit");
            }));
        }
        try (var repository = new PuzzleRepository(db)) { assertEquals(1, repository.all().size()); }
    }
}
