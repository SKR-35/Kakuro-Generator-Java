package io.github.skr35.kakuro.batch;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Hard process boundary for optional experiments, never used for deterministic production. */
public final class BoundedProcess {
    private BoundedProcess() { }
    public record Result(boolean timedOut, int exitCode, double seconds) { }
    public static Result run(List<String> command, long timeoutMillis, Path log) throws Exception {
        if(timeoutMillis<1) throw new IllegalArgumentException("Positive timeout required");
        long start=System.nanoTime();
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Thread hook=new Thread(process::destroyForcibly,"probe-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            boolean finished=process.waitFor(timeoutMillis,TimeUnit.MILLISECONDS);
            if(!finished) {
                process.destroyForcibly();
                if(!process.waitFor(5,TimeUnit.SECONDS)) throw new IllegalStateException("Timed-out worker did not terminate after forced kill");
            }
            return new Result(!finished,process.exitValue(),(System.nanoTime()-start)/1e9);
        } finally {
            if(process.isAlive()) process.destroyForcibly();
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch(IllegalStateException ignored) { }
        }
    }
}
