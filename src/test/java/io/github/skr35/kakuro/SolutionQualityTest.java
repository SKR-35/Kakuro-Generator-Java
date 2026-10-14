package io.github.skr35.kakuro;

import io.github.skr35.kakuro.generator.*;
import io.github.skr35.kakuro.geometry.GeometryQualityEvaluator;
import io.github.skr35.kakuro.model.*;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import io.github.skr35.kakuro.solver.Solver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SolutionQualityTest {
    private final SolutionQualityEvaluator evaluator=new SolutionQualityEvaluator();

    @Test void exactFrequencyAndEntropyForKnownSolution() {
        var puzzle=Fixtures.unique();var m=evaluator.evaluate(puzzle.board(),puzzle.solution()).metrics();
        assertEquals(List.of(1,1,1,1,0,0,0,0,0),m.frequencies());
        assertEquals(StrictMath.log(4)/StrictMath.log(9),m.entropy(),1e-12);
        assertEquals(.25,m.maximumDigitShare());assertEquals(.75,m.lowDigitShare());
        assertEquals(0,m.highDigitShare());assertEquals(0,m.triples());
        assertEquals(0,m.monotonicShare());assertEquals(0,m.consecutiveShare());
        assertEquals(4,m.distinctClues());assertEquals(1,m.clueEntropy(),1e-12);
        assertThrows(UnsupportedOperationException.class,()->m.frequencies().set(0,100));
    }

    @Test void uniformCountsDoNotExcuseRepetitiveLatinPatterns() {
        boolean[] mask=new boolean[100];int[] values=new int[100];
        for(int r=1;r<10;r++)for(int c=1;c<10;c++){mask[r*10+c]=true;values[r*10+c]=(r+c-2)%9+1;}
        Board board=Board.fromSolution(10,mask,values);
        var e=evaluator.evaluate(board,values);
        assertEquals(1,e.metrics().entropy(),1e-12);
        assertTrue(e.metrics().consecutiveShare()>.70);
        assertTrue(e.metrics().repeatedTripleShare()>.80);
        assertEquals(1,e.metrics().distinctClues());
        assertFalse(e.accepted());
        assertTrue(e.violations().contains("consecutive triples"));
        assertTrue(e.violations().contains("clue diversity"));
    }

    @Test void complementDoesNotHideFrequencyOrSequenceDefects() {
        var p=Fixtures.unique();int[] complement=p.solution();
        for(int i=0;i<complement.length;i++)if(complement[i]>0)complement[i]=10-complement[i];
        Board board=Board.fromSolution(p.board().rows(),p.board().cols(),p.board().white(),complement);
        var a=evaluator.evaluate(p.board(),p.solution()).metrics();var b=evaluator.evaluate(board,complement).metrics();
        assertEquals(a.entropy(),b.entropy(),1e-12);
        assertEquals(a.lowDigitShare(),b.highDigitShare());assertEquals(a.maximumDigitShare(),b.maximumDigitShare());
        assertEquals(a.regionalDivergence(),b.regionalDivergence(),1e-12);
        assertEquals(a.monotonicShare(),b.monotonicShare());
    }

    @Test void patternDistanceIsSymmetricAndRejectsIdenticalSolutions() {
        var p=Fixtures.unique();int[] changed=p.solution();
        for(int i=0;i<changed.length;i++)if(changed[i]>0)changed[i]=10-changed[i];
        Board b=Board.fromSolution(3,p.board().white(),changed);
        assertEquals(0,evaluator.distance(p.board(),p.solution(),p.board(),p.solution()),1e-12);
        double ab=evaluator.distance(p.board(),p.solution(),b,changed);
        assertTrue(ab>.08 && ab<=1);assertEquals(ab,evaluator.distance(b,changed,p.board(),p.solution()),1e-12);
        assertFalse(evaluator.acceptsBatch(p.board(),p.solution(),List.of(p)));
    }

    @Test void thresholdsAccountForSampleSizeAndInvalidSolutionsAreRejected() {
        var small=SolutionQualityEvaluator.thresholds(30,10,20);var large=SolutionQualityEvaluator.thresholds(130,80,90);
        assertTrue(small.minimumEntropy()<large.minimumEntropy());
        assertTrue(small.maximumDigitShare()>large.maximumDigitShare());
        assertTrue(small.maximumConsecutiveShare()>large.maximumConsecutiveShare());
        var p=Fixtures.unique();int[] invalid=p.solution();invalid[4]=9;
        assertThrows(IllegalArgumentException.class,()->evaluator.evaluate(p.board(),invalid));
    }

    @Test @EnabledIfSystemProperty(named="kakuro.solution.dbs",matches=".+")
    void independentlyAuditsNewBookletsAndAllQualityGates() throws Exception {
        for(String file:System.getProperty("kakuro.solution.dbs").split(",")) {
            List<Puzzle> accepted=new ArrayList<>();Set<String> dimensions=new HashSet<>();
            try(var repo=new PuzzleRepository(Path.of(file))) {
                assertTrue(repo.all().size()>=5);
                for(var saved:repo.all()) {
                    var p=saved.puzzle();var board=p.board();
                    dimensions.add(board.rows()+"x"+board.cols());
                    var e=evaluator.evaluate(board,p.solution());assertTrue(e.accepted(),"ID "+saved.id()+e.violations());
                    assertEquals(0,e.loss(),1e-12);
                    assertTrue(evaluator.acceptsBatch(board,p.solution(),accepted));accepted.add(p);
                    assertTrue(new GeometryQualityEvaluator().evaluate(board.rows(),board.cols(),board.white()).accepted());
                    var oracle=new IndependentSolver(board);var answers=oracle.solve();
                    assertEquals(1,answers.size());assertArrayEquals(p.solution(),answers.getFirst());board.validateSolution(answers.getFirst());
                    var proof=new Solver().solve(board,Generator.SOLVER_NODE_LIMIT);
                    assertEquals(Solver.Status.UNIQUE,proof.status());assertEquals(p.score(),Difficulty.score(board,proof.nodes()),1e-9);
                    assertTrue(p.difficulty().accepts(p.score()));
                    System.out.println("Independent solution quality audit "+file+" ID "+saved.id()+": UNIQUE; entropy="+e.metrics().entropy());
                }
            }
            assertTrue(dimensions.containsAll(Set.of("10x10","12x12","15x15","10x20","20x10")));
        }
    }
}
