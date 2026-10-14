package io.github.skr35.kakuro.pdf;

import io.github.skr35.kakuro.model.Puzzle;
import java.util.ArrayList;
import java.util.List;

/** Bounded, order-preserving packing of compact answer keys. Dimensions are PDF points. */
public final class SolutionLayout {
    public record Options(int perPage, float cellSize) {
        public Options {
            if (perPage != 1 && perPage != 2 && perPage != 3 && perPage != 4 && perPage != 6)
                throw new IllegalArgumentException("--solutions-per-page must be 1, 2, 3, 4 or 6");
            if (!Float.isFinite(cellSize) || cellSize < 14 || cellSize > 72)
                throw new IllegalArgumentException("--solution-cell-size must be 14..72 points");
        }
        public static Options defaults() { return new Options(6, 20); }
    }
    public record Tile(int puzzleIndex, float left, float bottom, float cell, float labelY) { }
    public record Sheet(int columns, int rows, List<Tile> tiles) {
        public Sheet { tiles = List.copyOf(tiles); }
    }
    public record Plan(int totalPages, List<Sheet> sheets) {
        public Plan { sheets = List.copyOf(sheets); }
    }
    private SolutionLayout() { }

    public static Plan plan(List<Puzzle> puzzles, PrintLayout layout, Options options) {
        if (puzzles.isEmpty()) throw new IllegalArgumentException("Cannot lay out an empty booklet");
        int pages = puzzles.size() + (puzzles.size() + options.perPage() - 1) / options.perPage();
        // Repack if the actual total crosses an automatic gutter threshold.
        for (int iteration = 0; iteration < 10; iteration++) {
            List<Sheet> sheets = pack(puzzles, layout, options, pages);
            int actual = puzzles.size() + sheets.size();
            if (actual == pages) {
                layout.validate(actual);
                return new Plan(actual, sheets);
            }
            pages = actual;
        }
        throw new IllegalArgumentException("Solution layout did not stabilize at a binding margin");
    }

    private static List<Sheet> pack(List<Puzzle> puzzles, PrintLayout layout, Options options, int pages) {
        List<Sheet> sheets = new ArrayList<>();
        int[][] formats = {{2,3},{2,2},{1,3},{2,1},{1,2},{1,1}};
        for (int offset = 0; offset < puzzles.size();) {
            int page = puzzles.size() + sheets.size() + 1;
            Sheet best = null;
            for (int[] format : formats) {
                int columns = format[0], rows = format[1], capacity = columns * rows;
                if (capacity > options.perPage()) continue;
                float width = layout.width() - layout.left(page,pages) - layout.right(page,pages) - 2;
                float height = layout.height() - (float)((layout.top()+layout.bottom())*72) - 90;
                float tileWidth = (width - (columns-1)*18) / columns;
                float tileHeight = (height - (rows-1)*20) / rows;
                List<Tile> tiles = new ArrayList<>();
                for (int slot=0;slot<capacity && offset+slot<puzzles.size();slot++) {
                    var board = puzzles.get(offset+slot).board();
                    float cell = Math.min(options.cellSize(), Math.min(tileWidth/board.cols(), (tileHeight-22)/board.rows()));
                    if (cell < 14) break; // 0.53 * 14 = 7.42 point answer digits.
                    float left = layout.left(page,pages)+1+(slot%columns)*(tileWidth+18)
                            +(tileWidth-board.cols()*cell)/2;
                    float top = layout.height()-(float)(layout.top()*72)-50-(slot/columns)*(tileHeight+20);
                    tiles.add(new Tile(offset+slot,left,top-22-board.rows()*cell,cell,top-10));
                }
                if (best == null || tiles.size() > best.tiles().size()) best = new Sheet(columns,rows,tiles);
            }
            if (best == null || best.tiles().isEmpty())
                throw new IllegalArgumentException("Solution cannot fit with legible digits; reduce margins");
            sheets.add(best);
            offset += best.tiles().size();
        }
        return sheets;
    }
}
