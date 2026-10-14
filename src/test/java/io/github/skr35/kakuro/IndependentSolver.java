package io.github.skr35.kakuro;

import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Run;
import java.util.ArrayList;
import java.util.List;

/** Test oracle: plain digit assignments with arithmetic bounds; no production solver code. */
final class IndependentSolver {
    private final Board board;
    private final int[] values;
    private final List<List<Run>> memberships = new ArrayList<>();
    final List<int[]> solutions = new ArrayList<>();
    long nodes;

    IndependentSolver(Board board) {
        this.board = board;
        values = new int[board.cellCount()];
        for (int i = 0; i < values.length; i++) memberships.add(new ArrayList<>());
        for (Run run : board.runs()) for (int c : run.cells()) memberships.get(c).add(run);
    }

    List<int[]> solve() { search(); return solutions; }

    private void search() {
        if (++nodes > 5_000_000) throw new AssertionError("Independent solver exhausted its validation budget");
        if (solutions.size() == 2) return;
        int best = -1;
        List<Integer> options = null;
        for (int c = 0; c < values.length; c++) {
            if (!board.isWhite(c) || values[c] != 0) continue;
            List<Integer> candidates = new ArrayList<>();
            for (int digit = 1; digit <= 9; digit++) {
                values[c] = digit;
                boolean valid = true;
                for (Run run : memberships.get(c)) if (!possible(run)) { valid = false; break; }
                if (valid) candidates.add(digit);
            }
            values[c] = 0;
            if (candidates.isEmpty()) return;
            if (options == null || candidates.size() < options.size()) { best = c; options = candidates; }
        }
        if (best == -1) { solutions.add(values.clone()); return; }
        for (int digit : options) {
            values[best] = digit;
            search();
            if (solutions.size() == 2) break;
        }
        values[best] = 0;
    }

    private boolean possible(Run run) {
        boolean[] used = new boolean[10];
        int total = 0, empty = 0;
        for (int c : run.cells()) {
            int v = values[c];
            if (v == 0) { empty++; continue; }
            if (used[v]) return false;
            used[v] = true;
            total += v;
        }
        if (empty == 0) return total == run.sum();
        int min = 0, max = 0, found = 0;
        for (int d = 1; d <= 9 && found < empty; d++) if (!used[d]) { min += d; found++; }
        if (found != empty) return false;
        found = 0;
        for (int d = 9; d >= 1 && found < empty; d--) if (!used[d]) { max += d; found++; }
        return total + min <= run.sum() && total + max >= run.sum();
    }
}
