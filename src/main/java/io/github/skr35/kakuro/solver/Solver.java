package io.github.skr35.kakuro.solver;

import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Exact run-domain propagation followed by deterministic MRV backtracking. */
public final class Solver {
    public enum Status { UNSATISFIABLE, UNIQUE, MULTIPLE, INCONCLUSIVE }
    public record Result(Status status, List<int[]> solutions, long nodes) {
        public Result { solutions = solutions.stream().map(int[]::clone).toList(); }
        @Override public List<int[]> solutions() { return solutions.stream().map(int[]::clone).toList(); }
    }

    private static final int[] SUM = new int[512];
    private static final int[] COUNT = new int[512];
    static {
        for (int m = 1; m < 512; m++) {
            int bit = Integer.lowestOneBit(m);
            SUM[m] = SUM[m ^ bit] + Integer.numberOfTrailingZeros(bit) + 1;
            COUNT[m] = COUNT[m ^ bit] + 1;
        }
    }

    public static int combinationCount(int length, int total) {
        int n = 0;
        for (int m = 0; m < 512; m++) if (COUNT[m] == length && SUM[m] == total) n++;
        return n;
    }

    public Result solve(Board board, long nodeLimit) {
        if (nodeLimit < 1) throw new IllegalArgumentException("Node limit must be positive");
        Search search = new Search(board, nodeLimit);
        int[] domains = new int[board.cellCount()];
        for (int i = 0; i < domains.length; i++) if (board.isWhite(i)) domains[i] = 511;
        search.visit(domains);
        Status status = search.solutions.size() >= 2 ? Status.MULTIPLE : search.exhausted ? Status.INCONCLUSIVE
                : search.solutions.isEmpty() ? Status.UNSATISFIABLE : Status.UNIQUE;
        return new Result(status, search.solutions, search.nodes);
    }

    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Generation cancelled");
    }

    private static final class Search {
        private final Board board;
        private final long limit;
        private final List<int[]> solutions = new ArrayList<>();
        private long nodes;
        private boolean exhausted;

        Search(Board board, long limit) { this.board = board; this.limit = limit; }

        void visit(int[] domains) {
            checkCancelled();
            if (solutions.size() >= 2 || exhausted) return;
            if (nodes >= limit) { exhausted = true; return; }
            nodes++;
            if (!propagate(domains)) return;
            int best = -1, fewest = 10;
            for (int c = 0; c < domains.length; c++) {
                int n = Integer.bitCount(domains[c]);
                if (n > 1 && n < fewest) { best = c; fewest = n; }
            }
            if (best < 0) {
                int[] values = new int[domains.length];
                for (int c = 0; c < values.length; c++) {
                    if (board.isWhite(c)) values[c] = Integer.numberOfTrailingZeros(domains[c]) + 1;
                }
                board.validateSolution(values);
                solutions.add(values);
                return;
            }
            for (int m = domains[best]; m != 0 && solutions.size() < 2 && !exhausted; m &= m - 1) {
                int[] child = domains.clone();
                child[best] = Integer.lowestOneBit(m);
                visit(child);
            }
        }

        boolean propagate(int[] domains) {
            boolean changed;
            do {
                checkCancelled();
                changed = false;
                for (Run run : board.runs()) {
                    int n = run.cells().size();
                    boolean[] forward = new boolean[512];
                    boolean[] backward = new boolean[512];
                    forward[0] = true;
                    for (int mask = 0; mask < 512; mask++) {
                        int pos = COUNT[mask];
                        if (!forward[mask] || pos >= n || SUM[mask] >= run.sum()) continue;
                        int available = domains[run.cells().get(pos)] & ~mask;
                        for (int m = available; m != 0; m &= m - 1) {
                            int next = mask | Integer.lowestOneBit(m);
                            if (SUM[next] <= run.sum()) forward[next] = true;
                        }
                    }
                    for (int mask = 511; mask >= 0; mask--) {
                        int pos = COUNT[mask];
                        if (!forward[mask]) continue;
                        if (pos == n) { backward[mask] = SUM[mask] == run.sum(); continue; }
                        if (pos > n) continue;
                        int available = domains[run.cells().get(pos)] & ~mask;
                        for (int m = available; m != 0; m &= m - 1) {
                            if (backward[mask | Integer.lowestOneBit(m)]) { backward[mask] = true; break; }
                        }
                    }
                    if (!backward[0]) return false;
                    int[] supported = new int[n];
                    for (int mask = 0; mask < 512; mask++) {
                        int pos = COUNT[mask];
                        if (!forward[mask] || !backward[mask] || pos >= n) continue;
                        int available = domains[run.cells().get(pos)] & ~mask;
                        for (int m = available; m != 0; m &= m - 1) {
                            int bit = Integer.lowestOneBit(m);
                            if (backward[mask | bit]) supported[pos] |= bit;
                        }
                    }
                    for (int pos = 0; pos < n; pos++) {
                        int c = run.cells().get(pos);
                        int next = domains[c] & supported[pos];
                        if (next == 0) return false;
                        if (next != domains[c]) { domains[c] = next; changed = true; }
                    }
                }
            } while (changed);
            return true;
        }
    }
}
