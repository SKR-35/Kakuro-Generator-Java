package io.github.skr35.kakuro.cli;

import io.github.skr35.kakuro.batch.BatchGenerator;
import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.persistence.BankStore;
import io.github.skr35.kakuro.persistence.BankStore.Bucket;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name="bank", mixinStandardHelpOptions=true, description="Fill durable size/difficulty targets; repeat the command to resume. No PDF output.")
public final class BankCommand implements Callable<Integer> {
    @Option(names="--db", required=true) Path db;
    @Option(names="--target", description="Repeatable ROWSxCOLS:DIFFICULTY:TOTAL, e.g. 8x8:EASY:50") List<String> targets;
    @Option(names="--sizes", split=",", description="Square sizes for --each, e.g. 8,10,12,15") List<Integer> sizes;
    @Option(names="--each", description="Target for every difficulty at every --sizes entry") Integer each;
    @Option(names="--seed", description="Required for a new bank; immutable on resume") Long seed;
    @Option(names="--max-attempts", description="Candidate budget per slot; initial default 500, inherited on resume") Integer attempts;
    @Option(names="--max-slots", defaultValue="1000", description="Maximum committed generation slots this invocation (default 1000)") int maxSlots;
    @Option(names="--workers", defaultValue="1") int workers;
    @Spec Model.CommandSpec spec;
    private static final class TargetReached extends RuntimeException { }

