import io.github.skr35.kakuro.generator.SolutionQualityEvaluator;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import java.nio.file.*;
import java.io.PrintWriter;
import java.util.Locale;

/** Read stored solutions, write per-puzzle metrics and pairwise numerical-pattern distances. */
public class SolutionQualityReport {
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[1]);Files.createDirectories(dir);
        try(var repo=new PuzzleRepository(Path.of(args[0]));var csv=new PrintWriter(Files.newBufferedWriter(dir.resolve("solution-quality.csv")))) {
            var records=repo.all();var evaluator=new SolutionQualityEvaluator();
            csv.println("id,rows,cols,difficulty,whites,d1,d2,d3,d4,d5,d6,d7,d8,d9,entropy,max_digit,low_share,high_share,regional_js,q1_entropy,q2_entropy,q3_entropy,q4_entropy,pairs,triples,repeated_pairs,repeated_triples,dominant_triple,monotonic,consecutive,distinct_clues,clue_entropy,accepted,violations");
            for(var saved:records) {
                var p=saved.puzzle();var e=evaluator.evaluate(p.board(),p.solution());var m=e.metrics();
                csv.printf(Locale.ROOT,"%d,%d,%d,%s,%d",saved.id(),p.board().rows(),p.board().cols(),p.difficulty(),m.whites());
                for(int n:m.frequencies())csv.print(","+n);
                csv.printf(Locale.ROOT,",%.6f,%.6f,%.6f,%.6f,%.6f",m.entropy(),m.maximumDigitShare(),m.lowDigitShare(),m.highDigitShare(),m.regionalDivergence());
                for(double n:m.regionalEntropies())csv.printf(Locale.ROOT,",%.6f",n);
                csv.printf(Locale.ROOT,",%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%.6f,%s,%s%n",m.pairs(),m.triples(),m.repeatedPairShare(),m.repeatedTripleShare(),m.dominantTripleShare(),m.monotonicShare(),m.consecutiveShare(),m.distinctClues(),m.clueEntropy(),e.accepted(),String.join(";",e.violations()));
            }
            try(var pairs=new PrintWriter(Files.newBufferedWriter(dir.resolve("solution-distances.csv")))) {
                pairs.println("first,second,pattern_distance");
                for(int i=0;i<records.size();i++)for(int j=i+1;j<records.size();j++) {
                    var a=records.get(i).puzzle();var b=records.get(j).puzzle();
                    pairs.printf(Locale.ROOT,"%d,%d,%.6f%n",records.get(i).id(),records.get(j).id(),evaluator.distance(a.board(),a.solution(),b.board(),b.solution()));
                }
            }
            System.out.println("Measured "+records.size()+" solutions in "+dir);
        }
    }
}
