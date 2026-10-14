package io.github.skr35.kakuro.pdf;

import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.model.Run;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/** Vector booklets: legacy A4 layouts or fixed-trim, no-bleed paperback interiors. */
public final class PdfBooklet {
    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    public void write(Path file, List<Puzzle> puzzles) throws IOException {
        write(file, puzzles, null);
    }

    /** A null print layout preserves the original A4 behavior. */
    public void write(Path file, List<Puzzle> puzzles, PrintLayout layout) throws IOException {
        write(file, puzzles, layout, SolutionLayout.Options.defaults());
    }

    public void write(Path file, List<Puzzle> puzzles, PrintLayout layout, SolutionLayout.Options solutions) throws IOException {
        if (puzzles.isEmpty()) throw new IllegalArgumentException("Cannot export an empty booklet");
        SolutionLayout.Plan plan = layout == null ? null : SolutionLayout.plan(puzzles, layout, solutions);
        if (layout != null) {
            int pages = plan.totalPages();
            layout.validate(pages);
            for (Puzzle puzzle : puzzles) layout.grid(puzzle.board().rows(), puzzle.board().cols(), 1, pages);
        }
        Path destination = file.toAbsolutePath();
        Path temp = Files.createTempFile(destination.getParent(), "kakuro-", ".pdf");
        try {
            try (PDDocument document = new PDDocument()) {
                document.getDocumentInformation().setTitle("Kakuro puzzles and solutions");
                document.getDocumentInformation().setCreator("Kakuro Generator Java");
                if (layout != null) {
                    KdpBooklet.render(document, puzzles, layout, plan);
                } else {
                for (int i = 0; i < puzzles.size(); i++) {
                    Puzzle puzzle = puzzles.get(i);
                    puzzlePage(document, puzzle, i, puzzles.size(), false);
                }
                // Preserve compact four-up solutions for legacy small-square booklets.
                boolean compact = puzzles.stream().allMatch(p -> p.board().rows() == p.board().cols() && p.board().rows() <= 10);
                if (!compact) {
                    for (int i = 0; i < puzzles.size(); i++) puzzlePage(document, puzzles.get(i), i, puzzles.size(), true);
                } else {
                for (int offset = 0; offset < puzzles.size(); offset += 4) {
                    PDPage page = new PDPage(PDRectangle.A4);
                    document.addPage(page);
                    try (PDPageContentStream s = new PDPageContentStream(document, page)) {
                        text(s, BOLD, 22, 42, 789, "SOLUTIONS");
                        for (int j = 0; j < 4 && offset + j < puzzles.size(); j++) {
                            Puzzle puzzle = puzzles.get(offset + j);
                            float x = 42 + (j % 2) * 264;
                            float y = j < 2 ? 470 : 150;
                            text(s, BOLD, 10, x, y + 240, "Puzzle " + (offset + j + 1) + " - " + puzzle.difficulty());
                            grid(s, puzzle, x, y, 225f / puzzle.board().cols(), true);
                        }
                    }
                }
                }
                }
                document.save(temp.toFile());
            }
            if (layout != null) KdpPdfValidator.validate(temp, layout, plan.totalPages());
            try { Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    /** A4 orientation follows the board; every grid uses square cells within a measured box. */
    private static void puzzlePage(PDDocument document, Puzzle puzzle, int index, int total, boolean solution) throws IOException {
        Board board = puzzle.board();
        PDRectangle media = board.cols() > board.rows()
                ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
        PDPage page = new PDPage(media);
        document.addPage(page);
        float width = media.getWidth(), height = media.getHeight();
        float cell = Math.min((width - 84) / board.cols(), (height - 220) / board.rows());
        float left = (width - cell * board.cols()) / 2;
        float bottom = 105 + (height - 220 - cell * board.rows()) / 2;
        try (PDPageContentStream s = new PDPageContentStream(document, page)) {
            text(s, BOLD, 22, 42, height - 50, (solution ? "SOLUTIONS  /  " : "KAKURO  /  ") + (index + 1));
            text(s, REGULAR, 11, 42, height - 74, puzzle.difficulty() + "  |  " + board.rows() + " x " + board.cols()
                    + "  |  Score " + String.format(Locale.ROOT, "%.2f", puzzle.score()));
            text(s, REGULAR, 9, 42, height - 96, solution ? "Puzzle " + (index + 1) + " - " + puzzle.difficulty()
                    : "Fill white cells with 1-9. Each run sums to its clue, with no repeated digits.");
            grid(s, puzzle, left, bottom, cell, solution);
            text(s, REGULAR, 9, 42, 76, "Across clues: upper-right triangle. Down clues: lower-left triangle.");
            text(s, REGULAR, 8, 42, 59, "Difficulty is an initial computer score, not a calibrated human-solving rating.");
            text(s, REGULAR, 8, 42, 40, "Puzzle " + (index + 1) + " of " + total
                    + "  |  Fingerprint " + puzzle.fingerprint().substring(0, 16));
        }
    }

    private static void grid(PDPageContentStream s, Puzzle puzzle, float left, float bottom, float cell,
                             boolean solution) throws IOException {
        grid(s, puzzle, left, bottom, cell, solution, REGULAR, BOLD, false);
    }

    static void grid(PDPageContentStream s, Puzzle puzzle, float left, float bottom, float cell,
                     boolean solution, PDFont regular, PDFont bold, boolean print) throws IOException {
        grid(s, puzzle, left, bottom, cell, solution, regular, bold, print, true);
    }

    static void grid(PDPageContentStream s, Puzzle puzzle, float left, float bottom, float cell,
                     boolean solution, PDFont regular, PDFont bold, boolean print, boolean clues) throws IOException {
        Board board = puzzle.board();
        int[] across = new int[board.cellCount()], down = new int[across.length];
        for (Run run : board.runs()) {
            (run.direction() == Run.Direction.ACROSS ? across : down)[run.clueCell()] = run.sum();
        }
        int[] values = puzzle.solution();
        for (int row = 0; row < board.rows(); row++) for (int col = 0; col < board.cols(); col++) {
            int index = row * board.cols() + col;
            float x = left + col * cell, y = bottom + (board.rows() - 1 - row) * cell;
            s.setNonStrokingColor(board.isWhite(index) ? 1f : 0.83f);
            s.addRect(x, y, cell, cell); s.fill();
            s.setStrokingColor(0.12f); s.setLineWidth(print ? 0.8f : solution ? 0.5f : 0.8f);
            s.addRect(x, y, cell, cell); s.stroke();
            if (board.isWhite(index)) {
                if (solution) {
                    float fs = cell * 0.53f;
                    String digit = Integer.toString(values[index]);
                    text(s, regular, fs, x + (cell - regular.getStringWidth(digit) / 1000 * fs) / 2,
                            y + cell * 0.32f, digit);
                }
            } else if (clues && (across[index] != 0 || down[index] != 0)) {
                s.moveTo(x, y + cell); s.lineTo(x + cell, y); s.stroke();
                float fs = print ? Math.max(7, cell * 0.25f) : cell * 0.25f;
                if (across[index] != 0) {
                    String clue = Integer.toString(across[index]);
                    text(s, bold, fs, x + cell * 0.92f - bold.getStringWidth(clue) / 1000 * fs,
                            y + cell * 0.66f, clue);
                }
                if (down[index] != 0) text(s, bold, fs, x + cell * 0.09f, y + cell * 0.14f, Integer.toString(down[index]));
            }
        }
    }

    static void text(PDPageContentStream s, PDFont font, float size, float x, float y, String text) throws IOException {
        s.setNonStrokingColor(0f); s.beginText(); s.setFont(font, size);
        s.newLineAtOffset(x, y); s.showText(text); s.endText();
    }
}