    @Override public Integer call() throws Exception {
        rejectParentOptions(spec);
        Map<Bucket,Integer> requested = parseTargets();
        if (workers<1 || workers>256 || maxSlots<1 || maxSlots>1_000_000 || attempts!=null && attempts<1)
            throw usage("workers must be 1..256, max-slots 1..1000000 and max-attempts positive");
        long start=System.nanoTime();
        AtomicInteger committed=new AtomicInteger(), accepted=new AtomicInteger();
        Thread caller=Thread.currentThread();
        Thread hook=new Thread(() -> { caller.interrupt(); try { caller.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } },"bank-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        var heartbeat=Executors.newSingleThreadScheduledExecutor();
        try (var bank=new BankStore(db)) {
            BankStore.Config config;
            try { config=bank.configure(seed,attempts); } catch (IllegalArgumentException e) { throw usage(e.getMessage()); }
            out("Bank " + BankStore.VERSION + " seed="+config.seed()+" max-attempts="+config.attempts()+"; existing records count toward targets.");
            heartbeat.scheduleAtFixedRate(() -> out(String.format(Locale.ROOT,"Progress: committed-slots=%d accepted=%d elapsed=%.1fs; waiting for ordered work/checkpoint",committed.get(),accepted.get(),seconds(start))),30,30,TimeUnit.SECONDS);
            for (var entry:requested.entrySet()) {
                Bucket bucket=entry.getKey(); int target=entry.getValue();
                while (bank.repository().count(bucket.rows(),bucket.cols(),bucket.difficulty())<target && committed.get()<maxSlots) {
                    long first=bank.nextSlot(bucket);
                    int count=Math.min(64,maxSlots-committed.get());
                    List<BatchGenerator.Task> tasks=new ArrayList<>();
                    for(int i=0;i<count;i++) tasks.add(new BatchGenerator.Task(i+1,bucket.difficulty(),bucket.rows(),bucket.cols(),
                            BankStore.seed(config.seed(),bucket,Math.addExact(first,i)),config.attempts()));
                    var previous=bank.repository().bucket(bucket.rows(),bucket.cols(),bucket.difficulty());
                    try {
                        new BatchGenerator().run(tasks,workers,previous,result -> {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                            long ordinal=first+result.task().slot()-1;
                            String status=bank.complete(bucket,ordinal,result);
                            committed.incrementAndGet(); if(status.equals("ACCEPTED")) accepted.incrementAndGet();
                            long stored=bank.repository().count(bucket.rows(),bucket.cols(),bucket.difficulty());
                            out(String.format(Locale.ROOT,"%s slot=%d seed=%d %s bank=%d/%d candidates=%d elapsed=%.2fs accepted/hour=%.2f uniqueness=%s",
                                    bucket,ordinal,result.task().seed(),status,stored,target,result.statistics().candidates(),seconds(start),accepted.get()*3600/Math.max(.001,seconds(start)),
                                    status.equals("ACCEPTED")?"PROVEN":"no-new-record"));
                            out("  "+result.statistics());
                            if(stored>=target) throw new TargetReached();
                        });
                    } catch(TargetReached ignored) { /* Cancel speculative work; uncommitted seeds are replayable. */ }
                }
            }
            boolean complete=true;
            for(var entry:requested.entrySet()) {
                Bucket b=entry.getKey(); long count=bank.repository().count(b.rows(),b.cols(),b.difficulty());
                out("Target "+b+"="+entry.getValue()+" stored="+count+" remaining="+Math.max(0,entry.getValue()-count));
                complete &= count>=entry.getValue();
            }
            out(bank.totals());
            out(String.format(Locale.ROOT,"Run accepted=%d committed-slots=%d elapsed=%.2fs throughput=%.2f accepted/hour; %s",
                    accepted.get(),committed.get(),seconds(start),accepted.get()*3600/Math.max(.001,seconds(start)),complete?"TARGETS COMPLETE":"SLOT LIMIT: repeat to resume"));
            return complete?0:1;
        } catch(InterruptedException | CancellationException e) {
            Thread.currentThread().interrupt();
            out("Cancelled: committed puzzles and checkpoints preserved; uncommitted slots replay on resume."); return 130;
        } finally {
            heartbeat.shutdownNow();
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch(IllegalStateException ignored) { }
        }
    }

    private Map<Bucket,Integer> parseTargets() {
        Map<Bucket,Integer> result=new TreeMap<>(Comparator.comparing((Bucket b)->b.difficulty().ordinal()).thenComparingInt(Bucket::rows).thenComparingInt(Bucket::cols));
        if ((sizes==null)!=(each==null) || sizes!=null && targets!=null) throw usage("Use --sizes with --each OR repeat --target");
        if(sizes!=null) for(int size:sizes) for(Difficulty d:Difficulty.values()) add(result,new Bucket(size,size,d),each);
        if(targets!=null) for(String target:targets) {
            try {
                String[] fields=target.split(":"); String[] dimensions=fields[0].toLowerCase(Locale.ROOT).split("x");
                if(fields.length!=3 || dimensions.length!=2) throw new IllegalArgumentException();
                add(result,new Bucket(Integer.parseInt(dimensions[0]),Integer.parseInt(dimensions[1]),Difficulty.valueOf(fields[1].toUpperCase(Locale.ROOT))),Integer.parseInt(fields[2]));
            } catch(IllegalArgumentException | IndexOutOfBoundsException e) { throw usage("Invalid target: "+target+"; use ROWSxCOLS:DIFFICULTY:TOTAL, dimensions 7..20"); }
        }
        if(result.isEmpty()) throw usage("Supply --sizes/--each or --target");
        return result;
    }
    private void add(Map<Bucket,Integer> map,Bucket b,int count) {
        if(b.rows()<7 || b.rows()>20 || b.cols()<7 || b.cols()>20 || count<0 || count>1_000_000) throw usage("Dimensions must be 7..20; targets 0..1000000");
        if(map.putIfAbsent(b,count)!=null) throw usage("Duplicate target: "+b);
    }
    private static double seconds(long start) { return (System.nanoTime()-start)/1e9; }
    static void rejectParentOptions(Model.CommandSpec spec) {
        if(spec.parent()!=null && !spec.parent().commandLine().getParseResult().matchedOptions().isEmpty())
            throw new CommandLine.ParameterException(spec.commandLine(),"Place all options after the subcommand; legacy generation options cannot be combined with it");
    }
    private CommandLine.ParameterException usage(String message) { return new CommandLine.ParameterException(spec.commandLine(),message); }
    private synchronized void out(String text) { spec.commandLine().getOut().println(text); spec.commandLine().getOut().flush(); }
}
