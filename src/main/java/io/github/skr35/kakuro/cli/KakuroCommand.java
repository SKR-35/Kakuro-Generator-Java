package io.github.skr35.kakuro.cli;

import io.github.skr35.kakuro.batch.BatchGenerator;
import io.github.skr35.kakuro.analysis.LogicalAnalyzer;
import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.generator.Generator;
import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.pdf.PdfBooklet;
import io.github.skr35.kakuro.pdf.PrintLayout;
import io.github.skr35.kakuro.pdf.SolutionLayout;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import io.github.skr35.kakuro.solver.Solver;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

@Command(name = "kakuro", mixinStandardHelpOptions = true, version = "Kakuro Java 0.1.0", subcommands = {BankCommand.class, BankReportCommand.class, ProbeCommand.class},
        description = "Generate proven-unique Kakuro puzzles (dimensions 7..20), SQLite records and A4 or KDP PDF booklets.",
        footer = "Difficulty bands are computer scores, not calibrated human-solving ratings.\n"
                + "Exit codes: 0 success, 1 failed/duplicate slots or I/O failure, 2 usage error, 130 cancelled.")
public final class KakuroCommand implements Callable<Integer> {
    @Option(names = "--easy", description = "Number of Easy puzzles") Integer easy;
    @Option(names = "--medium", description = "Number of Medium puzzles (default: one when no counts given)") Integer medium;
    @Option(names = "--hard", description = "Number of Hard puzzles") Integer hard;
    @Option(names = "--evil", description = "Number of Evil puzzles") Integer evil;
    @Option(names = "--size", description = "Global square board size, 7..20 (default: 9)", defaultValue = "9") int size;
    @Option(names = "--rows", description = "Board rows, 7..20; requires --cols; excludes size options") Integer rows;
    @Option(names = "--cols", description = "Board columns, 7..20; requires --rows") Integer cols;
    @Option(names = "--easy-size") Integer easySize;
    @Option(names = "--medium-size") Integer mediumSize;
    @Option(names = "--hard-size") Integer hardSize;
    @Option(names = "--evil-size") Integer evilSize;
    @Option(names = "--seed", description = "Signed 64-bit master seed; printed if omitted") Long seed;
    @Option(names = "--workers", description = "Parallel workers, 1..256 (default: 1)", defaultValue = "1") int workers;
    @Option(names = "--max-attempts", description = "Candidate budget per puzzle (default: 500)", defaultValue = "500") int maxAttempts;
    @Option(names = "--db", description = "SQLite file (new standalone schema)") Path db;
    @Option(names = "--outfile", description = "PDF output (default: kakuro_puzzles.pdf)", defaultValue = "kakuro_puzzles.pdf") Path outfile;
    @Option(names = "--no-pdf", description = "Suppress PDF generation") boolean noPdf;
    @Option(names = "--page-size", defaultValue = "a4", description = "PDF preset: a4, kdp-8.25, kdp-8.50") String pageSize;
    @Option(names = "--margin-inner", description = "KDP inside margin in inches, including binding space (default: automatic)") Double marginInner;
    @Option(names = "--margin-outer", description = "KDP outside margin in inches (default: 0.5)") Double marginOuter;
    @Option(names = "--margin-top", description = "KDP top margin in inches (default: 0.5)") Double marginTop;
    @Option(names = "--margin-bottom", description = "KDP bottom margin in inches (default: 0.5)") Double marginBottom;
    @Option(names = "--gutter", description = "Additional KDP inside binding space in inches (default: 0)") Double gutter;
    private PrintLayout printLayout;
    @Option(names="--solutions-per-page", description="KDP maximum solutions per page: 1,2,3,4,6 (default: 6)") Integer solutionsPerPage;
    @Option(names="--solution-cell-size", description="KDP maximum solution cell size in points, 14..72 (default: 20)") Float solutionCellSize;
    private SolutionLayout.Options solutionOptions;
    @Option(names = "--export-ids", split = ",", description = "Export saved IDs in specified order; requires --db") List<Long> exportIds;
    @Option(names = "--verify-db", description = "Re-solve and validate every saved record; requires --db") boolean verifyDb;
    @Option(names = "--analyze-db", description = "Explain logical deduction complexity of saved puzzles; requires --db") boolean analyzeDb;
    @Option(names = "--analysis-budget", defaultValue = "5000000", description = "Deterministic logical-analysis work limit per puzzle") long analysisBudget;
    @Spec CommandLine.Model.CommandSpec spec;

