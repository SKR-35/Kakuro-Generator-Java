package io.github.skr35.kakuro;

import io.github.skr35.kakuro.cli.KakuroCommand;
import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.pdf.*;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KdpPdfTest {
    @TempDir Path temp;

    // Four independently audited v4 solutions. No generation work or local DB required by tests.
    static List<Puzzle> fixtures() throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(KdpPdfTest.class.getResourceAsStream("/kdp-fixtures.csv")), StandardCharsets.UTF_8))) {
            return reader.lines().skip(1).map(line -> {
                String[] fields = line.split(",");
                int rows = Integer.parseInt(fields[0]), cols = Integer.parseInt(fields[1]);
                int[] values = fields[2].chars().map(c -> c - '0').toArray();
                boolean[] white = new boolean[values.length];
                for (int i=0; i<values.length; i++) white[i] = values[i] != 0;
                return new Puzzle(Board.fromSolution(rows, cols, white, values), values, Difficulty.MEDIUM,
                        Double.parseDouble(fields[3]), 0, Long.parseLong(fields[4]));
            }).toList();
        }
    }

    @ParameterizedTest @ValueSource(strings={"kdp-8.25", "kdp-8.50"})
    void exactTrimEmbeddedFontsMirroredMarginsAndRectangularRendering(String preset) throws Exception {
        var layout = PrintLayout.defaults(preset);
        List<Puzzle> puzzles = fixtures();
        Path file = temp.resolve(preset + ".pdf");
        new PdfBooklet().write(file, puzzles, layout);
        var plan = SolutionLayout.plan(puzzles, layout, SolutionLayout.Options.defaults());
        int pages = plan.totalPages();
        KdpPdfValidator.validate(file, layout, pages);
        try (var document = Loader.loadPDF(file.toFile())) {
            assertEquals(pages, document.getNumberOfPages());
            var renderer = new PDFRenderer(document);
            for (int index=0; index<pages; index++) {
                int number = index + 1;
                var page = document.getPage(index);
                assertEquals(preset.equals("kdp-8.25") ? 594 : 612, page.getMediaBox().getWidth());
                assertEquals(792, page.getMediaBox().getHeight());
                assertEquals(0, page.getRotation());
                var text = new PDFTextStripper(); text.setStartPage(number); text.setEndPage(number);
                String content = text.getText(document);
                assertTrue(content.contains(index < 4 ? "KAKURO " + (index+1) : "SOLUTIONS"));
                assertTrue(content.contains("Page " + number + " of " + pages));
                if (index >= 4) for (var tile : plan.sheets().get(index-4).tiles())
                    assertTrue(content.contains("Puzzle " + (tile.puzzleIndex()+1)));
                // Actual raster, including strokes and text: every mark must be inside mirrored margins.
                var image = renderer.renderImageWithDPI(index, 72);
                int marks = 0;
                for (int y=0; y<image.getHeight(); y++) for (int x=0; x<image.getWidth(); x++) {
                    if ((image.getRGB(x,y)&0xffffff) == 0xffffff) continue;
                    marks++;
                    assertTrue(x >= Math.floor(layout.left(number,pages)) && x < Math.ceil(layout.width()-layout.right(number,pages))
                            && y >= Math.floor(layout.top()*72) && y < Math.ceil(792-layout.bottom()*72),
                            "Ink outside margins, page " + number + " at " + x + "," + y);
                }
                assertTrue(marks > 1000);
                // White cells remain empty in puzzles; solved pages contain actual printed digits.
                if (index < 4) {
                    var board = puzzles.get(index).board();
                    var grid = layout.grid(board.rows(),board.cols(),number,pages);
                    assertEquals(0, whiteInk(image,board,grid.left(),grid.bottom(),grid.cell()));
                } else for (var tile:plan.sheets().get(index-4).tiles()) {
                    var board=puzzles.get(tile.puzzleIndex()).board();
                    assertTrue(whiteInk(image,board,tile.left(),tile.bottom(),tile.cell())>100);
                }
            }
        }
        assertEquals(45, layout.left(1,8)); assertEquals(36, layout.left(2,8));
        assertEquals(layout.left(1,8), layout.right(2,8));
    }

    private static int whiteInk(java.awt.image.BufferedImage image,Board board,float left,float bottom,float cellSize) {
        int ink=0;
        for(int cell=0;cell<board.cellCount();cell++) if(board.isWhite(cell)) {
            float x0=left+(cell%board.cols())*cellSize;
            float y0=792-bottom-(board.rows()-cell/board.cols())*cellSize;
            for(int y=(int)Math.ceil(y0+cellSize*.25);y<y0+cellSize*.8;y++)
                for(int x=(int)Math.ceil(x0+cellSize*.25);x<x0+cellSize*.75;x++)
                    if((image.getRGB(x,y)&0xffffff)!=0xffffff)ink++;
        }
        return ink;
    }

    @ParameterizedTest @ValueSource(strings={"kdp-8.25","kdp-8.50"})
    void compactPackingAdaptsAndKeepsOrder(String preset) throws Exception {
        var layout=PrintLayout.defaults(preset);
        var fixtures=fixtures();
        int[] expected={6,4,3,4};
        for(int kind=0;kind<4;kind++) {
            var puzzles=Collections.nCopies(7,fixtures.get(kind));
            var plan=SolutionLayout.plan(puzzles,layout,SolutionLayout.Options.defaults());
            assertEquals(expected[kind],plan.sheets().getFirst().tiles().size());
            assertEquals(7,plan.sheets().stream().mapToInt(s->s.tiles().size()).sum());
            int next=0;
            for(var sheet:plan.sheets()) for(var tile:sheet.tiles()) {
                assertEquals(next++,tile.puzzleIndex());
                assertTrue(tile.cell()>=14 && tile.cell()<=20);
                var board=puzzles.get(tile.puzzleIndex()).board();
                assertTrue(tile.labelY()>tile.bottom()+board.rows()*tile.cell());
                for(var other:sheet.tiles()) if(other.puzzleIndex()!=tile.puzzleIndex()) {
                    var otherBoard=puzzles.get(other.puzzleIndex()).board();
                    assertTrue(tile.left()+board.cols()*tile.cell()<=other.left()
                            || other.left()+otherBoard.cols()*other.cell()<=tile.left()
                            || tile.labelY()+10<=other.bottom()
                            || other.labelY()+10<=tile.bottom(), "Overlapping answer tiles");
                }
            }
            Path file=temp.resolve(preset+"-"+kind+".pdf");
            new PdfBooklet().write(file,puzzles,layout);
            KdpPdfValidator.validate(file,layout,plan.totalPages());
        }
        var options=new SolutionLayout.Options(2,16);
        var plan=SolutionLayout.plan(Collections.nCopies(7,fixtures.getFirst()),layout,options);
        assertEquals(List.of(2,2,2,1),plan.sheets().stream().map(s->s.tiles().size()).toList());
        assertEquals(16,plan.sheets().getFirst().tiles().getFirst().cell());
        assertThrows(IllegalArgumentException.class,()->new SolutionLayout.Options(5,20));
        assertThrows(IllegalArgumentException.class,()->new SolutionLayout.Options(6,13));
    }

    @Test void actualCompactPageCountControlsGutter() throws Exception {
        var puzzles=Collections.nCopies(440,fixtures().getFirst());
        var layout=PrintLayout.defaults("kdp-8.25");
        var plan=SolutionLayout.plan(puzzles,layout,SolutionLayout.Options.defaults());
        assertEquals(514,plan.totalPages());
        assertEquals(54,layout.inside(plan.totalPages()));
        assertThrows(IllegalArgumentException.class,()->SolutionLayout.plan(puzzles,
                new PrintLayout("kdp-8.25",.625,.5,.5,.5,0),SolutionLayout.Options.defaults()));
    }

    @Test void puzzlePagesStayIdenticalApartFromTotalPageFooter() throws Exception {
        var puzzles=Collections.nCopies(6,fixtures().getFirst());
        var layout=PrintLayout.defaults("kdp-8.25");
        Path compact=temp.resolve("compact.pdf"),single=temp.resolve("single.pdf");
        new PdfBooklet().write(compact,puzzles,layout);
        new PdfBooklet().write(single,puzzles,layout,new SolutionLayout.Options(1,48));
        try(var a=Loader.loadPDF(compact.toFile());var b=Loader.loadPDF(single.toFile())) {
            assertEquals(7,a.getNumberOfPages()); assertEquals(12,b.getNumberOfPages());
            for(int i=0;i<6;i++) {
                var x=new PDFRenderer(a).renderImageWithDPI(i,72);
                var y=new PDFRenderer(b).renderImageWithDPI(i,72);
                for(int row=0;row<740;row++)for(int col=0;col<x.getWidth();col++)
                    assertEquals(x.getRGB(col,row),y.getRGB(col,row));
            }
        }
    }

    @Test void gutterAndPageCountValidation() {
        assertEquals(.375, PrintLayout.minimumInner(150)); assertEquals(.5, PrintLayout.minimumInner(151));
        assertEquals(.5, PrintLayout.minimumInner(300)); assertEquals(.625, PrintLayout.minimumInner(301));
        assertEquals(.625, PrintLayout.minimumInner(500)); assertEquals(.75, PrintLayout.minimumInner(501));
        var auto = PrintLayout.defaults("kdp-8.25");
        assertEquals(54, auto.inside(502));
        var explicit = new PrintLayout("kdp-8.50", .5, .6, .7, .8, .125);
        assertEquals(45, explicit.inside(300));
        assertThrows(IllegalArgumentException.class, () -> explicit.validate(502));
        assertThrows(IllegalArgumentException.class, () -> auto.validate(592));
        assertThrows(IllegalArgumentException.class, () -> new PrintLayout("kdp-8.25", Double.NaN,.5,.5,.5,0));
        assertThrows(IllegalArgumentException.class, () -> new PrintLayout("kdp-8.25", null,.2,.5,.5,0));
        assertThrows(IllegalArgumentException.class, () -> new PrintLayout("kdp-8.25", null,.5,.5,.5,-1));
    }

    @Test void rejectedLayoutPreservesExistingOutputAndCustomMarginsRender() throws Exception {
        Path file = temp.resolve("existing.pdf"); Files.writeString(file,"keep existing output");
        var tooNarrow = new PrintLayout("kdp-8.25", 2.0, 2, .5, .5, 0);
        assertThrows(IllegalArgumentException.class, () -> new PdfBooklet().write(file, fixtures(), tooNarrow));
        assertEquals("keep existing output",Files.readString(file));
        var custom = new PrintLayout("kdp-8.50", .6, .4, .7, .8, .1);
        new PdfBooklet().write(file, fixtures(), custom);
        int pages = SolutionLayout.plan(fixtures(),custom,SolutionLayout.Options.defaults()).totalPages();
        KdpPdfValidator.validate(file,custom,pages);
        try(var pdf=Loader.loadPDF(file.toFile())) {
            pdf.getPage(0).setRotation(90); pdf.save(temp.resolve("invalid.pdf").toFile());
        }
        assertThrows(IOException.class, () -> KdpPdfValidator.validate(temp.resolve("invalid.pdf"),custom,pages));
    }

    @ParameterizedTest @ValueSource(strings={"--page-size letter", "--margin-inner 0.5", "--page-size kdp-8.25 --margin-top 0.1",
            "--page-size kdp-8.50 --gutter -1", "--page-size kdp-8.25 --margin-outer NaN",
            "--solutions-per-page 2", "--page-size kdp-8.25 --solutions-per-page 5",
            "--page-size kdp-8.50 --solution-cell-size NaN"})
    void cliRejectsInvalidLayout(String args) {
        var command = new CommandLine(new KakuroCommand()).setErr(new PrintWriter(new StringWriter()));
        assertEquals(2,command.execute((args+" --easy 0").split(" ")));
    }

    @Test void cliExportsBothPresetsFromExistingDatabase() throws Exception {
        Path db=temp.resolve("puzzles.db");
        try(var repo=new PuzzleRepository(db)) { repo.save(Fixtures.unique()); }
        for(String preset:List.of("kdp-8.25","kdp-8.50")) {
            var err=new StringWriter();
            var command=new CommandLine(new KakuroCommand()).setOut(new PrintWriter(new StringWriter())).setErr(new PrintWriter(err));
            Path file=temp.resolve(preset+".pdf");
            assertEquals(0,command.execute("--db",db.toString(),"--export-ids","1","--page-size",preset,"--outfile",file.toString()));
            assertTrue(err.toString().contains("fewer than 24"));
            KdpPdfValidator.validate(file,PrintLayout.defaults(preset),2);
        }
    }
}
