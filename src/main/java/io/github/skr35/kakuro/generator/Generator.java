package io.github.skr35.kakuro.generator;

import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.model.Run;
import io.github.skr35.kakuro.solver.Solver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.function.BiPredicate;
import java.util.HashSet;
import java.util.Set;

public final class Generator {
    public static final long SOLVER_NODE_LIMIT = 20_000;
    public record Statistics(int candidates, long geometryRequests, long geometryMasks, long geometryAccepted,
            long geometryStates, int fillAttempts, int fillSuccesses, int solverCalls, int unique,
            int inBand, int inconclusive, int diversityRejected, int geometryExhaustions, int solutionQualityRejected) { }
    public record Generated(Puzzle puzzle, int attempts, Statistics statistics) { }
    /** Disjoint final gates, separate from the legacy overlapping stage counters. */
    public record AcceptanceDiagnostics(int inBandQualityRejected, int eligible, int layoutRejected, int numericalRejected) { }

    public Generated generate(Difficulty difficulty, int size, long seed, int maxAttempts) {
        return generate(difficulty, size, size, seed, maxAttempts);
    }

    public Generated generate(Difficulty difficulty, int rows, int cols, long seed, int maxAttempts) {
        return new Session(difficulty, rows, cols, seed, maxAttempts).next(board -> true);
    }

    /** A resumable deterministic candidate stream, including its original total work budget. */
    public static final class Session {
        private final Difficulty difficulty;
        private final int rows, cols, maxAttempts;
        private final long seed;
        private final Random random;
        private final Solver solver = new Solver();
        private final io.github.skr35.kakuro.geometry.GeometryBuilder builder = new io.github.skr35.kakuro.geometry.GeometryBuilder();
        private int geometryRequests, fillAttempts, fillSuccesses, solverCalls, inBand, inconclusive, diversityRejected;
        private int attempt, unique, rejectedLayouts;
        private int solutionQualityRejected;
        private int inBandQualityRejected, eligible, layoutRejected, numericalRejected;
        public AcceptanceDiagnostics acceptanceDiagnostics() {
            return new AcceptanceDiagnostics(inBandQualityRejected, eligible, layoutRejected, numericalRejected);
        }
        private final SolutionQualityEvaluator quality = new SolutionQualityEvaluator();
        private boolean[] white;
        private List<Run> runs;
        private Board anchor;
        private int[] anchorValues;
        private double anchorScore;
        private int tuningAttempts;
        private final Set<String> triedMutations = new HashSet<>();
        private Board repairBoard;
        private int[] repairValues;
        private List<int[]> counterexamples;
        private int repairSteps;
        private final Set<String> repairHistory = new HashSet<>();

        public Statistics statistics() {
            return new Statistics(attempt, geometryRequests, builder.completedMasks(), builder.acceptedMasks(), builder.states(),
                    fillAttempts, fillSuccesses, solverCalls, unique, inBand, inconclusive, diversityRejected, rejectedLayouts, solutionQualityRejected);
        }

        /** Account for an ordered batch rejection of a worker's provisional result. */
        public void recordBatchRejection() { diversityRejected++; }

        public Session(Difficulty difficulty, int size, long seed, int maxAttempts) {
        this(difficulty, size, size, seed, maxAttempts);
        }

        public Session(Difficulty difficulty, int rows, int cols, long seed, int maxAttempts) {
            this(difficulty, rows, cols, seed, maxAttempts, false);
        }

        /** Wide-board experiments must be run in a time-limited child process by the probe CLI. */
        public Session(Difficulty difficulty, int rows, int cols, long seed, int maxAttempts, boolean experimental) {
        if (!(rows >= 7 && rows <= 20 && cols >= 7 && cols <= 20)
                && !(experimental && rows == 15 && (cols == 30 || cols == 40 || cols == 50)))
            throw new IllegalArgumentException("Generation dimensions must be 7..20; experimental probes permit 15x30/40/50");
        if (maxAttempts < 1) throw new IllegalArgumentException("max-attempts must be positive");
        this.difficulty = difficulty; this.rows = rows; this.cols = cols; this.seed = seed; this.maxAttempts = maxAttempts;
        this.random = new Random(seed);
        }

