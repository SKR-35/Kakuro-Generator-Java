package io.github.skr35.kakuro.cli;

import io.github.skr35.kakuro.batch.BoundedProcess;
import io.github.skr35.kakuro.batch.ProbeWorker;
import io.github.skr35.kakuro.generator.Difficulty;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name="probe",mixinStandardHelpOptions=true,description="Opt-in bounded 15-row wide-board experiment; no success or difficulty guarantee.")
public final class ProbeCommand implements Callable<Integer> {
    @Option(names="--cols",required=true,description="30, 40 or 50; rows are fixed at 15") int cols;
    @Option(names="--difficulty",required=true,description="EASY, MEDIUM, HARD or EVIL") Difficulty difficulty;
    @Option(names="--seed",required=true) long seed;
    @Option(names="--max-attempts",required=true,description="1..1000 candidates") int attempts;
    @Option(names="--seconds",required=true,description="1..300 seconds including worker startup and uniqueness/storage checks") int seconds;
    @Option(names="--outdir",required=true,description="New directory for log and, only on success, puzzles.db") Path outdir;
    @Spec Model.CommandSpec spec;
    @Override public Integer call() throws Exception {
        BankCommand.rejectParentOptions(spec);
        if((cols!=30 && cols!=40 && cols!=50) || attempts<1 || attempts>1000 || seconds<1 || seconds>300 || Files.exists(outdir))
            throw new CommandLine.ParameterException(spec.commandLine(),"Require cols 30/40/50, attempts 1..1000, seconds 1..300 and a new output directory");
        Files.createDirectories(outdir);
        Path temporary=outdir.resolve("incomplete.db"),log=outdir.resolve("probe.log");
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var command=List.of(java,"-Xmx512m","-cp",System.getProperty("java.class.path"),ProbeWorker.class.getName(),
                Integer.toString(cols),difficulty.name(),Long.toString(seed),Integer.toString(attempts),temporary.toAbsolutePath().toString());
        var result=BoundedProcess.run(command,seconds*1000L,log);
        boolean accepted=!result.timedOut() && result.exitCode()==0 && Files.isRegularFile(temporary);
        if(accepted) Files.move(temporary,outdir.resolve("puzzles.db"));
        spec.commandLine().getOut().printf(Locale.ROOT,"Probe 15x%d %s status=%s elapsed=%.3fs attempts-cap=%d time-cap=%ds log=%s%n",
                cols,difficulty,accepted?"ACCEPTED_UNIQUE":result.timedOut()?"TIMEOUT":"FAILED_OR_EXHAUSTED",result.seconds(),attempts,seconds,log.toAbsolutePath());
        spec.commandLine().getOut().println("Timed-out partial statistics are unavailable; incomplete.db is not an accepted output. No quality or uniqueness gate is relaxed.");
        return accepted?0:1;
    }
}
