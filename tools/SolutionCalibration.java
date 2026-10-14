import io.github.skr35.kakuro.generator.SolutionQualityEvaluator;
import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import java.nio.file.*;
import java.util.*;
import java.io.PrintWriter;

/** Legal randomized Latin assignments on fixed masks: a sampling reference, NOT unique puzzles. */
public class SolutionCalibration {
    public static void main(String[] args) throws Exception {
        Random random=new Random(20261016);
        var evaluator=new SolutionQualityEvaluator();
        try(var repo=new PuzzleRepository(Path.of(args[0]));var csv=new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            csv.println("board,sample,whites,entropy,max_digit,extreme_share,regional_js,monotonic,consecutive,dominant_triple,clue_entropy,accepted");
            for(var saved:repo.all())for(int sample=0;sample<100;sample++) {
                var b=saved.puzzle().board();int[] row=permutation(random),col=permutation(random),digits=permutation(random),v=new int[b.cellCount()];
                for(int r=0;r<b.rows();r++)for(int c=0;c<b.cols();c++)if(b.isWhite(r*b.cols()+c))
                    v[r*b.cols()+c]=digits[(row[r%9]+col[c%9])%9]+1;
                Board board=Board.fromSolution(b.rows(),b.cols(),b.white(),v);
                var e=evaluator.evaluate(board,v);var m=e.metrics();
                csv.printf(Locale.ROOT,"%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s%n",saved.id(),sample,m.whites(),m.entropy(),m.maximumDigitShare(),Math.max(m.lowDigitShare(),m.highDigitShare()),m.regionalDivergence(),m.monotonicShare(),m.consecutiveShare(),m.dominantTripleShare(),m.clueEntropy(),e.accepted());
            }
        }
        System.out.println("Calibration reference written; assignments are legal but have NOT been proven unique.");
    }
    private static int[] permutation(Random random) {
        List<Integer> values=new ArrayList<>(List.of(0,1,2,3,4,5,6,7,8));Collections.shuffle(values,random);
        return values.stream().mapToInt(Integer::intValue).toArray();
    }
}