        public Generated next(Predicate<Board> layoutAllowed) {
            return next(layoutAllowed, (board, values) -> true);
        }

        public Generated next(Predicate<Board> layoutAllowed, BiPredicate<Board,int[]> solutionAllowed) {
        while (attempt < maxAttempts) {
            Solver.checkCancelled();
            int candidate = attempt++;
            int[] values = null;
            if (anchor != null && tuningAttempts++ < 64) values = mutation();
            if (values == null && anchor != null) { anchor = null; white = null; }
            if (values == null && repairBoard != null) {
                if (repairSteps++ < 64) values = repairAmbiguity();
                if (values == null) { repairBoard = null; white = null; }
            }
            if (values == null && (white == null || candidate % 8 == 0)) {
                try {
                    geometryRequests++;
                    repairSteps = 0; repairHistory.clear();
                    white = builder.create(rows, cols, random, difficulty);
                    runs = Board.geometry(rows, cols, white);
                } catch (IllegalStateException e) { white = null; rejectedLayouts++; continue; }
            }
            if (values == null) {
                Fill fill = new Fill(rows, cols, white, runs, random, difficulty);
                fillAttempts++;
                if (!fill.search(0)) continue;
                fillSuccesses++;
                values = fill.values;
            }
            Board board = Board.fromSolution(rows, cols, white, values);
            solverCalls++;
            Solver.Result result = solver.solve(board, SOLVER_NODE_LIMIT);
            if (result.status() == Solver.Status.INCONCLUSIVE) inconclusive++;
            if (result.status() != Solver.Status.UNIQUE) {
                // On larger boards, use explicit counterexamples to alter an ambiguous
                // crossing, rather than repeatedly starting unrelated digit assignments.
                if (result.status() == Solver.Status.MULTIPLE && anchor == null) {
                    repairBoard = board; repairValues = values.clone(); counterexamples = result.solutions();
                    repairHistory.add(Arrays.toString(values));
                }
                continue;
            }
            repairBoard = null;
            unique++;
            double score = Difficulty.score(board, result.nodes());
            if (difficulty.accepts(score)) inBand++;
            boolean qualityAccepted = quality.evaluate(board, values).accepted();
            if (!qualityAccepted) solutionQualityRejected++;
            if (difficulty.accepts(score) && !qualityAccepted) inBandQualityRejected++;
            if (difficulty.accepts(score) && qualityAccepted) {
                eligible++;
                if (!layoutAllowed.test(board)) layoutRejected++;
                else if (!solutionAllowed.test(board,values)) numericalRejected++;
                else {
                    // The solver derives the solution entirely from the clues.
                    return new Generated(new Puzzle(board, result.solutions().getFirst(), difficulty, score, seed,
                            result.nodes()), attempt, statistics());
                }
            }
            if (!layoutAllowed.test(board) || !solutionAllowed.test(board,values)) { diversityRejected++; anchor = null; white = null; continue; }
            // Broad masks are less forgiving than thin strips. Tune only proven-unique
            // anchors, and pay the same full proof/budget for every proposed clue change.
            if (anchor == null || objective(board, values, score) < objective(anchor, anchorValues, anchorScore)) {
                anchor = board; anchorValues = values.clone(); anchorScore = score;
                tuningAttempts = 0; triedMutations.clear();
            }
        }
        throw new IllegalStateException("Could not generate " + difficulty + " " + rows + "x" + cols
                + " in " + maxAttempts + " candidates (" + unique + " unique, none remaining in-band and diverse; "
                + rejectedLayouts + " geometry-search failures); " + statistics());
        }

        private double distance(double score) {
            if (difficulty.accepts(score)) return 0;
            return score < difficulty.minimum() ? difficulty.minimum() - score : score - difficulty.maximum() + 1e-9;
        }

        private double objective(Board board, int[] values, double score) {
            return distance(score) + 12 * quality.evaluate(board,values).loss()
                    + .02 * DigitPreferences.penalty(rows,cols,runs,values);
        }

