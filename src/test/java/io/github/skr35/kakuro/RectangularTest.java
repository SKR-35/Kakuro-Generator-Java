package io.github.skr35.kakuro;

import io.github.skr35.kakuro.cli.KakuroCommand;
import io.github.skr35.kakuro.batch.BatchGenerator;
import io.github.skr35.kakuro.generator.*;
import io.github.skr35.kakuro.geometry.*;
import io.github.skr35.kakuro.model.*;
import io.github.skr35.kakuro.pdf.PdfBooklet;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import io.github.skr35.kakuro.solver.Solver;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RectangularTest {
    @TempDir Path temp;

    static Board tinyRectangle() {
        return Board.fromSolution(3,4,new boolean[]{false,false,false,false,false,true,true,true,false,true,true,true},
                new int[]{0,0,0,0,0,1,2,3,0,2,4,1});
    }

    static Board transpose(Board original) {
        boolean[] mask = new boolean[original.cellCount()];
        int[] values = new Solver().solve(original,20_000).solutions().getFirst();
        int[] transposed = new int[mask.length];
        for(int r=0;r<original.rows();r++) for(int c=0;c<original.cols();c++) {
            mask[c*original.rows()+r]=original.isWhite(r*original.cols()+c);
            transposed[c*original.rows()+r]=values[r*original.cols()+c];
        }
        return Board.fromSolution(original.cols(),original.rows(),mask,transposed);
    }

    static Puzzle puzzle(Board board) {
        var proof = new Solver().solve(board,20_000);
        assertEquals(Solver.Status.UNIQUE,proof.status());
        double score=Difficulty.score(board,proof.nodes());
        var level=Arrays.stream(Difficulty.values()).filter(d->d.accepts(score)).findFirst().orElseThrow();
        return new Puzzle(board,proof.solutions().getFirst(),level,score,1,proof.nodes());
    }

    @Test void rectangularIndexingAndIndependentProof() {
        Board board=tinyRectangle();
        assertEquals(3,board.rows()); assertEquals(4,board.cols());assertEquals(12,board.cellCount());
        assertThrows(IllegalStateException.class,board::size);
        for(Board b:List.of(board,transpose(board))) {
            var independent=new IndependentSolver(b).solve();
            assertEquals(1,independent.size());
            assertArrayEquals(puzzle(b).solution(),independent.getFirst());
            b.validateSolution(independent.getFirst());
        }
        assertNotEquals(board.canonical(),transpose(board).canonical());
        assertNotEquals(GeometryDiversity.fingerprint(board),GeometryDiversity.fingerprint(transpose(board)));
    }

    @Test void runLengthNineIsLegalButTenIsRejected() {
        boolean[] valid=new boolean[30];
        for(int r=1;r<3;r++)for(int c=1;c<10;c++)valid[r*10+c]=true;
        assertTrue(Board.geometry(3,10,valid).stream().anyMatch(r->r.cells().size()==9));
        boolean[] invalid=new boolean[33];
        for(int r=1;r<3;r++)for(int c=1;c<11;c++)invalid[r*11+c]=true;
        assertThrows(IllegalArgumentException.class,()->Board.geometry(3,11,invalid));
    }

    @Test void rectangularBatchIsReproducibleAcrossWorkerCounts() throws Exception {
        var tasks=List.of(new BatchGenerator.Task(1,Difficulty.MEDIUM,8,12,4949376863275602286L,500),
                new BatchGenerator.Task(2,Difficulty.MEDIUM,10,20,-1885985257786415820L,500));
        List<String> first=new ArrayList<>(),second=new ArrayList<>();
        new BatchGenerator().run(tasks,1,r->{assertNull(r.failure());first.add(r.generated().puzzle().fingerprint());});
        new BatchGenerator().run(tasks,2,r->{assertNull(r.failure());second.add(r.generated().puzzle().fingerprint());});
        assertEquals(first,second);
    }

    @Test void exhaustionRemainsExplicitAndTelemetryCountsProofAttempts() {
        var session=new Generator.Session(Difficulty.MEDIUM,11,11,-7870152323696341927L,1);
        assertThrows(IllegalStateException.class,()->session.next(b->true));
        var stats=session.statistics();
        assertEquals(1,stats.candidates());assertEquals(1,stats.solverCalls());assertEquals(0,stats.unique());
        assertEquals(1,stats.geometryAccepted());assertTrue(stats.geometryMasks()>=stats.geometryAccepted());
    }

    @ParameterizedTest @CsvSource({"11,11","12,12","13,13","14,14","15,15","8,12","10,15","10,20","15,10","20,10"})
    void requestedGeometriesAreLegalAndReproducible(int rows,int cols) {
        var builder=new GeometryBuilder();
        boolean[] white=builder.create(rows,cols,new Random(20261015),Difficulty.MEDIUM);
        assertArrayEquals(white,new GeometryBuilder().create(rows,cols,new Random(20261015),Difficulty.MEDIUM));
        var evaluation=new GeometryQualityEvaluator().evaluate(rows,cols,white);
        assertTrue(evaluation.accepted(),evaluation.violations().toString());
        assertEquals(1,evaluation.metrics().components());
        assertEquals(1,evaluation.metrics().rowCoverage());assertEquals(1,evaluation.metrics().columnCoverage());
        int[] membership=new int[white.length];
        for(var run:Board.geometry(rows,cols,white)) {
            assertTrue(run.cells().size()>=2 && run.cells().size()<=9);
            for(int cell:run.cells())membership[cell]++;
        }
        for(int i=0;i<white.length;i++)assertEquals(white[i]?2:0,membership[i]);
    }

    @Test void rectangleReflectionsButNotQuarterTurnsPreserveGeometryIdentity() {
        int rows=8,cols=12;
        boolean[] white=Generator.geometry(rows,cols,new Random(33),Difficulty.MEDIUM);
        int[] sums=Board.geometry(rows,cols,white).stream().mapToInt(r->r.cells().size()*(r.cells().size()+1)/2).toArray();
        Board original=new Board(rows,cols,white,sums);
        boolean[] reflected=new boolean[white.length], rotated=new boolean[white.length];
        for(int r=1;r<rows;r++)for(int c=1;c<cols;c++) {
            reflected[r*cols+c]=white[r*cols+cols-c];
            rotated[c*rows+(rows-r)]=white[r*cols+c];
        }
        Board mirror=geometry(rows,cols,reflected),turn=geometry(cols,rows,rotated);
        assertEquals(0,GeometryDiversity.distance(original,mirror),1e-12);
        assertEquals(GeometryDiversity.fingerprint(original),GeometryDiversity.fingerprint(mirror));
        assertNotEquals(GeometryDiversity.fingerprint(original),GeometryDiversity.fingerprint(turn));
        assertTrue(GeometryDiversity.distance(original,turn)>0);
    }

    private static Board geometry(int rows,int cols,boolean[] mask) {
        return new Board(rows,cols,mask,Board.geometry(rows,cols,mask).stream().mapToInt(r->r.cells().size()*(r.cells().size()+1)/2).toArray());
    }

    @ParameterizedTest @ValueSource(strings={"--rows 10","--cols 20","--rows 10 --cols 20 --size 10",
            "--rows 10 --cols 20 --evil-size 10","--rows 6 --cols 20","--rows 10 --cols 21"})
    void ambiguousOrInvalidDimensionsAreRejected(String arguments) {
        var cli=new CommandLine(new KakuroCommand()).setErr(new PrintWriter(new StringWriter()));
        assertEquals(2,cli.execute((arguments+" --easy 0 --no-pdf").split(" ")));
    }

    @Test void explicitRectanglesAndLegacyOverridesAreAccepted() {
        var cli=new CommandLine(new KakuroCommand()).setOut(new PrintWriter(new StringWriter()));
        assertEquals(0,cli.execute("--rows","10","--cols","20","--easy","0","--no-pdf"));
        assertEquals(0,new CommandLine(new KakuroCommand()).setOut(new PrintWriter(new StringWriter()))
                .execute("--size","11","--easy-size","15","--easy","0","--no-pdf"));
    }

    @Test void v1MigrationPreservesSquareIdentityAndAddsRectangularRecords() throws Exception {
        Path file=temp.resolve("legacy.db"); Puzzle old=Fixtures.unique();
        try(var repo=new PuzzleRepository(file)){repo.save(old);}
        // Exact legacy schema: remove only the new optional columns, then reopen through migration.
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var s=c.createStatement()) {
            s.execute("ALTER TABLE puzzles DROP COLUMN board_rows");s.execute("ALTER TABLE puzzles DROP COLUMN board_cols");
            s.execute("PRAGMA user_version=1");
        }
        Puzzle rectangle=puzzle(tinyRectangle());
        try(var repo=new PuzzleRepository(file)) {
            assertEquals(old.fingerprint(),repo.find(1).orElseThrow().puzzle().fingerprint());
            assertFalse(repo.save(old).inserted());assertTrue(repo.save(rectangle).inserted());
            assertFalse(repo.save(rectangle).inserted());
        }
        try(var repo=new PuzzleRepository(file)) {
            assertEquals(2,repo.all().size());
            assertEquals(rectangle.fingerprint(),repo.all().get(1).puzzle().fingerprint());
            assertEquals(4,repo.all().get(1).puzzle().board().cols());
        }
    }

    @Test void mixedOrientationPdfContainsSeparateReadableSolutions() throws Exception {
        Path file=temp.resolve("rectangles.pdf");
        new PdfBooklet().write(file,List.of(puzzle(tinyRectangle()),puzzle(transpose(tinyRectangle()))));
        try(var pdf=Loader.loadPDF(file.toFile())) {
            assertEquals(4,pdf.getNumberOfPages());
            for(int i=0;i<4;i++) {
                var box=pdf.getPage(i).getMediaBox();
                assertEquals(i%2==0,box.getWidth()>box.getHeight());
                assertNotNull(new PDFRenderer(pdf).renderImageWithDPI(i,40));
            }
        }
    }

    @Test @EnabledIfSystemProperty(named="kakuro.dimensions.db",matches=".+")
    void independentlyAuditsAllTenAcceptanceDimensions() throws Exception {
        Set<String> expected=new HashSet<>(List.of("11x11","12x12","13x13","14x14","15x15","8x12","10x15","10x20","15x10","20x10"));
        List<Board> boards=new ArrayList<>();
        try(var repo=new PuzzleRepository(Path.of(System.getProperty("kakuro.dimensions.db")))) {
            assertEquals(10,repo.all().size());
            for(var saved:repo.all()) {
                var p=saved.puzzle(); var b=p.board();
                assertTrue(expected.remove(b.rows()+"x"+b.cols()));
                assertTrue(new GeometryQualityEvaluator().evaluate(b.rows(),b.cols(),b.white()).accepted());
                assertTrue(GeometryDiversity.accepts(b,boards));boards.add(b);
                var oracle=new IndependentSolver(b);var solutions=oracle.solve();
                assertEquals(1,solutions.size());assertArrayEquals(p.solution(),solutions.getFirst());
                b.validateSolution(solutions.getFirst());
                var proof=new Solver().solve(b,Generator.SOLVER_NODE_LIMIT);
                assertEquals(Solver.Status.UNIQUE,proof.status());
                assertEquals(p.score(),Difficulty.score(b,proof.nodes()),1e-9);
                assertTrue(p.difficulty().accepts(p.score()));
                System.out.println("Independent rectangular audit "+b.rows()+"x"+b.cols()+": UNIQUE ("+oracle.nodes+" nodes)");
            }
        }
        assertTrue(expected.isEmpty());
    }

    @Test @EnabledIfSystemProperty(named="kakuro.additional.dbs",matches=".+")
    void independentlyAuditsSupplementalExamples() throws Exception {
        for(String file:System.getProperty("kakuro.additional.dbs").split(",")) {
            try(var repo=new PuzzleRepository(Path.of(file))) {
                assertFalse(repo.all().isEmpty());
                for(var saved:repo.all()) {
                    var p=saved.puzzle(); var oracle=new IndependentSolver(p.board());var answers=oracle.solve();
                    assertEquals(1,answers.size());assertArrayEquals(p.solution(),answers.getFirst());
                    p.board().validateSolution(answers.getFirst());
                    assertTrue(p.difficulty().accepts(p.score()));
                    System.out.println("Supplemental independent audit "+file+" ID "+saved.id()+": UNIQUE ("+oracle.nodes+" nodes)");
                }
            }
        }
    }
}
