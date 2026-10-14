import io.github.skr35.kakuro.batch.BatchGenerator;
import io.github.skr35.kakuro.generator.*;
import io.github.skr35.kakuro.persistence.BankStore;
import java.nio.file.*;
import java.util.Locale;

/** Twenty artificial exhausted checkpoints in a NEW disposable database; no generation. */
public class BankCheckpointProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || Files.exists(Path.of(args[0])))throw new IllegalArgumentException("Provide a new disposable DB path");
        try(var bank=new BankStore(Path.of(args[0]))) {
            bank.configure(15051963L,5000);var bucket=new BankStore.Bucket(8,8,Difficulty.EASY);
            var stats=new Generator.Session(Difficulty.EASY,8,1,1).statistics();
            long start=System.nanoTime();
            for(int i=0;i<20;i++) {
                var task=new BatchGenerator.Task(i,Difficulty.EASY,8,BankStore.seed(15051963,bucket,i),5000);
                bank.complete(bucket,i,new BatchGenerator.Outcome(task,null,0,"Synthetic checkpoint latency measurement",stats));
            }
            double seconds=(System.nanoTime()-start)/1e9;
            System.out.printf(Locale.ROOT,"20 atomic exhausted checkpoints: %.6fs total, %.3fms/commit%n",seconds,seconds*1000/20);
        }
    }
}
