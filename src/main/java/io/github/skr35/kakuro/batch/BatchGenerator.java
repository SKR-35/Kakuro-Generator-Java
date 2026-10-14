package io.github.skr35.kakuro.batch;

import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.generator.Generator;
import io.github.skr35.kakuro.geometry.GeometryDiversity;
import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.generator.SolutionQualityEvaluator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Bounded work queue; callbacks run on the caller in stable schedule order. */
public final class BatchGenerator {
    public record Task(int slot, Difficulty difficulty, int rows, int cols, long seed, int attempts) {
        public Task(int slot, Difficulty difficulty, int size, long seed, int attempts) {
            this(slot, difficulty, size, size, seed, attempts);
        }
    }
    public record Outcome(Task task, Generator.Generated generated, double seconds, String failure, Generator.Statistics statistics) { }
    private record Work(Outcome outcome, Generator.Session session) { }
    @FunctionalInterface public interface Receiver { void accept(Outcome outcome) throws Exception; }

    public void run(List<Task> tasks, int workers, Receiver receiver) throws Exception {
        run(tasks, workers, List.of(), receiver);
    }

    /** Seed diversity checks with durable records when continuing a production bank. */
    public void run(List<Task> tasks, int workers, List<Puzzle> previous, Receiver receiver) throws Exception {
        if (workers < 1 || workers > 256) throw new IllegalArgumentException("workers must be 1..256");
        var executor = Executors.newFixedThreadPool(workers);
        ArrayDeque<Future<Work>> pending = new ArrayDeque<>();
        List<Board> accepted = new ArrayList<>(previous.stream().map(Puzzle::board).toList());
        List<Puzzle> acceptedSolutions = new ArrayList<>(previous);
        var solutionQuality = new SolutionQualityEvaluator();
        try {
            int submitted = 0;
            while (submitted < tasks.size() || !pending.isEmpty()) {
                while (submitted < tasks.size() && pending.size() < workers * 2) {
                    Task task = tasks.get(submitted++);
                    pending.add(executor.submit(() -> {
                        long start = System.nanoTime();
                        var session = new Generator.Session(task.difficulty(), task.rows(), task.cols(), task.seed(), task.attempts());
                        try {
                            var generated = session.next(board -> true);
                            return new Work(new Outcome(task, generated, (System.nanoTime() - start) / 1e9, null, session.statistics()), session);
                        } catch (IllegalStateException e) {
                            return new Work(new Outcome(task, null, (System.nanoTime() - start) / 1e9, e.getMessage(), session.statistics()), session);
                        }
                    }));
                }
                try {
                    Work work = pending.remove().get();
                    Outcome result = work.outcome();
                    if (result.failure() == null && (!GeometryDiversity.accepts(result.generated().puzzle().board(), accepted)
                            || !solutionQuality.acceptsBatch(result.generated().puzzle().board(),result.generated().puzzle().solution(),acceptedSolutions))) {
                        work.session().recordBatchRejection();
                        long start = System.nanoTime();
                        try {
                            var replacement = work.session().next(board -> GeometryDiversity.accepts(board, accepted),
                                    (board,values) -> solutionQuality.acceptsBatch(board,values,acceptedSolutions));
                            result = new Outcome(result.task(), replacement, result.seconds() + (System.nanoTime() - start) / 1e9, null, work.session().statistics());
                        } catch (IllegalStateException e) {
                            result = new Outcome(result.task(), null, result.seconds() + (System.nanoTime() - start) / 1e9, e.getMessage(), work.session().statistics());
                        }
                    }
                    // Selection is deterministic in schedule order, never completion order.
                    if (result.failure() == null) {
                        accepted.add(result.generated().puzzle().board());
                        acceptedSolutions.add(result.generated().puzzle());
                    }
                    receiver.accept(result);
                }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception exception) throw exception;
                    throw new IllegalStateException("Worker failed", e.getCause());
                }
            }
        } finally {
            for (Future<?> future : pending) future.cancel(true);
            executor.shutdownNow();
            try { executor.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
