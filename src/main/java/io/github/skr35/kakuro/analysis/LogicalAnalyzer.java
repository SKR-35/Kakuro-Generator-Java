package io.github.skr35.kakuro.analysis;

import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Run;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Versioned deterministic deduction analysis. Does not call the search solver or read a solution. */
public final class LogicalAnalyzer {
    public static final String VERSION = "logical-v1";
    public static final long DEFAULT_WORK_LIMIT = 5_000_000;
    public enum Status { SOLVED, STALLED, CONTRADICTION, BUDGET_EXHAUSTED }
    public enum Technique { FIXED_AND_REMAINDER, SUM_BOUNDS, RUN_COMBINATIONS, RUN_MATCHING }
    public record Change(int cell, int before, int after) { }
    public record Deduction(int runIndex, Technique technique, List<Change> changes,
                            int combinationsRemoved, int depth, String explanation) {
        public Deduction { changes = List.copyOf(changes); }
    }
    public record TechniqueMetrics(Technique technique, int applications, int eliminations, int forcedMoves) { }
    public record Result(Status status, List<Integer> candidates, List<Deduction> trace,
                         List<TechniqueMetrics> techniques, int whites, int solvedCells, int maxDepth,
                         int minimumBranchWidth, long work, long combinationChecks, long matchingChecks) {
        public Result {
            candidates = List.copyOf(candidates); trace = List.copyOf(trace); techniques = List.copyOf(techniques);
        }
        public int forcedMoves() { return techniques.stream().mapToInt(TechniqueMetrics::forcedMoves).sum(); }
        public int eliminations() { return techniques.stream().mapToInt(TechniqueMetrics::eliminations).sum(); }
        /** Productive run applications per white cell, not a calibrated human difficulty score. */
        public double effortPerCell() { return trace.size() / (double) whites; }
        public String strongestTechnique() {
            return techniques.stream().filter(t -> t.applications() > 0).map(t -> t.technique().name())
                    .reduce((a,b) -> b).orElse("NONE");
        }
        public boolean beyondSupportedLogic() { return status == Status.STALLED; }
        public int[] solvedValues() {
            if (status != Status.SOLVED) throw new IllegalStateException("Analysis has not solved the board");
            return candidates.stream().mapToInt(m -> m == 0 ? 0 : Integer.numberOfTrailingZeros(m)+1).toArray();
        }
    }

    public Result analyze(Board board) { return analyze(board, DEFAULT_WORK_LIMIT, Technique.RUN_MATCHING); }
    public Result analyze(Board board, long workLimit, Technique ceiling) {
        if (workLimit < 1 || ceiling == null) throw new IllegalArgumentException("Positive work budget and technique ceiling required");
        Session session = new Session(board, workLimit);
        Status status;
        try { status = session.run(ceiling); }
        catch (Limit e) { status = Status.BUDGET_EXHAUSTED; }
        catch (Contradiction e) { status = Status.CONTRADICTION; }
        return session.result(status);
    }

    private static final class Limit extends RuntimeException { }
    private static final class Contradiction extends RuntimeException { }
    private static final class Session {
        final Board board;
        final long limit;
        final int[] domains, depths, runDepth;
        final List<List<Integer>> combinations = new ArrayList<>();
        final List<Deduction> trace = new ArrayList<>();
        long work, combinationChecks, matchingChecks;

