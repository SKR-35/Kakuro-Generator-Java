package io.github.skr35.kakuro.pdf;

import io.github.skr35.kakuro.model.Puzzle;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import java.io.IOException;
import java.util.List;

/** Single-page portrait interiors; wide grids scale without rotating page or content. */
final class KdpBooklet {
    private KdpBooklet() { }
    static void render(PDDocument document, List<Puzzle> puzzles, PrintLayout layout, SolutionLayout.Plan plan) throws IOException {
        // PDFBox distributes this licensed font in its jar; no system-font dependency.
        PDType0Font font;
        try (var stream = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            if (stream == null) throw new IOException("Bundled print font is missing");
            font = PDType0Font.load(document, stream, true);
        }
        int pages = plan.totalPages();
        for (int number = 1; number <= puzzles.size(); number++) {
            boolean solution = false;
            int index = (number - 1) % puzzles.size();
            Puzzle puzzle = puzzles.get(index);
            var grid = layout.grid(puzzle.board().rows(), puzzle.board().cols(), number, pages);
            PDPage page = new PDPage(layout.mediaBox());
            page.setCropBox(layout.mediaBox()); page.setTrimBox(layout.mediaBox()); page.setBleedBox(layout.mediaBox());
            document.addPage(page);
            float left = layout.left(number, pages) + 1;
            float top = layout.height() - (float)(layout.top() * 72);
            float bottom = (float)(layout.bottom() * 72);
            float usable = layout.width() - layout.left(number, pages) - layout.right(number, pages) - 2;
            try (var s = new PDPageContentStream(document, page)) {
                PdfBooklet.text(s, font, 20, left, top - 22, (solution ? "SOLUTION " : "KAKURO ") + (index + 1));
                PdfBooklet.text(s, font, 10, left, top - 42, puzzle.difficulty() + "  |  "
                        + puzzle.board().rows() + " x " + puzzle.board().cols());
                String instruction = solution ? "Solution to puzzle " + (index + 1)
                        : "Use 1-9. Match each clue sum; no repeated digit in a run.";
                float instructionSize = Math.min(9, usable * 1000 / font.getStringWidth(instruction));
                if (instructionSize < 7) throw new IllegalArgumentException("Margins leave insufficient room for instructions");
                PdfBooklet.text(s, font, instructionSize, left, top - 60, instruction);
                PdfBooklet.grid(s, puzzle, grid.left(), grid.bottom(), grid.cell(), solution, font, font, true);
                PdfBooklet.text(s, font, 8, left, bottom + 27, "Across: upper-right clue. Down: lower-left clue.");
                String footer = "Page " + number + " of " + pages;
                float x = number % 2 == 1 ? left + usable - font.getStringWidth(footer) / 1000 * 9 : left;
                PdfBooklet.text(s, font, 9, x, bottom + 7, footer);
            }
        }
        for (int index=0; index<plan.sheets().size(); index++) {
            int number = puzzles.size()+index+1;
            PDPage page = new PDPage(layout.mediaBox());
            page.setCropBox(layout.mediaBox()); page.setTrimBox(layout.mediaBox()); page.setBleedBox(layout.mediaBox());
            document.addPage(page);
            float left=layout.left(number,pages)+1;
            float top=layout.height()-(float)(layout.top()*72);
            try (var s = new PDPageContentStream(document,page)) {
                PdfBooklet.text(s,font,20,left,top-22,"SOLUTIONS");
                for (var tile:plan.sheets().get(index).tiles()) {
                    PdfBooklet.text(s,font,10,tile.left(),tile.labelY(),"Puzzle "+(tile.puzzleIndex()+1));
                    PdfBooklet.grid(s,puzzles.get(tile.puzzleIndex()),tile.left(),tile.bottom(),tile.cell(),true,font,font,true,false);
                }
                String footer="Page "+number+" of "+pages;
                float usable=layout.width()-layout.left(number,pages)-layout.right(number,pages)-2;
                float x=number%2==1?left+usable-font.getStringWidth(footer)/1000*9:left;
                PdfBooklet.text(s,font,9,x,(float)(layout.bottom()*72)+7,footer);
            }
        }
    }
}
