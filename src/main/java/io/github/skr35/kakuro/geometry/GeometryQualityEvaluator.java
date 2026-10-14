package io.github.skr35.kakuro.geometry;

import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Run;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Solver-independent publication heuristics. Denominators exclude the mandatory clue border. */
public final class GeometryQualityEvaluator {
    /** Exact rejection-only fast path. Survivors still undergo the complete evaluator. */
    public boolean accepts(int height, int width, boolean[] white) {
        if (!GeometryBuilder.supported(height,width) || white.length != height*width)
            throw new IllegalArgumentException("Unsupported geometry dimensions");
        int nr=height-1,nc=width-1,total=0,across=0,down=0;
        int[] columns=new int[nc],quadrantArea=new int[4],quadrantWhite=new int[4];
        for(int r=1;r<height;r++) {
            int row=0;
            for(int c=1;c<width;c++) {
                int q=quadrant(r,c,nr,nc);quadrantArea[q]++;
                if(!white[r*width+c])continue;
                row++;columns[c-1]++;total++;quadrantWhite[q]++;
                if(!white[r*width+c-1])across++;
                if(!white[(r-1)*width+c])down++;
            }
            if(row<(int)Math.ceil(nc*.40))return false;
        }
        double density=total/(double)(nr*nc);
        if(density<.52 || density>.80)return false;
        for(int column:columns)if(column<(int)Math.ceil(nr*.40))return false;
        if(across<nr || down<nc || Math.min(across,down)/(double)Math.max(across,down)<.70)return false;
        for(int q=0;q<4;q++)if(quadrantWhite[q]/(double)quadrantArea[q]<.40)return false;
        return evaluate(height,width,white).accepted();
    }

    public record Metrics(double density, double rowCoverage, double columnCoverage,
            int minimumRowWhites, int minimumColumnWhites, int acrossRuns, int downRuns,
            double runBalance, List<Integer> runLengthCounts, int components,
            List<Double> quadrantDensities, int longestWhiteDiagonal, double diagonalBandShare,
            int interiorClues, int interiorClueRows, int interiorClueColumns, int interiorClueQuadrants) { }
    public record Evaluation(Metrics metrics, List<String> violations) {
        public Evaluation { violations = List.copyOf(violations); }
        public boolean accepted() { return violations.isEmpty(); }
    }

    public Evaluation evaluate(int size, boolean[] white) {
        return evaluate(size, size, white);
    }

