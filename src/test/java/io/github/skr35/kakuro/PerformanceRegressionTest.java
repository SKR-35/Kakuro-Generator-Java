package io.github.skr35.kakuro;

import io.github.skr35.kakuro.batch.BatchGenerator;
import io.github.skr35.kakuro.generator.*;
import io.github.skr35.kakuro.geometry.*;
import io.github.skr35.kakuro.model.*;
import io.github.skr35.kakuro.solver.Solver;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceRegressionTest {
    @Test void precomputedCombinationCountsMatchIndependentEnumeration() {
        for(int length=-1;length<=10;length++)for(int sum=-1;sum<=46;sum++) {
            int count=0;
            for(int mask=0;mask<512;mask++) {
                int total=0;for(int digit=1;digit<=9;digit++)if((mask&(1<<(digit-1)))!=0)total+=digit;
                if(Integer.bitCount(mask)==length && total==sum)count++;
            }
            assertEquals(count,Solver.combinationCount(length,sum));
        }
    }

    @Test void linearDiagonalMetricsMatchOriginalExhaustiveDefinition() {
        Random random=new Random(701);
        for(int[] dimensions:List.of(new int[]{8,8},new int[]{10,15},new int[]{15,50}))for(int sample=0;sample<40;sample++) {
            int rows=dimensions[0],cols=dimensions[1],whites=0,longest=0,maximum=0;
            boolean[] mask=new boolean[rows*cols];
            for(int r=1;r<rows;r++)for(int c=1;c<cols;c++)if(random.nextBoolean()){mask[r*cols+c]=true;whites++;}
            for(int slope:new int[]{-1,1}) {
                for(int offset=-2*Math.max(rows-1,cols-1);offset<=2*Math.max(rows-1,cols-1);offset++) {
                    int count=0;
                    for(int r=1;r<rows;r++)for(int c=1;c<cols;c++)if(mask[r*cols+c] && Math.abs(c-slope*r-offset)<=1)count++;
                    maximum=Math.max(maximum,count);
                }
                for(int r=1;r<rows;r++)for(int c=1;c<cols;c++) {
                    int n=0;for(int rr=r,cc=c;rr<rows && cc>0 && cc<cols && mask[rr*cols+cc];rr++,cc+=slope)n++;
                    longest=Math.max(longest,n);
                }
            }
            var actual=new GeometryQualityEvaluator().evaluate(rows,cols,mask).metrics();
            var evaluator=new GeometryQualityEvaluator();
            assertEquals(evaluator.evaluate(rows,cols,mask).accepted(),evaluator.accepts(rows,cols,mask));
            assertEquals(longest,actual.longestWhiteDiagonal());
            assertEquals(whites==0?1:maximum/(double)whites,actual.diagonalBandShare());
        }
    }

    @Test void geometryFastRejectionPreservesAcceptedMasks() {
        for(int seed=0;seed<16;seed++) {
            int rows=seed%2==0?8:10,cols=seed%2==0?8:15;
            boolean[] mask=Generator.geometry(rows,cols,new Random(seed),Difficulty.values()[seed%4]);
            var evaluator=new GeometryQualityEvaluator();
            assertTrue(evaluator.evaluate(rows,cols,mask).accepted());
            assertTrue(evaluator.accepts(rows,cols,mask));
        }
    }

    @Test void speculativeContinuationsReplayAgainstChangedHistoryExactly() throws Exception {
        long first=1560050721204170096L,second=-7183284058935943373L;
        var initialSession=new Generator.Session(Difficulty.MEDIUM,8,first,120);
        Puzzle initial=initialSession.next(b->true).puzzle();
        var diagnostics=initialSession.acceptanceDiagnostics();
        assertEquals(initialSession.statistics().inBand(),diagnostics.inBandQualityRejected()+diagnostics.eligible());
        assertEquals(1,diagnostics.eligible());
        assertEquals(0,diagnostics.layoutRejected()+diagnostics.numericalRejected());
        // Slot 1 is accepted while slots 2/3 may already be continuing against the old prefix.
        var tasks=List.of(new BatchGenerator.Task(1,Difficulty.MEDIUM,8,second,120),
                new BatchGenerator.Task(2,Difficulty.MEDIUM,8,first,120),
                new BatchGenerator.Task(3,Difficulty.MEDIUM,8,second,120));
        var expected=reference(tasks,List.of(initial));
        assertTrue(expected.getFirst().startsWith("ACCEPTED"));
        for(int workers:new int[]{1,3}) {
            List<String> actual=new ArrayList<>();
            new BatchGenerator().run(tasks,workers,List.of(initial),r->actual.add(summary(r.generated(),r.statistics())));
            assertEquals(expected,actual);
        }
    }

    private static String summary(Generator.Generated generated,Generator.Statistics statistics) {
        return (generated==null?"EXHAUSTED":"ACCEPTED "+generated.puzzle().fingerprint())+" "+statistics;
    }

    /** Previous ordered coordinator policy, independent of the new worker scheduling. */
    private static List<String> reference(List<BatchGenerator.Task> tasks,List<Puzzle> initial) {
        var previous=new ArrayList<>(initial); var result=new ArrayList<String>(); var quality=new SolutionQualityEvaluator();
        for(var task:tasks) {
            var session=new Generator.Session(task.difficulty(),task.rows(),task.cols(),task.seed(),task.attempts());
            Generator.Generated generated=null;
            try {
                var candidate=session.next(b->true); var boards=previous.stream().map(Puzzle::board).toList();
                if(!GeometryDiversity.accepts(candidate.puzzle().board(),boards)
                        || !quality.acceptsBatch(candidate.puzzle().board(),candidate.puzzle().solution(),previous)) {
                    session.recordBatchRejection();
                    candidate=session.next(b->GeometryDiversity.accepts(b,boards),(b,v)->quality.acceptsBatch(b,v,previous));
                }
                generated=candidate;previous.add(generated.puzzle());
            } catch(IllegalStateException exhausted) { }
            result.add(summary(generated,session.statistics()));
        }
        return result;
    }
}
