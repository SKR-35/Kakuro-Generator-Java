package io.github.skr35.kakuro;

import io.github.skr35.kakuro.analysis.LogicalAnalyzer;
import io.github.skr35.kakuro.model.Board;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;
import io.github.skr35.kakuro.cli.KakuroCommand;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class LogicalAnalyzerTest {
    private final LogicalAnalyzer analyzer=new LogicalAnalyzer();
    @TempDir Path temp;

    @Test void knownSolutionIsDerivedWithoutStoredDigits() {
        var board=Fixtures.tiny(3,7,4,6);
        var result=analyzer.analyze(board);
        assertEquals(LogicalAnalyzer.Status.SOLVED,result.status());
        assertArrayEquals(new int[]{0,0,0,0,1,2,0,3,4},result.solvedValues());
        assertEquals(4,result.forcedMoves());assertEquals(32,result.eliminations());
        assertTrue(result.maxDepth()>0);assertFalse(result.beyondSupportedLogic());
        assertEquals(0,result.minimumBranchWidth());
        assertEquals(result,analyzer.analyze(board));
        assertThrows(UnsupportedOperationException.class,()->result.candidates().set(4,511));
    }

    @Test void ambiguityStallsAndContradictionIsDistinct() {
        var ambiguous=analyzer.analyze(Fixtures.tiny(3,3,3,3));
        assertEquals(LogicalAnalyzer.Status.STALLED,ambiguous.status());
        assertEquals(2,ambiguous.minimumBranchWidth());
        assertTrue(ambiguous.beyondSupportedLogic());
        assertThrows(IllegalStateException.class,ambiguous::solvedValues);
        assertEquals(LogicalAnalyzer.Status.CONTRADICTION,analyzer.analyze(Fixtures.tiny(3,3,3,4)).status());
    }

    @Test void exhaustionAndCancellationCannotMasqueradeAsLogicalStalling() {
        var limited=analyzer.analyze(Fixtures.unique().board(),1,LogicalAnalyzer.Technique.RUN_MATCHING);
        assertEquals(LogicalAnalyzer.Status.BUDGET_EXHAUSTED,limited.status());
        assertEquals(1,limited.work());assertFalse(limited.beyondSupportedLogic());
        assertEquals(0,limited.minimumBranchWidth());
        assertThrows(IllegalArgumentException.class,()->analyzer.analyze(Fixtures.unique().board(),0,LogicalAnalyzer.Technique.SUM_BOUNDS));
        Thread.currentThread().interrupt();
        try { assertThrows(CancellationException.class,()->analyzer.analyze(Fixtures.unique().board())); }
        finally { Thread.interrupted(); }
    }

    @Test void deductionTraceReplaysAndEveryRemovalPreservesIndependentSolutions() {
        Random random=new Random(20261018);
        for(int sample=0;sample<120;sample++) {
            Board board=Fixtures.tiny(3+random.nextInt(15),3+random.nextInt(15),3+random.nextInt(15),3+random.nextInt(15));
            var solutions=new IndependentSolver(board).solve();
            for(var ceiling:LogicalAnalyzer.Technique.values()) {
                var result=analyzer.analyze(board,100_000,ceiling);
                assertNotEquals(LogicalAnalyzer.Status.BUDGET_EXHAUSTED,result.status());
                int[] replay=new int[board.cellCount()];
                for(int c=0;c<replay.length;c++)if(board.isWhite(c))replay[c]=511;
                int eliminated=0;
                for(var step:result.trace()) {
                    assertTrue(step.technique().ordinal()<=ceiling.ordinal());
                    assertFalse(step.explanation().isBlank());
                    for(var change:step.changes()) {
                        assertEquals(replay[change.cell()],change.before());
                        assertNotEquals(0,change.after());
                        assertEquals(change.after(),change.after()&change.before());
                        for(int[] solution:solutions)assertNotEquals(0,change.after()&(1<<(solution[change.cell()]-1)));
                        eliminated+=Integer.bitCount(change.before())-Integer.bitCount(change.after());
                        replay[change.cell()]=change.after();
                    }
                }
                assertEquals(Arrays.stream(replay).boxed().toList(),result.candidates());
                assertEquals(eliminated,result.eliminations());
                if(!solutions.isEmpty())assertNotEquals(LogicalAnalyzer.Status.CONTRADICTION,result.status());
                if(result.status()==LogicalAnalyzer.Status.SOLVED) {
                    assertEquals(1,solutions.size());assertArrayEquals(solutions.getFirst(),result.solvedValues());
                }
            }
        }
    }

    @Test void rectangularSavedFixturesAreSoundAtEveryTechniqueCeiling() throws Exception {
        for(var puzzle:KdpPdfTest.fixtures())for(var ceiling:LogicalAnalyzer.Technique.values()) {
            var result=analyzer.analyze(puzzle.board(),LogicalAnalyzer.DEFAULT_WORK_LIMIT,ceiling);
            assertNotEquals(LogicalAnalyzer.Status.CONTRADICTION,result.status());
            assertNotEquals(LogicalAnalyzer.Status.BUDGET_EXHAUSTED,result.status());
            for(int c=0;c<puzzle.board().cellCount();c++)if(puzzle.board().isWhite(c))
                assertNotEquals(0,result.candidates().get(c)&(1<<(puzzle.solution()[c]-1)));
            if(result.status()==LogicalAnalyzer.Status.SOLVED)assertArrayEquals(puzzle.solution(),result.solvedValues());
        }
    }

    private List<Board> advancedFixtures() throws Exception {
        try(var reader=new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/logical-fixtures.csv")),StandardCharsets.UTF_8))) {
            return reader.lines().skip(1).map(line->{
                String[] fields=line.split(",");int[] values=fields[3].chars().map(c->c-'0').toArray();
                boolean[] white=new boolean[values.length];for(int c=0;c<values.length;c++)white[c]=values[c]!=0;
                return Board.fromSolution(Integer.parseInt(fields[1]),Integer.parseInt(fields[2]),white,values);
            }).toList();
        }
    }

    @Test void techniqueCeilingsSeparateBoundsCombinationsMatchingAndUnresolvedUnique() throws Exception {
        var boards=advancedFixtures();
        for(int fixture=0;fixture<boards.size();fixture++) {
            var independent=new IndependentSolver(boards.get(fixture)).solve();
            assertEquals(1,independent.size());
            for(var ceiling:LogicalAnalyzer.Technique.values()) {
                var result=analyzer.analyze(boards.get(fixture),5_000_000,ceiling);
                boolean solves=fixture<3 && ceiling.ordinal()>=fixture+1;
                assertEquals(solves?LogicalAnalyzer.Status.SOLVED:LogicalAnalyzer.Status.STALLED,result.status());
                if(solves)assertArrayEquals(independent.getFirst(),result.solvedValues());
                for(int c=0;c<boards.get(fixture).cellCount();c++)if(boards.get(fixture).isWhite(c))
                    assertNotEquals(0,result.candidates().get(c)&(1<<(independent.getFirst()[c]-1)));
            }
        }
    }

    @Test void interruptedMatchingCommitsOnlyCompleteSoundDeductions() throws Exception {
        Board board=advancedFixtures().get(2);
        var complete=analyzer.analyze(board);
        for(long budget:new long[]{22000,complete.work()-1}) {
            var partial=analyzer.analyze(board,budget,LogicalAnalyzer.Technique.RUN_MATCHING);
            assertEquals(LogicalAnalyzer.Status.BUDGET_EXHAUSTED,partial.status());
            assertEquals(budget,partial.work());assertFalse(partial.beyondSupportedLogic());
            for(int c=0;c<board.cellCount();c++)if(board.isWhite(c))
                assertNotEquals(0,partial.candidates().get(c)&complete.candidates().get(c));
        }
    }

    @Test void cliReportsLogicalProfileWithoutReclassifyingOrWritingPdf() throws Exception {
        Path db=temp.resolve("puzzles.db"),pdf=temp.resolve("unchanged.pdf");
        var original=Fixtures.unique();
        try(var repository=new PuzzleRepository(db)){repository.save(original);}
        Files.writeString(pdf,"preserve");
        var out=new StringWriter();var err=new StringWriter();
        var command=new CommandLine(new KakuroCommand()).setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
        assertEquals(0,command.execute("--db",db.toString(),"--analyze-db","--outfile",pdf.toString()));
        assertTrue(out.toString().contains("logical-v1 status=SOLVED"));
        assertTrue(out.toString().contains("legacy=EASY"));
        assertEquals("preserve",Files.readString(pdf));
        try(var repository=new PuzzleRepository(db)){
            var saved=repository.find(1).orElseThrow().puzzle();
            assertEquals(original.fingerprint(),saved.fingerprint());assertEquals(original.score(),saved.score());
            assertEquals(original.difficulty(),saved.difficulty());assertEquals(1,repository.all().size());
        }
        out=new StringWriter();
        assertEquals(1,new CommandLine(new KakuroCommand()).setOut(new PrintWriter(out)).execute(
                "--db",db.toString(),"--analyze-db","--analysis-budget","1"));
        assertTrue(out.toString().contains("BUDGET_EXHAUSTED"));
        for(String[] args:new String[][]{{"--analyze-db"},{"--analysis-budget","10"},
                {"--db",db.toString(),"--analyze-db","--verify-db"},
                {"--db",db.toString(),"--analyze-db","--easy","1"},
                {"--db",db.toString(),"--analyze-db","--export-ids","1"}})
            assertEquals(2,new CommandLine(new KakuroCommand()).setErr(new PrintWriter(err)).execute(args));
    }

    @Test void optionalCalibrationCorpusPreservesEveryIndependentlySolvedValue() throws Exception {
        String property=System.getProperty("kakuro.logical.dbs");
        Assumptions.assumeTrue(property!=null,"Set kakuro.logical.dbs to audit saved calibration records");
        for(String path:property.split(","))try(var repository=new PuzzleRepository(Path.of(path))) {
            for(var saved:repository.all()) {
                var puzzle=saved.puzzle();var result=analyzer.analyze(puzzle.board());
                var independent=new IndependentSolver(puzzle.board()).solve();
                assertEquals(1,independent.size());assertArrayEquals(puzzle.solution(),independent.getFirst());
                assertNotEquals(LogicalAnalyzer.Status.CONTRADICTION,result.status());
                assertNotEquals(LogicalAnalyzer.Status.BUDGET_EXHAUSTED,result.status());
                for(int c=0;c<puzzle.board().cellCount();c++)if(puzzle.board().isWhite(c))
                    assertNotEquals(0,result.candidates().get(c)&(1<<(independent.getFirst()[c]-1)));
                if(result.status()==LogicalAnalyzer.Status.SOLVED)assertArrayEquals(independent.getFirst(),result.solvedValues());
                System.out.println("Logical audit "+path+" ID "+saved.id()+": independent UNIQUE; "+result.status());
            }
        }
    }
}