        Session(Board board, long limit) {
            this.board = board; this.limit = limit;
            domains = new int[board.cellCount()]; depths = new int[domains.length];
            runDepth = new int[board.runs().size()];
            for (int cell=0;cell<domains.length;cell++) if (board.isWhite(cell)) domains[cell]=511;
        }
        void tick() {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Logical analysis cancelled");
            if (work == limit) throw new Limit();
            work++;
        }
        Status run(Technique ceiling) {
            // Independent digit-set enumeration, deliberately not Solver's tables or propagation.
            for (Run run : board.runs()) {
                List<Integer> sets = new ArrayList<>();
                for (int mask=1;mask<512;mask++) {
                    tick();
                    if (Integer.bitCount(mask)==run.cells().size() && sum(mask)==run.sum()) sets.add(mask);
                }
                combinations.add(sets);
            }
            while (true) {
                boolean progress=false;
                for (Technique technique:Technique.values()) {
                    if (technique.ordinal()>ceiling.ordinal()) break;
                    for (int r=0;r<board.runs().size();r++) progress |= apply(r,technique);
                    if (progress) break; // Restart with the weakest rules after every productive pass.
                }
                if (solved()) {
                    int[] values=Arrays.stream(domains).map(m->m==0?0:Integer.numberOfTrailingZeros(m)+1).toArray();
                    try { board.validateSolution(values); } catch (IllegalArgumentException e) { throw new Contradiction(); }
                    return Status.SOLVED;
                }
                if (!progress) return Status.STALLED;
            }
        }
        boolean solved() {
            for(int c=0;c<domains.length;c++) if(board.isWhite(c) && Integer.bitCount(domains[c])!=1)return false;
            return true;
        }
        boolean apply(int index,Technique technique) {
            tick();
            Run run=board.runs().get(index);
            int[] before=run.cells().stream().mapToInt(c->domains[c]).toArray(), next=before.clone();
            List<Integer> old=combinations.get(index), remaining=old;
            switch(technique) {
                case FIXED_AND_REMAINDER -> fixed(run,before,next);
                case SUM_BOUNDS -> bounds(run,before,next);
                case RUN_COMBINATIONS, RUN_MATCHING -> {
                    remaining=new ArrayList<>();
                    int union=0,mandatory=511;
                    for(int mask:old) {
                        tick(); combinationChecks++;
                        if(!compatible(before,mask))continue;
                        if(technique==Technique.RUN_MATCHING && !matches(before,mask,-1,0))continue;
                        remaining.add(mask);union|=mask;mandatory&=mask;
                    }
                    if(remaining.isEmpty())throw new Contradiction();
                    for(int i=0;i<next.length;i++)next[i]&=union;
                    for(int bits=mandatory;bits!=0;bits&=bits-1) {
                        int bit=Integer.lowestOneBit(bits),only=-1,count=0;
                        for(int i=0;i<before.length;i++)if((before[i]&bit)!=0){only=i;count++;}
                        if(count==1)next[only]&=bit;
                    }
                    if(technique==Technique.RUN_MATCHING) {
                        int[] support=new int[next.length];
                        for(int mask:remaining)for(int i=0;i<before.length;i++)
                            for(int bits=before[i]&mask;bits!=0;bits&=bits-1) {
                                int bit=Integer.lowestOneBit(bits);
                                if((support[i]&bit)==0 && matches(before,mask,i,bit)) support[i]|=bit;
                            }
                        for(int i=0;i<next.length;i++)next[i]&=support[i];
                    }
                }
            }
            for(int mask:next)if(mask==0)throw new Contradiction();
            List<Change> changes=new ArrayList<>();
            int depth=runDepth[index];
            for(int c:run.cells())depth=Math.max(depth,depths[c]);
            depth++;
            for(int i=0;i<before.length;i++)if(before[i]!=next[i])
                changes.add(new Change(run.cells().get(i),before[i],next[i]));
            int removed=old.size()-remaining.size();
            if(changes.isEmpty() && removed==0)return false;
            // Commit a whole run deduction atomically; exhaustion cannot commit partial support sets.
            for(Change change:changes){domains[change.cell()]=change.after();depths[change.cell()]=depth;}
            combinations.set(index,remaining);runDepth[index]=depth;
            String reason=switch(technique) {
                case FIXED_AND_REMAINDER -> "Remove fixed peer digits; a single remaining cell equals the residual sum";
                case SUM_BOUNDS -> "Reject digits whose remaining sum lies outside candidate/distinct-digit bounds";
                case RUN_COMBINATIONS -> "Intersect feasible sum digit sets; place mandatory digits with one location";
                case RUN_MATCHING -> "Retain digits with a complete distinct placement in a feasible run digit set";
            };
            reason = run.direction()+" clue cell "+run.clueCell()+", length "+run.cells().size()+", sum "+run.sum()+": "+reason;
            trace.add(new Deduction(index,technique,changes,removed,depth,reason));
            return true;
        }
        void fixed(Run run,int[] before,int[] next) {
            int used=0,fixedSum=0,unknown=0,last=-1;
            for(int i=0;i<before.length;i++) {
                if(Integer.bitCount(before[i])==1) {
                    if((used&before[i])!=0)throw new Contradiction();
                    used|=before[i];fixedSum+=digit(before[i]);
                } else {unknown++;last=i;}
            }
            if(unknown==0 && fixedSum!=run.sum())throw new Contradiction();
            for(int i=0;i<next.length;i++)if(Integer.bitCount(before[i])>1)next[i]&=~used;
            if(unknown==1) {
                int value=run.sum()-fixedSum;
                if(value<1 || value>9)throw new Contradiction();
                next[last]&=1<<(value-1);
            }
        }
        void bounds(Run run,int[] before,int[] next) {
            for(int i=0;i<before.length;i++)for(int bits=before[i];bits!=0;bits&=bits-1) {
                tick();int bit=Integer.lowestOneBit(bits),min=0,max=0,union=0;
                boolean possible=true;
                for(int j=0;j<before.length;j++)if(i!=j) {
                    int other=before[j]&~bit;
                    if(other==0){possible=false;break;}
                    union|=other;min+=digit(Integer.lowestOneBit(other));max+=32-Integer.numberOfLeadingZeros(other);
                }
                int n=before.length-1;
                if(Integer.bitCount(union)<n)possible=false;
                if(possible) {
                    int low=0,high=0,count=0;
                    for(int b=union;b!=0 && count<n;b&=b-1,count++)low+=digit(Integer.lowestOneBit(b));
                    count=0;
                    for(int b=union;b!=0 && count<n;count++){int highest=Integer.highestOneBit(b);high+=digit(highest);b^=highest;}
                    int residual=run.sum()-digit(bit);
                    possible=residual>=Math.max(min,low) && residual<=Math.min(max,high);
                }
                if(!possible)next[i]&=~bit;
            }
        }
        boolean compatible(int[] domains,int mask) {
            int union=0;
            for(int domain:domains){if((domain&mask)==0)return false;union|=domain&mask;}
            return union==mask;
        }
        boolean matches(int[] domains,int mask,int forcedCell,int forcedBit) {
            tick();matchingChecks++;
            int[] owner=new int[9];Arrays.fill(owner,-1);
            if(forcedCell>=0)owner[digit(forcedBit)-1]=forcedCell;
            for(int cell=0;cell<domains.length;cell++)if(cell!=forcedCell)
                if(!augment(cell,domains,mask,forcedBit,owner,new boolean[9]))return false;
            return true;
        }
        boolean augment(int cell,int[] domains,int mask,int reserved,int[] owner,boolean[] seen) {
            for(int bits=domains[cell]&mask&~reserved;bits!=0;bits&=bits-1) {
                tick();int d=digit(Integer.lowestOneBit(bits))-1;
                if(seen[d])continue;seen[d]=true;
                if(owner[d]<0 || augment(owner[d],domains,mask,reserved,owner,seen)){owner[d]=cell;return true;}
            }
            return false;
        }
        Result result(Status status) {
            List<TechniqueMetrics> metrics=new ArrayList<>();
            for(Technique technique:Technique.values()) {
                int applications=0,eliminations=0,forced=0;
                for(Deduction step:trace)if(step.technique()==technique) {
                    applications++;
                    for(Change change:step.changes()) {
                        eliminations+=Integer.bitCount(change.before())-Integer.bitCount(change.after());
                        if(Integer.bitCount(change.after())==1 && Integer.bitCount(change.before())>1)forced++;
                    }
                }
                metrics.add(new TechniqueMetrics(technique,applications,eliminations,forced));
            }
            int solved=0,branch=10;
            for(int c=0;c<domains.length;c++)if(board.isWhite(c)) {
                int n=Integer.bitCount(domains[c]);if(n==1)solved++;else if(n>1)branch=Math.min(branch,n);
            }
            return new Result(status,Arrays.stream(domains).boxed().toList(),trace,metrics,board.whiteCount(),solved,
                    trace.stream().mapToInt(Deduction::depth).max().orElse(0),status==Status.STALLED && branch<10?branch:0,
                    work,combinationChecks,matchingChecks);
        }
    }
    private static int digit(int bit) { return Integer.numberOfTrailingZeros(bit)+1; }
    private static int sum(int mask) {
        int total=0;for(int bits=mask;bits!=0;bits&=bits-1)total+=digit(Integer.lowestOneBit(bits));return total;
    }
}
