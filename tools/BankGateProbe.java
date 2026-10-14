import io.github.skr35.kakuro.generator.*;
import io.github.skr35.kakuro.geometry.GeometryDiversity;
import io.github.skr35.kakuro.persistence.*;
import java.nio.file.Path;

/** One bounded slot, inspecting final gates without changing the candidate sequence. Snapshot only. */
public class BankGateProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("snapshot master-seed slot");
        var bucket=new BankStore.Bucket(8,8,Difficulty.EASY);
        long seed=BankStore.seed(Long.parseLong(args[1]),bucket,Long.parseLong(args[2]));
        var session=new Generator.Session(Difficulty.EASY,8,seed,5000);
        try(var repository=new PuzzleRepository(Path.of(args[0]))) {
            var previous=repository.bucket(8,8,Difficulty.EASY);var boards=previous.stream().map(p->p.board()).toList();
            var quality=new SolutionQualityEvaluator();
            try {
                var provisional=session.next(b->true);
                boolean geometry=GeometryDiversity.accepts(provisional.puzzle().board(),boards);
                boolean numerical=quality.acceptsBatch(provisional.puzzle().board(),provisional.puzzle().solution(),previous);
                System.out.println("Provisional first result: layout="+geometry+" numerical="+numerical);
                if(!geometry || !numerical) {
                    session.recordBatchRejection();
                    session.next(b->GeometryDiversity.accepts(b,boards),(b,v)->quality.acceptsBatch(b,v,previous));
                }
                System.out.println("ACCEPTED");
            } catch(IllegalStateException e) { System.out.println("EXHAUSTED"); }
            System.out.println(session.statistics());System.out.println(session.acceptanceDiagnostics());
            System.out.println("Diagnostics eligible includes the first history-free provisional result, whose external rejection is printed separately.");
        }
    }
}