        private int[] repairAmbiguity() {
            record Change(int[] values, double priority) { }
            List<Change> changes = new ArrayList<>();
            double beforeQuality = DigitPreferences.penalty(rows,cols,runs,repairValues);
            for (int cell = 0; cell < repairValues.length; cell++) {
                if (!repairBoard.isWhite(cell)) continue;
                final int index = cell;
                if (counterexamples.stream().allMatch(s -> s[index] == repairValues[index])) continue;
                int used = 0;
                List<Run> affected = new ArrayList<>(2);
                for (Run run : repairBoard.runs()) if (run.cells().contains(cell)) {
                    affected.add(run);
                    for (int c : run.cells()) if (c != cell) used |= 1 << (repairValues[c]-1);
                }
                for (int digit = 1; digit <= 9; digit++) {
                    if (digit == repairValues[cell] || (used & (1 << (digit-1))) != 0) continue;
                    int[] proposed = repairValues.clone(); proposed[cell] = digit;
                    if (repairHistory.contains(Arrays.toString(proposed))) continue;
                    double priority = random.nextDouble() * 0.5;
                    for (Run run : affected) {
                        int after = Solver.combinationCount(run.cells().size(), run.sum() + digit - repairValues[cell]);
                        int before = Solver.combinationCount(run.cells().size(), run.sum());
                        priority += StrictMath.log(after / (double) before);
                    }
                    priority += 1.5 * (DigitPreferences.penalty(rows,cols,runs,proposed) - beforeQuality);
                    changes.add(new Change(proposed, priority));
                }
            }
            if (changes.isEmpty()) return null;
            changes.sort(Comparator.comparingDouble(Change::priority));
            return changes.get(random.nextInt(Math.min(3, changes.size()))).values();
        }

        private int[] mutation() {
            record Move(int cell, int digit, double priority) { }
            List<Move> moves = new ArrayList<>();
            double beforeQuality = DigitPreferences.penalty(rows,cols,runs,anchorValues);
            double beforeLoss = quality.evaluate(anchor,anchorValues).loss();
            double goal = switch (difficulty) { case EASY -> 10.5; case MEDIUM -> 16; case HARD -> 24; case EVIL -> 32; };
            for (int cell = 0; cell < anchorValues.length; cell++) {
                if (!anchor.isWhite(cell)) continue;
                int used = 0;
                List<Run> affected = new ArrayList<>(2);
                for (Run run : anchor.runs()) if (run.cells().contains(cell)) {
                    affected.add(run);
                    for (int c : run.cells()) if (c != cell) used |= 1 << (anchorValues[c] - 1);
                }
                for (int digit = 1; digit <= 9; digit++) {
                    String key = cell + ":" + digit;
                    if (digit == anchorValues[cell] || (used & (1 << (digit - 1))) != 0 || triedMutations.contains(key)) continue;
                    double predicted = anchorScore;
                    for (Run run : affected) {
                        int before = Solver.combinationCount(run.cells().size(), run.sum());
                        int after = Solver.combinationCount(run.cells().size(), run.sum() + digit - anchorValues[cell]);
                        predicted += 10.0 / anchor.runs().size() * StrictMath.log(after / (double) before) / StrictMath.log(2);
                    }
                    int[] proposed = anchorValues.clone(); proposed[cell] = digit;
                    double afterLoss = quality.evaluate(Board.fromSolution(rows,cols,white,proposed),proposed).loss();
                    moves.add(new Move(cell, digit, Math.abs(predicted - goal) + random.nextDouble() * .35
                            + .3 * (DigitPreferences.penalty(rows,cols,runs,proposed) - beforeQuality)
                            + 12 * (afterLoss - beforeLoss)));
                }
            }
            if (moves.isEmpty()) return null;
            moves.sort(Comparator.comparingDouble(Move::priority));
            Move move = moves.get(random.nextInt(Math.min(5, moves.size())));
            triedMutations.add(move.cell() + ":" + move.digit());
            int[] values = anchorValues.clone(); values[move.cell()] = move.digit(); return values;
        }
    }

