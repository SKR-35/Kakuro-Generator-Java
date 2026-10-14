package io.github.skr35.kakuro.generator;

import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Run;
import io.github.skr35.kakuro.model.Puzzle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Solver-independent statistics of a validated solution. No uniform-frequency requirement. */
public final class SolutionQualityEvaluator {
    public static final double MINIMUM_BATCH_DISTANCE = 0.08;
    public record Metrics(int whites, List<Integer> frequencies, double entropy, double maximumDigitShare,
            double lowDigitShare, double highDigitShare, List<Double> regionalEntropies,
            double regionalDivergence, int pairs, int triples, double repeatedPairShare,
            double repeatedTripleShare, double dominantTripleShare, double monotonicShare,
            double consecutiveShare, int distinctClues, double clueEntropy) { }
    public record Evaluation(Metrics metrics, List<String> violations) {
        public Evaluation { violations = List.copyOf(violations); }
        public boolean accepted() { return violations.isEmpty(); }
        /** Continuous distance outside the same acceptance bounds, used only to guide proposals. */
        public double loss() {
            Metrics m=metrics;Thresholds t=thresholds(m.whites(),m.triples(),2*m.whites()-m.pairs());
            return below(m.entropy(),t.minimumEntropy()) + above(m.maximumDigitShare(),t.maximumDigitShare())
                    + above(Math.max(m.lowDigitShare(),m.highDigitShare()),t.maximumThirdShare())
                    + above(m.regionalDivergence(),t.maximumRegionalDivergence())
                    + above(m.monotonicShare(),t.maximumMonotonicShare())
                    + above(m.consecutiveShare(),t.maximumConsecutiveShare())
                    + above(m.dominantTripleShare(),t.maximumDominantTripleShare())
                    + below(m.clueEntropy(),t.minimumClueEntropy());
        }
        private static double above(double v,double limit){return Math.max(0,v/limit-1);}
        private static double below(double v,double limit){return Math.max(0,1-v/limit);}
    }
    public record Thresholds(double minimumEntropy, double maximumDigitShare, double maximumThirdShare,
            double maximumRegionalDivergence, double maximumMonotonicShare, double maximumConsecutiveShare,
            double maximumDominantTripleShare, double minimumClueEntropy) { }

    public static Thresholds thresholds(int whites, int triples, int runs) {
        // Sampling slack shrinks with white-cell and triple-window counts, not bounding-box area.
        return new Thresholds(0.94 - 0.30 / Math.sqrt(whites), 0.20 + 0.40 / Math.sqrt(whites),
                0.46 + 0.50 / Math.sqrt(whites), 0.12 + 0.50 / Math.sqrt(whites),
                0.50 + 0.50 / Math.sqrt(Math.max(1, triples)), 0.12 + 0.50 / Math.sqrt(Math.max(1, triples)),
                0.06 + 2.0 / Math.max(1, triples), 0.70 - 0.50 / Math.sqrt(runs));
    }

