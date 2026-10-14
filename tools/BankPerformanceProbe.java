import io.github.skr35.kakuro.batch.BatchGenerator;
import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.persistence.BankStore;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import java.nio.file.*;
import java.util.*;

/** Bounded read-on-snapshot replay. Never point this at a live production database. */
public class BankPerformanceProbe {
    public static void main(String[] args) throws Exception {
        // snapshot, seed, first slot, slot count, candidate budget, workers, CSV
        if(args.length!=7) throw new IllegalArgumentException("snapshot seed first-slot count attempts workers csv");
        int count=Integer.parseInt(args[3]),budget=Integer.parseInt(args[4]),workers=Integer.parseInt(args[5]);
        if(count<1 || count>8 || budget<1 || budget>5000) throw new IllegalArgumentException("Probe limited to 8 slots x 5000 candidates");
        var bucket=new BankStore.Bucket(8,8,Difficulty.EASY);
        var tasks=new ArrayList<BatchGenerator.Task>();
        for(int i=0;i<count;i++) tasks.add(new BatchGenerator.Task(i,Difficulty.EASY,8,8,
                BankStore.seed(Long.parseLong(args[1]),bucket,Long.parseLong(args[2])+i),budget));
        try(var repository=new PuzzleRepository(Path.of(args[0]));var csv=Files.newBufferedWriter(Path.of(args[6]))) {
            var previous=repository.bucket(8,8,Difficulty.EASY);
            csv.write("slot,seed,result,fingerprint,candidates,geometryMasks,unique,inBand,qualityRejected,diversityRejected,slotSeconds,wallSeconds\n");
            long start=System.nanoTime();
            new BatchGenerator().run(tasks,workers,previous,result -> {
                var s=result.statistics();
                String row=String.format(Locale.ROOT,"%d,%d,%s,%s,%d,%d,%d,%d,%d,%d,%.6f,%.6f%n",
                    Long.parseLong(args[2])+result.task().slot(),result.task().seed(),result.failure()==null?"ACCEPTED":"EXHAUSTED",
                    result.generated()==null?"":result.generated().puzzle().fingerprint(),s.candidates(),s.geometryMasks(),s.unique(),s.inBand(),s.solutionQualityRejected(),s.diversityRejected(),result.seconds(),(System.nanoTime()-start)/1e9);
                csv.write(row);csv.flush();System.out.print(row);
            });
            System.out.printf(Locale.ROOT,"Total wall seconds %.6f%n",(System.nanoTime()-start)/1e9);
        }
    }
}