    @Override public Integer call() throws Exception {
        validateArguments();
        if (exportIds != null || verifyDb || analyzeDb) return existing();
        int[] counts = {value(easy), value(medium), value(hard), value(evil)};
        if (easy == null && medium == null && hard == null && evil == null) counts[1] = 1;
        if (Arrays.stream(counts).sum() == 0) { out("No puzzles requested."); return 0; }
        long actualSeed = seed == null ? new Random().nextLong() : seed;
        out("Master seed: " + actualSeed + "; algorithm: kakuro-solution-v4 (unchanged score bands, geometry and storage)");
        Random random = new Random(actualSeed);
        Integer[] sizes = {easySize, mediumSize, hardSize, evilSize};
        List<BatchGenerator.Task> tasks = new ArrayList<>();
        for (Difficulty difficulty : Difficulty.values()) {
            int i = difficulty.ordinal();
            for (int n = 0; n < counts[i]; n++) tasks.add(new BatchGenerator.Task(tasks.size() + 1, difficulty,
                    rows == null ? (sizes[i] == null ? size : sizes[i]) : rows,
                    cols == null ? (sizes[i] == null ? size : sizes[i]) : cols, random.nextLong(), maxAttempts));
        }
        List<Puzzle> puzzles = new ArrayList<>();
        int[] failures = {0};
        Thread caller = Thread.currentThread();
        Thread hook = new Thread(() -> {
            caller.interrupt();
            try { caller.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "kakuro-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try (PuzzleRepository repository = db == null ? null : new PuzzleRepository(db)) {
            new BatchGenerator().run(tasks, workers, result -> {
                if (result.failure() != null) {
                    failures[0]++;
                    err("Slot " + result.task().slot() + " FAILED: " + result.failure());
                    return;
                }
                Puzzle puzzle = result.generated().puzzle();
                String storage = "";
                if (repository != null) {
                    var inserted = repository.save(puzzle);
                    if (!inserted.inserted()) {
                        failures[0]++;
                        err("Slot " + result.task().slot() + " DUPLICATE: already stored as ID " + inserted.id());
                        return;
                    }
                    storage = " db-id=" + inserted.id();
                }
                puzzles.add(puzzle);
                out(String.format(Locale.ROOT, "Slot %d: %s %dx%d score=%.3f nodes=%d candidates=%d time=%.3fs seed=%d%s sha256=%s",
                        result.task().slot(), puzzle.difficulty(), puzzle.board().rows(), puzzle.board().cols(), puzzle.score(),
                        puzzle.nodes(), result.generated().attempts(), result.seconds(), puzzle.seed(), storage, puzzle.fingerprint()));
                out("  " + result.generated().statistics());
            });
            if (!noPdf && !puzzles.isEmpty()) {
                writePdf(puzzles);
                out("PDF: " + outfile.toAbsolutePath());
            }
            out("Generated " + puzzles.size() + " of " + tasks.size() + " requested puzzles; failed/skipped=" + failures[0]);
            return failures[0] == 0 ? 0 : 1;
        } catch (InterruptedException | CancellationException e) {
            Thread.currentThread().interrupt();
            err("Cancelled. Previously committed SQLite puzzles are preserved.");
            return 130;
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { /* JVM shutdown */ }
        }
    }

    private int existing() throws Exception {
        if (!Files.isRegularFile(db)) throw usage("Database file does not exist: " + db);
        try (PuzzleRepository repository = new PuzzleRepository(db)) {
            if (analyzeDb) {
                int failures=0;
                for (var saved:repository.scan()) {
                    var puzzle=saved.puzzle();
                    var result=new LogicalAnalyzer().analyze(puzzle.board(),analysisBudget,LogicalAnalyzer.Technique.RUN_MATCHING);
                    out(String.format(Locale.ROOT,"ID %d: legacy=%s score=%.3f search-nodes=%d; %s status=%s strongest=%s forced=%d/%d eliminations=%d effort/cell=%.3f depth=%d unresolved=%d min-branch-width=%d work=%d",
                            saved.id(),puzzle.difficulty(),puzzle.score(),puzzle.nodes(),LogicalAnalyzer.VERSION,result.status(),result.strongestTechnique(),
                            result.forcedMoves(),result.whites(),result.eliminations(),result.effortPerCell(),result.maxDepth(),
                            result.whites()-result.solvedCells(),result.minimumBranchWidth(),result.work()));
                    for (var technique:result.techniques())out("  "+technique);
                    if(result.status()==LogicalAnalyzer.Status.BUDGET_EXHAUSTED || result.status()==LogicalAnalyzer.Status.CONTRADICTION)failures++;
                }
                out("Logical profiles are not calibrated human ratings. STALLED means beyond supported rules, not proven guessing. Use --verify-db for uniqueness verification.");
                return failures==0?0:1;
            }
            if (verifyDb) {
                int count = 0;
                for (var saved : repository.scan()) {
                    Puzzle puzzle = saved.puzzle();
                    var proof = new Solver().solve(puzzle.board(), Generator.SOLVER_NODE_LIMIT);
                    if (proof.status() != Solver.Status.UNIQUE || !Arrays.equals(proof.solutions().getFirst(), puzzle.solution())
                            || proof.nodes() != puzzle.nodes()
                            || Math.abs(Difficulty.score(puzzle.board(), proof.nodes()) - puzzle.score()) > 1e-9) {
                        err("Verification FAILED for ID " + saved.id() + ": " + proof.status());
                        return 1;
                    }
                    puzzle.board().validateSolution(proof.solutions().getFirst());
                    count++;
                    out("Verified ID " + saved.id() + ": " + puzzle.difficulty() + " UNIQUE; solution, score and hash match");
                }
                out("Verified " + count + " saved puzzles from clues without supplying stored digits to the solver.");
                return 0;
            }
            List<Puzzle> puzzles = new ArrayList<>();
            boolean missing = false;
            for (long id : exportIds) {
                var saved = repository.find(id);
                if (saved.isPresent()) puzzles.add(saved.get().puzzle());
                else { err("Missing ID " + id); missing = true; }
            }
            if (puzzles.isEmpty()) { err("No matching puzzles; PDF not written."); return 1; }
            writePdf(puzzles);
            out("Exported " + puzzles.size() + " puzzles to " + outfile.toAbsolutePath());
            return missing ? 1 : 0;
        }
    }

    private void validateArguments() {
        boolean margins = marginInner != null || marginOuter != null || marginTop != null || marginBottom != null || gutter != null
                || solutionsPerPage != null || solutionCellSize != null;
        if (pageSize.equals("a4")) {
            if (margins) throw usage("Margin/gutter options require a KDP page-size preset");
        } else {
            try {
                solutionOptions = new SolutionLayout.Options(solutionsPerPage == null ? 6 : solutionsPerPage,
                        solutionCellSize == null ? 20 : solutionCellSize);
                printLayout = new PrintLayout(pageSize, marginInner, marginOuter == null ? .5 : marginOuter,
                        marginTop == null ? .5 : marginTop, marginBottom == null ? .5 : marginBottom,
                        gutter == null ? 0 : gutter);
            } catch (IllegalArgumentException e) { throw usage(e.getMessage()); }
        }
        if (size < 7 || size > 20) throw usage("--size must be 7..20");
        if ((rows == null) != (cols == null)) throw usage("--rows and --cols must be supplied together");
        if (rows != null) {
            if (rows < 7 || rows > 20 || cols < 7 || cols > 20) throw usage("--rows and --cols must be 7..20");
            if (spec.commandLine().getParseResult().hasMatchedOption("--size")
                    || easySize != null || mediumSize != null || hardSize != null || evilSize != null)
                throw usage("--rows/--cols cannot be combined with --size or difficulty size overrides");
        }
        for (Integer override : new Integer[]{easySize, mediumSize, hardSize, evilSize}) {
            if (override != null && (override < 7 || override > 20)) throw usage("Difficulty sizes must be 7..20");
        }
        long total = 0;
        for (Integer count : new Integer[]{easy, medium, hard, evil}) {
            if (count != null && count < 0) throw usage("Puzzle counts must be nonnegative");
            total += value(count);
        }
        if (total > 10_000) throw usage("At most 10000 puzzles per batch");
        if (workers < 1 || workers > 256 || maxAttempts < 1) throw usage("--workers must be 1..256 and --max-attempts positive");
        if ((exportIds != null || verifyDb || analyzeDb) && db == null) throw usage("Retrieval, analysis and verification require --db");
        if (analyzeDb && verifyDb) throw usage("Choose --analyze-db or --verify-db");
        if (analysisBudget<1) throw usage("--analysis-budget must be positive");
        if (!analyzeDb && spec.commandLine().getParseResult().hasMatchedOption("--analysis-budget")) throw usage("--analysis-budget requires --analyze-db");
        if (exportIds != null && (verifyDb || analyzeDb || noPdf)) throw usage("--export-ids cannot be combined with analysis, verification or --no-pdf");
        if ((exportIds != null || verifyDb || analyzeDb) && (easy != null || medium != null || hard != null || evil != null)) {
            throw usage("Do not combine generation counts with retrieval/verification");
        }
        if (exportIds != null && exportIds.stream().anyMatch(id -> id < 1)) throw usage("Export IDs must be positive");
        if (db != null && !noPdf && db.toAbsolutePath().normalize().equals(outfile.toAbsolutePath().normalize())) {
            throw usage("Database and PDF paths must differ");
        }
    }

    private void writePdf(List<Puzzle> puzzles) throws Exception {
        if (printLayout != null) {
            var plan = SolutionLayout.plan(puzzles, printLayout, solutionOptions);
            if (plan.totalPages() < 24)
                err("KDP review sample has fewer than 24 pages; it is not a complete publishable paperback interior.");
            out("KDP pages: " + plan.totalPages() + "; solutions per page: "
                    + plan.sheets().stream().map(sheet -> sheet.tiles().size()).toList());
        }
        new PdfBooklet().write(outfile, puzzles, printLayout,
                solutionOptions == null ? SolutionLayout.Options.defaults() : solutionOptions);
    }

    private static int value(Integer n) { return n == null ? 0 : n; }
    private CommandLine.ParameterException usage(String message) { return new CommandLine.ParameterException(spec.commandLine(), message); }
    private void out(String message) { spec.commandLine().getOut().println(message); spec.commandLine().getOut().flush(); }
    private void err(String message) { spec.commandLine().getErr().println(message); spec.commandLine().getErr().flush(); }
}