    public Evaluation evaluate(Board board, int[] values) {
        board.validateSolution(values);
        int[] counts = new int[9], clueCounts = new int[46];
        int[][] regions = new int[4][9];
        for (int cell=0; cell<values.length; cell++) if (values[cell] != 0) {
            counts[values[cell]-1]++;
            regions[region(board,cell)][values[cell]-1]++;
        }
        int[][] windows = windows(board,values);
        int pairCount = total(windows[0]), tripleCount = total(windows[1]), monotonic=0, consecutive=0;
        for (Run run:board.runs()) {
            clueCounts[run.sum()]++;
            for(int i=2;i<run.cells().size();i++) {
                int a=values[run.cells().get(i-2)], b=values[run.cells().get(i-1)], c=values[run.cells().get(i)];
                if ((b-a)*(c-b)>0) monotonic++;
                if (b-a==c-b && Math.abs(b-a)==1) consecutive++;
            }
        }
        int whites=board.whiteCount();
        List<Double> regionalEntropies=new ArrayList<>(); double divergence=0;
        for(int[] region:regions) {
            regionalEntropies.add(entropy(region,9));
            divergence += total(region)/(double)whites * js(region,counts);
        }
        Metrics m=new Metrics(whites,Arrays.stream(counts).boxed().toList(),entropy(counts,9),
                Arrays.stream(counts).max().orElse(0)/(double)whites,
                (counts[0]+counts[1]+counts[2])/(double)whites,(counts[6]+counts[7]+counts[8])/(double)whites,
                List.copyOf(regionalEntropies),divergence,pairCount,tripleCount,repeats(windows[0]),repeats(windows[1]),
                fraction(Arrays.stream(windows[1]).max().orElse(0),tripleCount),fraction(monotonic,tripleCount),
                fraction(consecutive,tripleCount),(int)Arrays.stream(clueCounts).filter(n->n>0).count(),
                entropy(clueCounts,Math.min(43,board.runs().size())));
        Thresholds t=thresholds(whites,tripleCount,board.runs().size());
        List<String> failures=new ArrayList<>();
        if(m.entropy()<t.minimumEntropy())failures.add("digit entropy");
        if(m.maximumDigitShare()>t.maximumDigitShare())failures.add("dominant digit");
        if(Math.max(m.lowDigitShare(),m.highDigitShare())>t.maximumThirdShare())failures.add("extreme-digit concentration");
        if(m.regionalDivergence()>t.maximumRegionalDivergence())failures.add("regional clustering");
        if(m.monotonicShare()>t.maximumMonotonicShare())failures.add("monotonic triples");
        if(m.consecutiveShare()>t.maximumConsecutiveShare())failures.add("consecutive triples");
        if(m.dominantTripleShare()>t.maximumDominantTripleShare())failures.add("repeated triple");
        if(m.clueEntropy()<t.minimumClueEntropy())failures.add("clue diversity");
        return new Evaluation(m,failures);
    }

    /** Symmetric numerical-pattern distance across layouts/sizes; 0 identical, 1 disjoint. */
    public double distance(Board a,int[] av,Board b,int[] bv) {
        a.validateSolution(av); b.validateSolution(bv);
        int[][] x=windows(a,av),y=windows(b,bv);
        return .25*js(x[0],y[0]) + .75*js(x[1],y[1]);
    }

    public boolean acceptsBatch(Board board,int[] solution,List<Puzzle> previous) {
        return previous.stream().allMatch(p -> distance(board,solution,p.board(),p.solution()) + 1e-12 >= MINIMUM_BATCH_DISTANCE);
    }

    static int region(Board board,int cell) {
        return (cell/board.cols()>board.rows()/2?2:0)+(cell%board.cols()>board.cols()/2?1:0);
    }
    private static int[][] windows(Board b,int[] v) {
        int[][] counts={new int[81],new int[729]};
        for(Run run:b.runs())for(int i=1;i<run.cells().size();i++) {
            int a=v[run.cells().get(i-1)]-1,c=v[run.cells().get(i)]-1;
            counts[0][a*9+c]++;
            if(i>1)counts[1][(v[run.cells().get(i-2)]-1)*81+a*9+c]++;
        }
        return counts;
    }
    private static int total(int[] a){return Arrays.stream(a).sum();}
    private static double fraction(int a,int b){return b==0?0:a/(double)b;}
    private static double repeats(int[] a){return fraction(Arrays.stream(a).map(n->Math.max(0,n-1)).sum(),total(a));}
    private static double entropy(int[] a,int categories) {
        double sum=total(a),h=0;if(sum==0 || categories<=1)return 0;
        for(int n:a)if(n>0){double p=n/sum;h-=p*StrictMath.log(p);}
        return h/StrictMath.log(categories);
    }
    private static double js(int[] a,int[] b) {
        double na=total(a),nb=total(b),distance=0;
        if(na==0 || nb==0)return na==nb?0:1;
        for(int i=0;i<a.length;i++) {
            double p=a[i]/na,q=b[i]/nb,m=(p+q)/2;
            if(p>0)distance+=.5*p*StrictMath.log(p/m)/StrictMath.log(2);
            if(q>0)distance+=.5*q*StrictMath.log(q/m)/StrictMath.log(2);
        }
        return Math.max(0,distance);
    }
}
