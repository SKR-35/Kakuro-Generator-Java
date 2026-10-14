package io.github.skr35.kakuro.geometry;

import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.model.Run;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Inspection utility only: produces an actual-grid contact sheet and per-puzzle metric CSV. */
public final class GeometryReport {
    private GeometryReport() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: GeometryReport database.db output-directory");
        Path directory = Path.of(args[1]); Files.createDirectories(directory);
        try (var repository = new PuzzleRepository(Path.of(args[0]))) {
            List<PuzzleRepository.Saved> records = repository.all();
            if (records.isEmpty()) throw new IllegalArgumentException("Database contains no puzzles");
            int width = 1260, height = 70 + ((records.size() + 2) / 3) * 450;
            int scale = records.stream().anyMatch(r -> Math.max(r.puzzle().board().rows(), r.puzzle().board().cols()) > 10) ? 2 : 1;
            BufferedImage image = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.scale(scale, scale);
                g.setColor(Color.WHITE); g.fillRect(0, 0, width, height);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.BLACK); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
                g.drawString("KAKURO / GEOMETRY REVIEW / " + records.size() + " PUZZLES", 25, 38);
                StringBuilder csv = new StringBuilder("id,difficulty,rows,cols,score,density,min_row_white,min_col_white,across,down,balance,components,q1,q2,q3,q4,diagonal_length,diagonal_share,interior_clues,clue_rows,clue_columns,clue_quadrants,quality,geometry_sha256\n");
                var evaluator = new GeometryQualityEvaluator();
                for (int i = 0; i < records.size(); i++) {
                    var saved = records.get(i); Puzzle p = saved.puzzle();
                    var evaluation = evaluator.evaluate(p.board().rows(), p.board().cols(), p.board().white());
                    var m = evaluation.metrics();
                    int x = 25 + i % 3 * 415, y = 72 + i / 3 * 450;
                    g.setColor(evaluation.accepted() ? Color.BLACK : Color.RED);
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
                    g.drawString("#" + saved.id() + "  " + p.difficulty() + "  " + p.board().rows() + " x " + p.board().cols(), x, y);
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                    g.drawString(String.format(Locale.ROOT,"density %.2f | runs %d/%d | score %.2f", m.density(),m.acrossRuns(),m.downRuns(),p.score()),x,y+20);
                    draw(g, p, x, y + 32, 370);
                    g.setColor(Color.BLACK); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                    g.drawString(String.format(Locale.ROOT,"diagonal share %.2f | interior clues %d | %s",m.diagonalBandShare(),m.interiorClues(),evaluation.accepted()?"QUALITY PASS":"QUALITY FAIL"),x,y+420);
                    csv.append(String.format(Locale.ROOT,"%d,%s,%d,%d,%.6f,%.6f,%d,%d,%d,%d,%.6f,%d,%.6f,%.6f,%.6f,%.6f,%d,%.6f,%d,%d,%d,%d,%s,%s%n",
                            saved.id(),p.difficulty(),p.board().rows(),p.board().cols(),p.score(),m.density(),m.minimumRowWhites(),m.minimumColumnWhites(),m.acrossRuns(),m.downRuns(),m.runBalance(),m.components(),
                            m.quadrantDensities().get(0),m.quadrantDensities().get(1),m.quadrantDensities().get(2),m.quadrantDensities().get(3),m.longestWhiteDiagonal(),m.diagonalBandShare(),m.interiorClues(),m.interiorClueRows(),m.interiorClueColumns(),m.interiorClueQuadrants(),evaluation.accepted(),GeometryDiversity.fingerprint(p.board())));
                }
                Files.writeString(directory.resolve("metrics.csv"), csv);
                StringBuilder distances = new StringBuilder("first_id,second_id,symmetry_minimum_hamming\n");
                double minimum = 1;
                for (int a = 0; a < records.size(); a++) for (int b = a + 1; b < records.size(); b++) {
                    double d = GeometryDiversity.distance(records.get(a).puzzle().board(), records.get(b).puzzle().board());
                    minimum = Math.min(minimum, d);
                    distances.append(String.format(Locale.ROOT,"%d,%d,%.9f%n",records.get(a).id(),records.get(b).id(),d));
                }
                Files.writeString(directory.resolve("distances.csv"), distances);
                ImageIO.write(image,"PNG",directory.resolve("contact-sheet.png").toFile());
                System.out.printf(Locale.ROOT,"Rendered %d grids; minimum symmetry-aware layout distance %.6f%n",records.size(),minimum);
            } finally { g.dispose(); }
        }
    }

    private static void draw(Graphics2D g, Puzzle p, int x, int y, int width) {
        int rows = p.board().rows(), cols = p.board().cols(); double cell = width / (double) Math.max(rows, cols);
        x += (int) ((width - cols * cell) / 2); y += (int) ((width - rows * cell) / 2);
        int[] across = new int[rows * cols], down = new int[rows * cols];
        for (Run run : p.board().runs()) (run.direction() == Run.Direction.ACROSS ? across : down)[run.clueCell()] = run.sum();
        g.setStroke(new BasicStroke(1));
        g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,(int)(cell*0.25)));
        for(int r=0;r<rows;r++) for(int c=0;c<cols;c++) {
            int i=r*cols+c, xx=x+(int)Math.round(c*cell), yy=y+(int)Math.round(r*cell);
            int right=x+(int)Math.round((c+1)*cell), bottom=y+(int)Math.round((r+1)*cell);
            g.setColor(p.board().isWhite(i)?Color.WHITE:new Color(205,205,205));g.fillRect(xx,yy,right-xx,bottom-yy);
            g.setColor(Color.BLACK);g.drawRect(xx,yy,right-xx,bottom-yy);
            if(across[i]!=0 || down[i]!=0) {
                g.drawLine(xx,yy,right,bottom);
                if(across[i]!=0) {String text=Integer.toString(across[i]);g.drawString(text,right-g.getFontMetrics().stringWidth(text)-(int)(cell*.07), yy+(int)(cell*.35));}
                if(down[i]!=0)g.drawString(Integer.toString(down[i]),xx+(int)(cell*.08),yy+(int)(cell*.85));
            }
        }
    }
}
