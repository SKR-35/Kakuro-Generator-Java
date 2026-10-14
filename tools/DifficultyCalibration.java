import io.github.skr35.kakuro.analysis.LogicalAnalyzer;
import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** Reproducible descriptive analysis, not a fit of new thresholds to old labels. */
class DifficultyCalibration {
    record Sample(String cohort,String source,long id,Puzzle puzzle,LogicalAnalyzer.Result logic) { }
    static String quote(Object value) { return "\""+value.toString().replace("\"","\"\"")+"\""; }
    public static void main(String[] args) throws Exception {
        if(args.length<2)throw new IllegalArgumentException("Usage: output-directory cohort=database [...]");
        Path output=Path.of(args[0]);Files.createDirectories(output.resolve("traces"));
        var analyzer=new LogicalAnalyzer();List<Sample> samples=new ArrayList<>();Set<String> fingerprints=new HashSet<>();
        int duplicates=0;
        try(var manifest=new PrintWriter(Files.newBufferedWriter(output.resolve("manifest.csv")))) {
            manifest.println("cohort,database,id,fingerprint,included");
            for(int arg=1;arg<args.length;arg++) {
                String[] source=args[arg].split("=",2);
                if(source.length!=2 || !Files.isRegularFile(Path.of(source[1])))throw new IllegalArgumentException("Missing cohort/database: "+args[arg]);
                try(var db=new PuzzleRepository(Path.of(source[1]))) {
                    for(var saved:db.all()) {
                        var p=saved.puzzle();boolean included=fingerprints.add(p.fingerprint());
                        manifest.printf("%s,%s,%d,%s,%s%n",quote(source[0]),quote(source[1]),saved.id(),p.fingerprint(),included);
                        if(!included){duplicates++;continue;}
                        var result=analyzer.analyze(p.board());
                        if(result.status()==LogicalAnalyzer.Status.CONTRADICTION)throw new IllegalStateException("Contradiction in saved puzzle "+p.fingerprint());
                        if(result.status()==LogicalAnalyzer.Status.SOLVED && !Arrays.equals(p.solution(),result.solvedValues()))
                            throw new IllegalStateException("Derived solution mismatch "+p.fingerprint());
                        samples.add(new Sample(source[0],source[1],saved.id(),p,result));
                        writeTrace(output.resolve("traces").resolve(p.fingerprint()+".csv"),result);
                    }
                }
            }
        }
        try(var csv=new PrintWriter(Files.newBufferedWriter(output.resolve("metrics.csv")))) {
            csv.print("cohort,source,id,fingerprint,label,rows,cols,whites,legacy_score,search_nodes,status,strongest,forced,eliminations,productive_runs,effort_per_cell,max_depth,unresolved,min_branch_width,work,combination_checks,matching_checks,combinations_removed,length_component,combination_component,search_component");
            for(var technique:LogicalAnalyzer.Technique.values())csv.print(","+technique+"_applications,"+technique+"_eliminations,"+technique+"_forced");
            csv.println();
            for(var s:samples) {
                var p=s.puzzle();var r=s.logic();
                csv.printf(Locale.ROOT,"%s,%s,%d,%s,%s,%d,%d,%d,%.9f,%d,%s,%s,%d,%d,%d,%.9f,%d,%d,%d,%d,%d,%d,%d",
                        quote(s.cohort()),quote(s.source()),s.id(),p.fingerprint(),p.difficulty(),p.board().rows(),p.board().cols(),r.whites(),
                        p.score(),p.nodes(),r.status(),r.strongestTechnique(),r.forcedMoves(),r.eliminations(),r.trace().size(),r.effortPerCell(),
                        r.maxDepth(),r.whites()-r.solvedCells(),r.minimumBranchWidth(),r.work(),r.combinationChecks(),r.matchingChecks(),
                        r.trace().stream().mapToInt(LogicalAnalyzer.Deduction::combinationsRemoved).sum());
                double length=10*(p.board().runs().stream().mapToInt(run->run.cells().size()).average().orElseThrow()-2);
                double search=4*StrictMath.log(p.nodes())/StrictMath.log(2);
                csv.printf(Locale.ROOT,",%.9f,%.9f,%.9f",length,Difficulty.score(p.board(),1)-length,search);
                for(var t:r.techniques())csv.printf(",%d,%d,%d",t.applications(),t.eliminations(),t.forcedMoves());
                csv.println();
            }
        }
        try(var report=new PrintWriter(Files.newBufferedWriter(output.resolve("REPORT.md")))) {
            report.println("# Reproducible difficulty calibration observations\n");
            report.println("Analyzer: "+LogicalAnalyzer.VERSION+"; deterministic work limit "+LogicalAnalyzer.DEFAULT_WORK_LIMIT+".\n");
            report.println("Unique fingerprints: **"+samples.size()+"**; duplicate records excluded: "+duplicates+". See manifest.csv for every input.\n");
            report.println("Legacy labels are generator targets, not human ground truth. No thresholds were fitted to them. Logical effort is productive run deductions per white cell; depth is a conservative dependency-chain length under the fixed policy, not human reasoning depth. Search nodes and generation time are separate. SOLVED does not replace an exhaustive uniqueness proof. STALLED means further techniques or branching may be necessary; budget exhaustion is a separate outcome.\n");
            for(String cohort:samples.stream().map(Sample::cohort).distinct().toList()) {
                var group=samples.stream().filter(s->s.cohort().equals(cohort)).toList();
                report.println("## Cohort: "+cohort+"\n");
                report.println("| Label | N | Solved / stalled / exhausted | Strongest productive technique counts | Legacy score min / median / max | Logical effort min / Q1 / median / Q3 / max | Depth min / median / max |\n|---|---:|---|---|---|---|---|");
                for(var label:Difficulty.values()) {
                    var all=group.stream().filter(s->s.puzzle().difficulty()==label).toList();
                    if(all.isEmpty())continue;
                    var complete=all.stream().filter(s->s.logic().status()==LogicalAnalyzer.Status.SOLVED).toList();
                    Map<String,Integer> techniques=new TreeMap<>();for(var s:all)techniques.merge(s.logic().strongestTechnique(),1,Integer::sum);
                    report.printf("| %s | %d | %d / %d / %d | %s | %s | %s | %s |%n",label,all.size(),
                            count(all,LogicalAnalyzer.Status.SOLVED),count(all,LogicalAnalyzer.Status.STALLED),count(all,LogicalAnalyzer.Status.BUDGET_EXHAUSTED),
                            techniques,distribution(all,s->s.puzzle().score(),false),distribution(complete,s->s.logic().effortPerCell(),true),
                            distribution(complete,s->s.logic().maxDepth(),false));
                }
                report.println("\nLogical-effort/depth distributions above use SOLVED cases only; censored cases are counted separately.\n");
                report.println("### Matched-dimension observations\n");
                report.println("| Dimensions | Label | N | Solved | Median legacy score | Median logical effort (solved) | Median depth (solved) |\n|---|---|---:|---:|---:|---:|---:|");
                Map<String,List<Sample>> cells=new TreeMap<>();
                for(var s:group)cells.computeIfAbsent(s.puzzle().board().rows()+"x"+s.puzzle().board().cols()+" | "+s.puzzle().difficulty(),k->new ArrayList<>()).add(s);
                for(var entry:cells.entrySet()) {
                    var all=entry.getValue();var solved=all.stream().filter(s->s.logic().status()==LogicalAnalyzer.Status.SOLVED).toList();
                    report.printf(Locale.ROOT,"| %s | %d | %d | %s | %s | %s |%n",entry.getKey(),all.size(),solved.size(),
                            number(median(all,s->s.puzzle().score())),number(median(solved,s->s.logic().effortPerCell())),number(median(solved,s->s.logic().maxDepth())));
                }
                report.println("\n### Label overlap (solved cases)\n");
                report.println("Common effort range is the intersection of observed min/max intervals, not an estimated population overlap. P(higher > lower) gives half weight to ties and compares logical effort only. Small, selected samples do not support confidence claims.\n");
                report.println("| Lower / higher label | Common logical effort range | P(higher > lower) |\n|---|---|---:|");
                for(int a=0;a<4;a++)for(int b=a+1;b<4;b++) {
                    var low=select(group,Difficulty.values()[a]);var high=select(group,Difficulty.values()[b]);
                    if(low.isEmpty() || high.isEmpty())continue;
                    double min=Math.max(low.stream().mapToDouble(s->s.logic().effortPerCell()).min().orElseThrow(),high.stream().mapToDouble(s->s.logic().effortPerCell()).min().orElseThrow());
                    double max=Math.min(low.stream().mapToDouble(s->s.logic().effortPerCell()).max().orElseThrow(),high.stream().mapToDouble(s->s.logic().effortPerCell()).max().orElseThrow());
                    double wins=0;for(var x:low)for(var y:high){int c=Double.compare(y.logic().effortPerCell(),x.logic().effortPerCell());wins+=c>0?1:c==0?.5:0;}
                    report.printf(Locale.ROOT,"| %s / %s | %s | %.3f |%n",Difficulty.values()[a],Difficulty.values()[b],
                            min<=max?String.format(Locale.ROOT,"%.3f–%.3f",min,max):"none",wins/(low.size()*high.size()));
                }
                report.println("\n### Review examples (descriptive extremes, not proven mislabels)\n");
                for(var label:Difficulty.values()) {
                    var sorted=select(group,label).stream().sorted(Comparator.comparingDouble(s->s.logic().effortPerCell())).toList();
                    if(sorted.isEmpty())continue;
                    for(var sample:List.of(sorted.getFirst(),sorted.getLast())) {
                        var p=sample.puzzle();var r=sample.logic();
                        report.printf(Locale.ROOT,"- %s %dx%d, source `%s` ID %d, legacy %.3f, effort %.3f, depth %d, %s. [Deduction trace](traces/%s.csv).%n",
                                label,p.board().rows(),p.board().cols(),sample.source(),sample.id(),p.score(),r.effortPerCell(),r.maxDepth(),r.strongestTechnique(),p.fingerprint());
                    }
                }
                report.println();
                report.println("### Unresolved examples\n");
                report.println("These are proven-unique saved puzzles, but the supported logic reached a fixed point. The minimum branch width describes a possible next MRV split, not a bound on total search or proof that humans must guess.\n");
                for(var sample:group)if(sample.logic().status()!=LogicalAnalyzer.Status.SOLVED) {
                    var p=sample.puzzle();var r=sample.logic();
                    report.printf(Locale.ROOT,"- %s %dx%d, `%s` ID %d: %s, legacy %.3f, %d/%d cells resolved, minimum branch width %d, search nodes %d. [Partial trace](traces/%s.csv).%n",
                            p.difficulty(),p.board().rows(),p.board().cols(),sample.source(),sample.id(),r.status(),p.score(),r.solvedCells(),r.whites(),r.minimumBranchWidth(),p.nodes(),p.fingerprint());
                }
                report.println();
            }
            report.println("## Decision\n\nRetain existing Easy/Medium/Hard/Evil thresholds and stored scores. These cohorts expose overlaps but provide no human timings, error rates or independent human rankings. Replacing one arbitrary numeric scale with another would not establish calibration. Publish logical profiles alongside legacy labels, collect blinded human data, then fit and validate on held-out puzzles before versioning classification.\n");
        }
        System.out.println("Analyzed "+samples.size()+" puzzles; excluded "+duplicates+" duplicates; report: "+output.resolve("REPORT.md"));
    }
    static List<Sample> select(List<Sample> all,Difficulty label) {
        return all.stream().filter(s->s.puzzle().difficulty()==label && s.logic().status()==LogicalAnalyzer.Status.SOLVED).toList();
    }
    static long count(List<Sample> all,LogicalAnalyzer.Status status) { return all.stream().filter(s->s.logic().status()==status).count(); }
    static double median(List<Sample> all,ToDoubleFunction<Sample> metric) { return quantile(all.stream().mapToDouble(metric).sorted().toArray(),.5); }
    static String number(double value) { return Double.isFinite(value)?String.format(Locale.ROOT,"%.3f",value):"n/a"; }
    static String distribution(List<Sample> all,ToDoubleFunction<Sample> metric,boolean quartiles) {
        if(all.isEmpty())return "n/a";double[] values=all.stream().mapToDouble(metric).sorted().toArray();List<String> parts=new ArrayList<>();
        for(double q:quartiles?new double[]{0,.25,.5,.75,1}:new double[]{0,.5,1})parts.add(String.format(Locale.ROOT,"%.3f",quantile(values,q)));
        return String.join(" / ",parts);
    }
    static double quantile(double[] a,double q) { if(a.length==0)return Double.NaN;double p=q*(a.length-1);int i=(int)p;return a[i]+(a[(int)Math.ceil(p)]-a[i])*(p-i); }
    static void writeTrace(Path path,LogicalAnalyzer.Result result) throws Exception {
        try(var csv=new PrintWriter(Files.newBufferedWriter(path))) {
            csv.println("step,run_index,technique,depth,combinations_removed,cell,before_digits,after_digits,explanation");int step=0;
            for(var d:result.trace()) {
                step++;
                if(d.changes().isEmpty())csv.printf("%d,%d,%s,%d,%d,,,,%s%n",step,d.runIndex(),d.technique(),d.depth(),d.combinationsRemoved(),quote(d.explanation()));
                for(var c:d.changes())csv.printf("%d,%d,%s,%d,%d,%d,%s,%s,%s%n",step,d.runIndex(),d.technique(),d.depth(),d.combinationsRemoved(),c.cell(),digits(c.before()),digits(c.after()),quote(d.explanation()));
            }
        }
    }
    static String digits(int mask) { StringBuilder b=new StringBuilder();for(int d=1;d<=9;d++)if((mask&(1<<(d-1)))!=0)b.append(d);return b.toString(); }
}
