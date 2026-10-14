import io.github.skr35.kakuro.generator.*;
import io.github.skr35.kakuro.geometry.*;
import io.github.skr35.kakuro.model.*;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import io.github.skr35.kakuro.pdf.PdfBooklet;
import java.nio.file.*;
import java.io.PrintWriter;
import java.util.*;

/** Reproducible sequential benchmark; every failure remains in the CSV, with denominators. */
public class DimensionBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException("Usage: output-dir candidate-budget seed difficulty [rowsxcols,...]");
        Path dir = Path.of(args[0]); Files.createDirectories(dir);
        int budget = Integer.parseInt(args[1]); Random seeds = new Random(Long.parseLong(args[2]));
        Difficulty difficulty = Difficulty.valueOf(args[3].toUpperCase(Locale.ROOT));
        String dimensions = args.length > 4 ? args[4] : "11x11,12x12,13x13,14x14,15x15,8x12,10x15,10x20,15x10,20x10";
        // Never silently mix a new benchmark with earlier records.
        if (Files.exists(dir.resolve("puzzles.db"))) throw new IllegalArgumentException("Use a fresh output directory");
        List<Puzzle> accepted = new ArrayList<>(); List<Board> boards = new ArrayList<>();
        try (var repository = new PuzzleRepository(dir.resolve("puzzles.db"));
             var csv = new PrintWriter(Files.newBufferedWriter(dir.resolve("benchmark.csv")))) {
            csv.println("rows,cols,difficulty,seed,status,seconds,candidates,geometry_requests,geometry_masks,geometry_accepted,geometry_rate,geometry_states,geometry_exhaustions,fill_attempts,fill_successes,solver_calls,unique,uniqueness_rate,in_band,difficulty_rate,inconclusive,diversity_rejected,score");
            for (String dimension : dimensions.split(",")) {
                String[] axes = dimension.split("x"); int rows = Integer.parseInt(axes[0]), cols = Integer.parseInt(axes[1]);
                long seed = seeds.nextLong(), start = System.nanoTime();
                var session = new Generator.Session(difficulty, rows, cols, seed, budget);
                String status = "ACCEPTED"; double score = Double.NaN;
                System.out.println("Starting " + dimension + " " + difficulty + " seed=" + seed + " budget=" + budget);
                try {
                    var result = session.next(board -> GeometryDiversity.accepts(board, boards),
                            (board,values) -> new SolutionQualityEvaluator().acceptsBatch(board,values,accepted));
                    Puzzle puzzle = result.puzzle(); repository.save(puzzle);
                    accepted.add(puzzle); boards.add(puzzle.board()); score = puzzle.score();
                } catch (IllegalStateException e) { status = "BUDGET_EXHAUSTED"; System.out.println(e.getMessage()); }
                double seconds = (System.nanoTime() - start) / 1e9;
                var s = session.statistics();
                csv.printf(Locale.ROOT,"%d,%d,%s,%d,%s,%.3f,%d,%d,%d,%d,%.6f,%d,%d,%d,%d,%d,%d,%.6f,%d,%.6f,%d,%d,%.6f%n",
                        rows,cols,difficulty,seed,status,seconds,s.candidates(),s.geometryRequests(),s.geometryMasks(),s.geometryAccepted(),
                        ratio(s.geometryAccepted(),s.geometryMasks()),s.geometryStates(),s.geometryExhaustions(),s.fillAttempts(),s.fillSuccesses(),
                        s.solverCalls(),s.unique(),ratio(s.unique(),s.solverCalls()),s.inBand(),ratio(s.inBand(),s.unique()),s.inconclusive(),s.diversityRejected(),score);
                csv.flush();
                System.out.printf(Locale.ROOT,"%s %s %.3fs %s%n",dimension,status,seconds,s);
                if (!accepted.isEmpty()) new PdfBooklet().write(dir.resolve("review.pdf"),accepted);
            }
        }
        if (!accepted.isEmpty()) GeometryReport.main(new String[]{dir.resolve("puzzles.db").toString(),dir.toString()});
    }
    private static double ratio(long a,long b) { return b == 0 ? 0 : a/(double)b; }
}
