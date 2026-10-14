package io.github.skr35.kakuro.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Immutable geometry and clues. Does not contain or consult a stored solution. */
public final class Board {
    private final int rows, cols;
    private final boolean[] white;
    private final List<Run> runs;

    public Board(int size, boolean[] white, int[] sums) {
        this(size, size, white, sums);
    }

    public Board(int rows, int cols, boolean[] white, int[] sums) {
        List<Run> geometry = geometry(rows, cols, white);
        if (sums.length != geometry.size()) throw new IllegalArgumentException("Wrong clue count");
        this.rows = rows; this.cols = cols;
        this.white = white.clone();
        List<Run> clues = new ArrayList<>();
        for (int i = 0; i < geometry.size(); i++) {
            Run r = geometry.get(i);
            int length = r.cells().size();
            if (sums[i] < length * (length + 1) / 2 || sums[i] > length * (19 - length) / 2) {
                throw new IllegalArgumentException("Impossible clue sum: " + sums[i]);
            }
            clues.add(new Run(r.direction(), r.clueCell(), r.cells(), sums[i]));
        }
        this.runs = List.copyOf(clues);
    }

    public static Board fromSolution(int size, boolean[] white, int[] solution) {
        return fromSolution(size, size, white, solution);
    }

    public static Board fromSolution(int rows, int cols, boolean[] white, int[] solution) {
        if (solution.length != white.length) throw new IllegalArgumentException("Wrong solution dimensions");
        List<Run> runs = geometry(rows, cols, white);
        int[] sums = runs.stream().mapToInt(r -> r.cells().stream().mapToInt(c -> solution[c]).sum()).toArray();
        Board board = new Board(rows, cols, white, sums);
        board.validateSolution(solution);
        return board;
    }

    public static List<Run> geometry(int size, boolean[] white) {
        return geometry(size, size, white);
    }

    public static List<Run> geometry(int rows, int cols, boolean[] white) {
        if (rows < 3 || cols < 3 || !((rows <= 20 && cols <= 20)
                || Math.min(rows,cols) == 15 && (Math.max(rows,cols) == 30 || Math.max(rows,cols) == 40 || Math.max(rows,cols) == 50))
                || white.length != rows * cols) {
            throw new IllegalArgumentException("Invalid board dimensions (3..20 per axis, or experimental 15x30/40/50)");
        }
        for (int c = 0; c < cols; c++) if (white[c]) throw new IllegalArgumentException("Top row must be black");
        for (int r = 0; r < rows; r++) if (white[r * cols]) throw new IllegalArgumentException("Left column must be black");
        List<Run> runs = new ArrayList<>();
        int[] memberships = new int[white.length];
        for (Run.Direction direction : Run.Direction.values()) {
            int step = direction == Run.Direction.ACROSS ? 1 : cols;
            int lines = direction == Run.Direction.ACROSS ? rows : cols;
            int length = direction == Run.Direction.ACROSS ? cols : rows;
            for (int line = 1; line < lines; line++) {
                for (int pos = 1; pos < length;) {
                    int cell = direction == Run.Direction.ACROSS ? line * cols + pos : pos * cols + line;
                    if (!white[cell]) { pos++; continue; }
                    int clue = cell - step;
                    List<Integer> cells = new ArrayList<>();
                    while (pos < length && white[cell]) {
                        cells.add(cell);
                        memberships[cell]++;
                        cell += step;
                        pos++;
                    }
                    runs.add(new Run(direction, clue, cells, 0));
                }
            }
        }
        int first = -1, count = 0;
        for (int i = 0; i < white.length; i++) {
            if (white[i]) {
                if (memberships[i] != 2) throw new IllegalArgumentException("Missing crossing run");
                first = i;
                count++;
            }
        }
        if (first < 0) throw new IllegalArgumentException("Empty board");
        boolean[] seen = new boolean[white.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(first);
        seen[first] = true;
        int reached = 0;
        while (!queue.isEmpty()) {
            int c = queue.remove();
            reached++;
            for (int next : new int[]{c - cols, c + cols, c % cols > 0 ? c - 1 : -1,
                    c % cols < cols - 1 ? c + 1 : -1}) {
                if (next >= 0 && next < white.length && white[next] && !seen[next]) {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        if (reached != count) throw new IllegalArgumentException("Disconnected white cells");
        return List.copyOf(runs);
    }

    public void validateSolution(int[] values) {
        if (values.length != white.length) throw new IllegalArgumentException("Wrong solution dimensions");
        for (int i = 0; i < white.length; i++) {
            if (white[i] ? values[i] < 1 || values[i] > 9 : values[i] != 0) {
                throw new IllegalArgumentException("Invalid digit or nonempty black cell at " + i);
            }
        }
        for (Run run : runs) {
            int mask = 0, sum = 0;
            for (int c : run.cells()) {
                int bit = 1 << (values[c] - 1);
                if ((mask & bit) != 0) throw new IllegalArgumentException("Repeated run digit");
                mask |= bit;
                sum += values[c];
            }
            if (sum != run.sum()) throw new IllegalArgumentException("Incorrect clue sum");
        }
    }

    /** Legacy square accessor: fail explicitly instead of silently misindexing a rectangle. */
    public int size() {
        if (rows != cols) throw new IllegalStateException("Rectangular board: use rows() and cols()");
        return rows;
    }
    public int rows() { return rows; }
    public int cols() { return cols; }
    public int cellCount() { return rows * cols; }
    public boolean isWhite(int cell) { return white[cell]; }
    public boolean[] white() { return white.clone(); }
    public List<Run> runs() { return runs; }
    public int whiteCount() { int n = 0; for (boolean v : white) if (v) n++; return n; }
    public int[] sums() { return runs.stream().mapToInt(Run::sum).toArray(); }

    /** Versioned, unambiguous identity; excludes solution and generation metadata. */
    public String canonical() {
        StringBuilder out = rows == cols
                ? new StringBuilder("kakuro-java-v1|").append(rows).append('|')
                : new StringBuilder("kakuro-java-rect-v1|").append(rows).append('x').append(cols).append('|');
        for (boolean v : white) out.append(v ? '1' : '0');
        return out.append('|').append(Arrays.toString(sums())).toString();
    }
}
