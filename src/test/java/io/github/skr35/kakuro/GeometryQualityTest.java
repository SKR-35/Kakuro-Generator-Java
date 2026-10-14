package io.github.skr35.kakuro;

import io.github.skr35.kakuro.batch.BatchGenerator;
import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.generator.Generator;
import io.github.skr35.kakuro.geometry.GeometryDiversity;
import io.github.skr35.kakuro.geometry.GeometryQualityEvaluator;
import io.github.skr35.kakuro.model.Board;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class GeometryQualityTest {
    private final GeometryQualityEvaluator evaluator = new GeometryQualityEvaluator();
    private static Board geometryBoard(int size, boolean[] mask) {
        int[] sums = Board.geometry(size,mask).stream().mapToInt(r -> r.cells().size()*(r.cells().size()+1)/2).toArray();
        return new Board(size,mask,sums);
    }

    @Test void rejectsTheOldStaircaseEvenThoughItIsMathematicallyLegal() {
        int size=8; boolean[] mask=new boolean[size*size];
        for(int r=1;r<size;r++) for(int c=1;c<size;c++) mask[r*size+c]=Math.abs(r-c)<=1;
        assertDoesNotThrow(()->Board.geometry(size,mask));
        var result=evaluator.evaluate(size,mask);
        assertFalse(result.accepted());
        assertEquals(7,result.metrics().longestWhiteDiagonal());
        assertEquals(1,result.metrics().diagonalBandShare(),1e-12);
        assertTrue(result.violations().contains("diagonal-band concentration"));
    }

    @Test void rejectsEmptyUnbrokenAndDisconnectedMasks() {
        boolean[] full=new boolean[100];
        for(int r=1;r<10;r++)for(int c=1;c<10;c++)full[r*10+c]=true;
        assertFalse(evaluator.evaluate(10,full).accepted());
        assertFalse(evaluator.evaluate(10,new boolean[100]).accepted());
        boolean[] disconnected=new boolean[64];
        for(int i:new int[]{9,10,17,18,45,46,53,54})disconnected[i]=true;
        assertEquals(2,evaluator.evaluate(8,disconnected).metrics().components());
        assertFalse(evaluator.evaluate(8,disconnected).accepted());
    }

    @Test void qualityCoverageBalanceConnectivityAndReproducibilityAcrossSeeds() {
        for(int size=7;size<=10;size++)for(int seed=0;seed<12;seed++) {
            boolean[] mask=Generator.geometry(size,new Random(seed),Difficulty.values()[seed%4]);
            assertArrayEquals(mask,Generator.geometry(size,new Random(seed),Difficulty.values()[seed%4]));
            var result=evaluator.evaluate(size,mask);
            assertTrue(result.accepted(),result.violations().toString());
            var m=result.metrics();
            assertEquals(1,m.components());assertEquals(1,m.rowCoverage());assertEquals(1,m.columnCoverage());
            assertTrue(m.runBalance()>=.70);
            assertTrue(m.quadrantDensities().stream().allMatch(d->d>=.40));
            assertTrue(m.interiorClueRows()>=3 && m.interiorClueColumns()>=3);
            for(var run:Board.geometry(size,mask))assertTrue(run.cells().size()>=2 && run.cells().size()<=9);
        }
    }

    @Test void differentSeedsYieldDifferentLayoutsAndCosmeticTransformsDoNotCount() {
        var fingerprints=new HashSet<String>();
        List<Board> boards=new ArrayList<>();
        for(int seed=0;seed<24;seed++) {
            Board board=geometryBoard(9,Generator.geometry(9,new Random(seed),Difficulty.MEDIUM));
            fingerprints.add(GeometryDiversity.fingerprint(board));boards.add(board);
        }
        assertTrue(fingerprints.size()>=22);
        double total=0;int pairs=0;
        for(int i=0;i<boards.size();i++)for(int j=i+1;j<boards.size();j++){total+=GeometryDiversity.distance(boards.get(i),boards.get(j));pairs++;}
        assertTrue(total/pairs>.20,"Mean layout distance "+total/pairs);
        Board original=boards.getFirst();boolean[] flipped=new boolean[81];
        for(int r=1;r<9;r++)for(int c=1;c<9;c++)flipped[r*9+c]=original.isWhite(r*9+9-c);
        Board mirror=geometryBoard(9,flipped);
        assertEquals(0,GeometryDiversity.distance(original,mirror),1e-12);
        assertEquals(GeometryDiversity.fingerprint(original),GeometryDiversity.fingerprint(mirror));
        assertFalse(GeometryDiversity.accepts(mirror,List.of(original)));
    }

    @Test void batchRetriesIdenticalLayoutsWithinOriginalBudgetAndKeepsDeterminism() throws Exception {
        var task=new BatchGenerator.Task(1,Difficulty.MEDIUM,9,20261014,3000);
        var tasks=List.of(task,new BatchGenerator.Task(2,Difficulty.MEDIUM,9,20261014,3000));
        List<Board> serial=new ArrayList<>(),parallel=new ArrayList<>();
        new BatchGenerator().run(tasks,1,r->{assertNull(r.failure());serial.add(r.generated().puzzle().board());assertTrue(r.generated().attempts()<=3000);});
        new BatchGenerator().run(tasks,2,r->{assertNull(r.failure());parallel.add(r.generated().puzzle().board());});
        assertEquals(2,serial.size());
        assertTrue(GeometryDiversity.distance(serial.get(0),serial.get(1))>=GeometryDiversity.MINIMUM_DISTANCE);
        for(int i=0;i<2;i++)assertEquals(serial.get(i).canonical(),parallel.get(i).canonical());
    }
}