    public Evaluation evaluate(int height, int width, boolean[] white) {
        if (!GeometryBuilder.supported(height,width) || white.length != height * width)
            throw new IllegalArgumentException("Unsupported geometry dimensions");
        int nr = height - 1, nc = width - 1, n = Math.min(nr, nc), total = 0, across = 0, down = 0;
        int[] rows = new int[nr], columns = new int[nc], quadrantArea = new int[4], quadrantWhite = new int[4];
        int[] histogram = new int[10];
        for (int r = 1; r < height; r++) for (int c = 1; c < width; c++) {
            int q = quadrant(r, c, nr, nc);
            quadrantArea[q]++;
            if (white[r * width + c]) { total++; rows[r - 1]++; columns[c - 1]++; quadrantWhite[q]++; }
        }
        int components = components(width, white);
        List<String> violations = new ArrayList<>();
        List<Run> runs = List.of();
        try { runs = Board.geometry(height, width, white); }
        catch (IllegalArgumentException e) { violations.add("legality: " + e.getMessage()); }
        boolean[] clues = new boolean[white.length], clueRows = new boolean[height], clueColumns = new boolean[width], clueQuadrants = new boolean[4];
        for (Run run : runs) {
            if (run.direction() == Run.Direction.ACROSS) across++; else down++;
            histogram[run.cells().size()]++;
            int r = run.clueCell() / width, c = run.clueCell() % width;
            if (r > 0 && c > 0) {
                clues[run.clueCell()] = true; clueRows[r] = true; clueColumns[c] = true;
                clueQuadrants[quadrant(r, c, nr, nc)] = true;
            }
        }
        int longest = 0, maxBand = 0;
        for (int slope : new int[]{-1, 1}) {
            int extent = 2 * Math.max(nr,nc);
            int[] bands = new int[2 * extent + 1];
            // A white cell contributes to exactly three neighboring offsets.
            for (int r = 1; r < height; r++) for (int c = 1; c < width; c++) {
                if (!white[r * width + c]) continue;
                int offset = c - slope * r + extent;
                for (int i = Math.max(0,offset-1); i <= Math.min(bands.length-1,offset+1); i++) bands[i]++;
            }
            for (int band : bands) maxBand = Math.max(maxBand,band);
            int[] lengths = new int[white.length];
            for (int r = height-1; r > 0; r--) for (int c = 1; c < width; c++) if (white[r*width+c]) {
                int next = c+slope;
                int length = 1 + (r+1 < height && next > 0 && next < width ? lengths[(r+1)*width+next] : 0);
                lengths[r*width+c] = length;
                longest = Math.max(longest,length);
            }
        }
        double density = total / (double) (nr * nc);
        double balance = Math.max(across, down) == 0 ? 0 : Math.min(across, down) / (double) Math.max(across, down);
        List<Double> quadrants = new ArrayList<>();
        for (int i = 0; i < 4; i++) quadrants.add(quadrantWhite[i] / (double) quadrantArea[i]);
        int minRow = Arrays.stream(rows).min().orElse(0), minColumn = Arrays.stream(columns).min().orElse(0);
        double band = total == 0 ? 1 : maxBand / (double) total;
        Metrics metrics = new Metrics(density, Arrays.stream(rows).filter(x -> x > 0).count() / (double) nr,
                Arrays.stream(columns).filter(x -> x > 0).count() / (double) nc, minRow, minColumn,
                across, down, balance, Arrays.stream(histogram).boxed().toList(), components, List.copyOf(quadrants),
                longest, band, count(clues), count(clueRows), count(clueColumns), count(clueQuadrants));
        if (density < 0.52 || density > 0.80) violations.add("interior density outside [0.52, 0.80]");
        int minimumRow = (int) Math.ceil(nc * 0.40), minimumColumn = (int) Math.ceil(nr * 0.40);
        if (minRow < minimumRow || minColumn < minimumColumn) violations.add("insufficient row/column coverage");
        if (balance < 0.70 || across < nr || down < nc) violations.add("unbalanced or too few crossing runs");
        if (components != 1) violations.add("white cells must form one component");
        if (quadrants.stream().anyMatch(q -> q < 0.40)) violations.add("underfilled quadrant");
        if (Arrays.stream(histogram).filter(x -> x > 0).count() < 2 || histogram[2] > 0.65 * runs.size()) violations.add("insufficient run-length variety");
        double bandLimit = Math.max(height, width) <= 10 ? (Math.max(height, width) <= 8 ? 0.62 : 0.55)
                : Math.max(0.25, 4.5 / Math.max(nr, nc));
        if (band > bandLimit) violations.add("diagonal-band concentration");
        if (longest > n - 1 && band > 0.48) violations.add("uninterrupted dominant diagonal");
        if (count(clues) < Math.max(n - 2, (int) Math.ceil(nr * nc * 0.10))
                || count(clueRows) < Math.max(3, (int) Math.ceil(nr * 0.30))
                || count(clueColumns) < Math.max(3, (int) Math.ceil(nc * 0.30)) || count(clueQuadrants) < 3) {
            violations.add("insufficiently distributed interior clues");
        }
        return new Evaluation(metrics, violations);
    }

    private static int quadrant(int r, int c, int nr, int nc) { return (r > (nr + 1) / 2 ? 2 : 0) + (c > (nc + 1) / 2 ? 1 : 0); }
    private static int count(boolean[] flags) { int n = 0; for (boolean v : flags) if (v) n++; return n; }
    private static int components(int size, boolean[] white) {
        boolean[] seen = new boolean[white.length];
        int components = 0;
        for (int i = 0; i < white.length; i++) {
            if (!white[i] || seen[i]) continue;
            components++; seen[i] = true;
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(i);
            while (!queue.isEmpty()) {
                int c = queue.remove();
                for (int next : new int[]{c - size, c + size, c % size > 0 ? c - 1 : -1, c % size < size - 1 ? c + 1 : -1}) {
                    if (next >= 0 && next < white.length && white[next] && !seen[next]) { seen[next] = true; queue.add(next); }
                }
            }
        }
        return components;
    }
}
