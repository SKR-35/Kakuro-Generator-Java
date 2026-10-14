package io.github.skr35.kakuro.cli;

import io.github.skr35.kakuro.analysis.LogicalAnalyzer;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name="bank-report",mixinStandardHelpOptions=true,description="Read saved records in bounded pages; optional logical-v1 CSV, never reclassifies puzzles.")
public final class BankReportCommand implements Callable<Integer> {
    @Option(names="--db",required=true) Path db;
    @Option(names="--logical") boolean logical;
    @Option(names="--analysis-budget",defaultValue="5000000") long budget;
    @Spec Model.CommandSpec spec;
    @Override public Integer call() throws Exception {
        BankCommand.rejectParentOptions(spec);
        if(!logical && spec.commandLine().getParseResult().hasMatchedOption("--analysis-budget"))
            throw new CommandLine.ParameterException(spec.commandLine(),"--analysis-budget requires --logical");
        if(!Files.isRegularFile(db) || budget<1) throw new CommandLine.ParameterException(spec.commandLine(),"Existing database and positive analysis budget required");
        Map<String,Integer> counts=new TreeMap<>(),statuses=new TreeMap<>();
        int failed=0,total=0;
        try(var repository=new PuzzleRepository(db)) {
            if(logical) spec.commandLine().getOut().println("id,fingerprint,rows,cols,label,legacy_score,logical_version,status,strongest,forced,eliminations,effort_per_cell,depth,minimum_branch_width,work");
                for(var saved:repository.scan()) {
                    var p=saved.puzzle(); total++;
                    counts.merge(p.board().rows()+"x"+p.board().cols()+":"+p.difficulty(),1,Integer::sum);
                    if(logical) {
                        var a=new LogicalAnalyzer().analyze(p.board(),budget,LogicalAnalyzer.Technique.RUN_MATCHING);
                        statuses.merge(a.status().name(),1,Integer::sum);
                        if(a.status()==LogicalAnalyzer.Status.CONTRADICTION || a.status()==LogicalAnalyzer.Status.BUDGET_EXHAUSTED) failed++;
                        spec.commandLine().getOut().printf(Locale.ROOT,"%d,%s,%d,%d,%s,%.9f,%s,%s,%s,%d,%d,%.9f,%d,%d,%d%n",
                                saved.id(),p.fingerprint(),p.board().rows(),p.board().cols(),p.difficulty(),p.score(),LogicalAnalyzer.VERSION,a.status(),a.strongestTechnique(),a.forcedMoves(),a.eliminations(),a.effortPerCell(),a.maxDepth(),a.minimumBranchWidth(),a.work());
                    }
                }
        }
        var summary=logical?spec.commandLine().getErr():spec.commandLine().getOut();
        summary.println("Stored puzzles="+total+" by bucket="+counts);
        if(logical) summary.println("Logical statuses="+statuses+"; not human-calibrated. STALLED does not prove guessing; this report does not replace --verify-db.");
        return failed==0?0:1;
    }
}
