package io.github.skr35.kakuro.batch;

import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.generator.Generator;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import java.nio.file.Path;

/** Internal process entry point. Invoke through `probe` to enforce the wall-clock limit. */
public final class ProbeWorker {
    private ProbeWorker() { }
    public static void main(String[] args) throws Exception {
        int cols=Integer.parseInt(args[0]), attempts=Integer.parseInt(args[3]);
        var session=new Generator.Session(Difficulty.valueOf(args[1]),15,cols,Long.parseLong(args[2]),attempts,true);
        try {
            var generated=session.next(board -> true);
            try(var repository=new PuzzleRepository(Path.of(args[4]))) { repository.save(generated.puzzle()); }
            System.out.println("ACCEPTED UNIQUE " + generated.puzzle().fingerprint());
            System.out.println(session.statistics());
        } catch(IllegalStateException e) {
            System.out.println("ATTEMPT_BUDGET_EXHAUSTED: " + e.getMessage());
            System.exit(1);
        }
    }
}