    /** Quality-gated, two-dimensional geometry; no monotonic path construction. */
    public static boolean[] geometry(int size, Random random, Difficulty difficulty) {
        return geometry(size, size, random, difficulty);
    }
    public static boolean[] geometry(int rows, int cols, Random random, Difficulty difficulty) {
        return new io.github.skr35.kakuro.geometry.GeometryBuilder().create(rows, cols, random, difficulty);
    }
    private static final class Fill {
        private static final double[][] RISK = risks();
        private static double[][] risks() {
            int[] count = new int[512], sum = new int[512];
            int[][] combinations = new int[10][46];
            for (int mask = 0; mask < 512; mask++) {
                count[mask] = Integer.bitCount(mask);
                for (int d = 1; d <= 9; d++) if ((mask & (1 << (d - 1))) != 0) sum[mask] += d;
                combinations[count[mask]][sum[mask]]++;
            }
            double[][] risk = new double[10][512];
            for (int length = 2; length <= 9; length++) for (int used = 0; used < 512; used++) {
                int min = 1000, total = 0, possibilities = 0;
                for (int mask = 0; mask < 512; mask++) {
                    if (count[mask] != length || (mask & used) != used) continue;
                    int ambiguity = combinations[length][sum[mask]];
                    min = Math.min(min, ambiguity); total += ambiguity; possibilities++;
                }
                risk[length][used] = possibilities == 0 ? 1000 : min + 0.20 * total / possibilities;
            }
            return risk;
        }
        private final int[] values;
        private final List<Run> runs;
        private final List<List<Integer>> membership;
        private final List<Integer> cells = new ArrayList<>();
        private final Random random;
        private final Difficulty difficulty;
        private int nodes;
        private final int rows, cols;
        Fill(int rows, int cols, boolean[] white, List<Run> runs, Random random, Difficulty difficulty) {
            this.rows=rows;this.cols=cols;
            this.values = new int[white.length];
            this.runs = runs;
            this.random = random;
            this.difficulty = difficulty;
            membership = new ArrayList<>();
            for (int i = 0; i < values.length; i++) {
                membership.add(new ArrayList<>());
                if (white[i]) cells.add(i);
            }
            for (int i = 0; i < runs.size(); i++) for (int c : runs.get(i).cells()) membership.get(c).add(i);
            Collections.shuffle(cells, random);
        }

        boolean search(int filled) {
            Solver.checkCancelled();
            if (++nodes > 10_000) return false;
            if (filled == cells.size()) return true;
            int best = -1, bestMask = 0, fewest = 10;
            for (int c : cells) {
                if (values[c] != 0) continue;
                int mask = 511;
                for (int ri : membership.get(c)) for (int other : runs.get(ri).cells()) {
                    if (values[other] != 0) mask &= ~(1 << (values[other] - 1));
                }
                int count = Integer.bitCount(mask);
                if (count == 0) return false;
                if (count < fewest) { best = c; bestMask = mask; fewest = count; }
            }
            record Choice(int digit, double risk) { }
            List<Choice> choices = new ArrayList<>();
            double currentPenalty=DigitPreferences.penalty(rows,cols,runs,values);
            for (int d = 1; d <= 9; d++) {
                if ((bestMask & (1 << (d - 1))) == 0) continue;
                double risk = 0;
                for (int ri : membership.get(best)) {
                    Run run = runs.get(ri);
                    int used = 1 << (d - 1);
                    for (int c : run.cells()) if (values[c] != 0) used |= 1 << (values[c] - 1);
                    risk += .8 * StrictMath.log(RISK[run.cells().size()][used]);
                }
                values[best]=d;
                risk += .8 * (DigitPreferences.penalty(rows,cols,runs,values)-currentPenalty);
                values[best]=0;
                double noise = switch (difficulty) { case EASY -> 0.6; case MEDIUM -> 0.8; case HARD -> 1.0; case EVIL -> 1.2; };
                choices.add(new Choice(d, risk + random.nextDouble() * noise));
            }
            choices.sort(Comparator.comparingDouble(Choice::risk));
            for (Choice choice : choices) {
                values[best] = choice.digit();
                if (search(filled + 1)) return true;
            }
            values[best] = 0;
            return false;
        }
    }
}
