package io.github.skr35.kakuro.generator;

import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.solver.Solver;

/** Legacy structural/search bands retained for generation and stored-record compatibility.
 * Human-style deduction profiles are reported separately by analysis.LogicalAnalyzer. */
public enum Difficulty {
    EASY(0, 12), MEDIUM(12, 20), HARD(20, 28), EVIL(28, Double.POSITIVE_INFINITY);

    private final double min, max;
    Difficulty(double min, double max) { this.min = min; this.max = max; }
    public boolean accepts(double score) { return score >= min && score < max; }
    public double minimum() { return min; }
    public double maximum() { return max; }

    public static double score(Board board, long nodes) {
        double length = board.runs().stream().mapToInt(r -> r.cells().size()).average().orElseThrow();
        double ambiguity = board.runs().stream()
                .mapToDouble(r -> log2(Solver.combinationCount(r.cells().size(), r.sum())))
                .average().orElseThrow();
        return 10 * (length - 2) + 10 * ambiguity + 4 * log2(Math.max(1, nodes));
    }

    private static double log2(double x) { return StrictMath.log(x) / StrictMath.log(2); }
}
