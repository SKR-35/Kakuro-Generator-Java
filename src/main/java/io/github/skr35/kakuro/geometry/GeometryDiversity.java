package io.github.skr35.kakuro.geometry;

import io.github.skr35.kakuro.model.Board;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class GeometryDiversity {
    public static final double MINIMUM_DISTANCE = 0.14;
    private GeometryDiversity() { }

    /** Area-normalized Hamming distance. Rectangles use only dimension-preserving symmetries. */
    public static double distance(Board a, Board b) {
        int ar = a.rows()-1, ac = a.cols()-1, br = b.rows()-1, bc = b.cols()-1;
        int height = ar * br, width = ac * bc;
        double best = 1;
        boolean squares = a.rows() == a.cols() && b.rows() == b.cols();
        // Equal-size masks need no expanded common sampling grid. This is exactly
        // the same distance, with each repeated comparison evaluated only once.
        if (ar == br && ac == bc) {
            for (int transform = 0; transform < (squares ? 8 : 4); transform++) {
                int differences = 0;
                for (int r = 0; r < ar; r++) for (int c = 0; c < ac; c++)
                    if (a.isWhite((r + 1) * a.cols() + c + 1) != cell(b,r,c,transform,squares)) differences++;
                best = Math.min(best,differences/(double)(ar*ac));
            }
            return best;
        }
        for (int transform = 0; transform < (squares ? 8 : 4); transform++) {
            int differences = 0;
            for (int r = 0; r < height; r++) for (int c = 0; c < width; c++) {
                boolean av = a.isWhite((r / br + 1) * a.cols() + c / bc + 1);
                if (av != cell(b, r / ar, c / ac, transform, squares)) differences++;
            }
            best = Math.min(best, differences / (double) (height * width));
        }
        return best;
    }

    public static boolean accepts(Board candidate, List<Board> accepted) {
        return accepted.stream().allMatch(previous -> distance(candidate, previous) + 1e-12 >= MINIMUM_DISTANCE);
    }

    public static String fingerprint(Board board) {
        String canonical = null;
        int nr = board.rows()-1, nc = board.cols()-1;
        boolean square = nr == nc;
        for (int t = 0; t < (square ? 8 : 4); t++) {
            StringBuilder mask = square ? new StringBuilder("geometry-v2:").append(nr).append(':')
                    : new StringBuilder("geometry-rect-v1:").append(nr).append('x').append(nc).append(':');
            for (int r = 0; r < nr; r++) for (int c = 0; c < nc; c++) mask.append(cell(board, r, c, t, square) ? '1' : '0');
            String s = mask.toString();
            if (canonical == null || s.compareTo(canonical) < 0) canonical = s;
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static boolean cell(Board board, int r, int c, int transform, boolean square) {
        int nr = board.rows()-1, nc = board.cols()-1;
        if (square) {
            if (transform >= 4) c = nc - 1 - c;
            for (int rotation = 0; rotation < transform % 4; rotation++) { int previous = r; r = c; c = nc - 1 - previous; }
        } else {
            if ((transform & 1) != 0) r = nr - 1 - r;
            if ((transform & 2) != 0) c = nc - 1 - c;
        }
        return board.isWhite((r + 1) * board.cols() + c + 1);
    }
}
