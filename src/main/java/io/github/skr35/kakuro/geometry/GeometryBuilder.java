package io.github.skr35.kakuro.geometry;

import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.solver.Solver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Randomized row-pattern constraint search across the entire two-dimensional mask. */
public final class GeometryBuilder {
    private long completedMasks, acceptedMasks, states;
    public long completedMasks() { return completedMasks; }
    public long acceptedMasks() { return acceptedMasks; }
    public long states() { return states; }
    private final GeometryQualityEvaluator evaluator = new GeometryQualityEvaluator();

    public boolean[] create(int size, Random random, Difficulty difficulty) {
        return create(size, size, random, difficulty);
    }

    public boolean[] create(int rows, int cols, Random random, Difficulty difficulty) {
        if (!supported(rows, cols)) throw new IllegalArgumentException("Unsupported geometry dimensions");
        // Search along the shorter axis, then transpose: avoids exponential wide-row enumeration.
        if (cols > rows) {
            boolean[] source = create(cols, rows, random, difficulty), result = new boolean[rows * cols];
            for (int r=0;r<rows;r++) for (int c=0;c<cols;c++) result[r*cols+c] = source[c*rows+r];
            return result;
        }
        int maxRun = switch (difficulty) { case EASY -> 4; case MEDIUM -> 5; default -> 6; };
        int n = cols - 1;
        List<Integer> patterns = new ArrayList<>();
        for (int mask = 0; mask < 1 << n; mask++) {
            if ((mask & 1023) == 0) Solver.checkCancelled();
            if (Integer.bitCount(mask) < (int) Math.ceil(n * 0.40)) continue;
            if (Integer.bitCount(mask) > Math.ceil(n * 0.86)) continue;
            int length = 0; boolean valid = true;
            for (int c = 0; c <= n; c++) {
                if (c < n && (mask & (1 << c)) != 0) length++;
                else { if (length == 1 || length > maxRun) valid = false; length = 0; }
            }
            if (valid) patterns.add(mask);
        }
        // Restart rather than spending the entire budget below an unlucky first row.
        for (int restart = 0; restart < 32; restart++) {
            Search search = new Search(rows, cols, maxRun, patterns, random, this);
            if (search.row(0, new int[n])) return search.white.clone();
        }
        throw new IllegalStateException("Geometry search exhausted 32 x 1000 states for " + rows + "x" + cols);
    }

    public static boolean supported(int rows, int cols) {
        return rows >= 7 && rows <= 20 && cols >= 7 && cols <= 20
                || Math.min(rows,cols) == 15 && (Math.max(rows,cols) == 30 || Math.max(rows,cols) == 40 || Math.max(rows,cols) == 50);
    }

    private static final class Search {
        private final int rows, cols, n, maxRun;
        private final List<Integer> patterns;
        private final Random random;
        private final GeometryBuilder owner;
        private final boolean[] white;
        private int visits;
        Search(int rows, int cols, int maxRun, List<Integer> patterns, Random random, GeometryBuilder owner) {
            this.rows = rows; this.cols = cols; this.n = cols - 1; this.maxRun = maxRun; this.patterns = patterns;
            this.random = random; this.owner = owner; this.white = new boolean[rows * cols];
        }
        boolean row(int row, int[] lengths) {
            Solver.checkCancelled();
            owner.states++;
            if (++visits > 1_000) return false;
            if (row == rows - 1) {
                owner.completedMasks++;
                boolean accepted = owner.evaluator.evaluate(rows, cols, white).accepted();
                if (accepted) owner.acceptedMasks++;
                return accepted;
            }
            List<Integer> shuffled = new ArrayList<>(patterns);
            Collections.shuffle(shuffled, random);
            for (int mask : shuffled) {
                int[] next = new int[n]; boolean valid = true;
                for (int col = 0; col < n; col++) {
                    boolean on = (mask & (1 << col)) != 0;
                    if (!on && lengths[col] == 1) { valid = false; break; }
                    next[col] = on ? lengths[col] + 1 : 0;
                    if (next[col] > maxRun || row == rows - 2 && next[col] == 1) { valid = false; break; }
                }
                if (!valid) continue;
                for (int col = 0; col < n; col++) white[(row + 1) * cols + col + 1] = (mask & (1 << col)) != 0;
                if (row(row + 1, next)) return true;
                if (visits >= 1_000) return false;
            }
            return false;
        }
    }
}
