import io.github.skr35.kakuro.generator.*;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import java.nio.file.*;
import java.io.PrintWriter;
import java.util.*;

/** Predeclared label/size/seed matrix. No selection on the new analysis, no retries beyond budget. */
class DifficultyCohort {
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Usage: fresh-output-directory candidate-budget master-seed");
        Path dir=Path.of(args[0]);Files.createDirectories(dir);
        if(Files.exists(dir.resolve("puzzles.db")))throw new IllegalArgumentException("Use a fresh output directory");
        int budget=Integer.parseInt(args[1]);Random seeds=new Random(Long.parseLong(args[2]));
        try(var db=new PuzzleRepository(dir.resolve("puzzles.db"));
            var csv=new PrintWriter(Files.newBufferedWriter(dir.resolve("attempts.csv")))) {
            csv.println("label,size,replicate,seed,budget,status,candidates,seconds,fingerprint");
            for(var difficulty:Difficulty.values())for(int size:new int[]{8,10,12})for(int replicate=1;replicate<=2;replicate++) {
                long seed=seeds.nextLong(),start=System.nanoTime();
                var session=new Generator.Session(difficulty,size,size,seed,budget);
                String status="ACCEPTED",hash="";
                System.out.println("Starting "+difficulty+" "+size+" seed="+seed);
                try {
                    var generated=session.next(board->true);var puzzle=generated.puzzle();
                    var saved=db.save(puzzle);status=saved.inserted()?"ACCEPTED":"DUPLICATE";hash=puzzle.fingerprint();
                } catch(IllegalStateException e) { status="BUDGET_EXHAUSTED"; }
                double seconds=(System.nanoTime()-start)/1e9;
                csv.printf(Locale.ROOT,"%s,%d,%d,%d,%d,%s,%d,%.3f,%s%n",difficulty,size,replicate,seed,budget,
                        status,session.statistics().candidates(),seconds,hash);csv.flush();
                System.out.printf(Locale.ROOT,"%s %dx%d %s %.3fs%n",difficulty,size,size,status,seconds);
            }
        }
    }
}
