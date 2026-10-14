package io.github.skr35.kakuro.persistence;

import io.github.skr35.kakuro.batch.BatchGenerator;
import io.github.skr35.kakuro.generator.Difficulty;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;

/** Additive metadata; puzzles, canonical hashes and schema version remain unchanged. */
public final class BankStore implements AutoCloseable {
    public static final String VERSION = "bank-v1/kakuro-solution-v4";
    public record Config(long seed, int attempts) { }
    public record Bucket(int rows, int cols, Difficulty difficulty) {
        @Override public String toString() { return rows + "x" + cols + ":" + difficulty; }
    }
    private final FileChannel channel;
    private final FileLock lock;
    private final PuzzleRepository repository;

    public BankStore(Path db) throws Exception {
        Path absolute = db.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        absolute = Files.exists(absolute) ? absolute.toRealPath() : absolute.getParent().toRealPath().resolve(absolute.getFileName());
        channel = FileChannel.open(Path.of(absolute + ".bank.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = channel.tryLock(); }
        catch (OverlappingFileLockException e) { channel.close(); throw new IllegalStateException("Another bank producer owns this database", e); }
        if (acquired == null) { channel.close(); throw new IllegalStateException("Another bank producer owns this database"); }
        lock = acquired;
        PuzzleRepository opened = null;
        try {
            opened = new PuzzleRepository(absolute);
            repository = opened;
            try (var s = repository.connection().createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS bank_config (singleton INTEGER PRIMARY KEY CHECK(singleton=1), algorithm TEXT NOT NULL, seed INTEGER NOT NULL, attempts INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS bank_cursor (bucket TEXT PRIMARY KEY, next_slot INTEGER NOT NULL)");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS bank_events (
                    bucket TEXT NOT NULL, slot INTEGER NOT NULL, seed INTEGER NOT NULL, status TEXT NOT NULL,
                    puzzle_id INTEGER, candidates INTEGER NOT NULL, seconds REAL NOT NULL,
                    geometry_masks INTEGER NOT NULL, geometry_accepted INTEGER NOT NULL,
                    fill_failed INTEGER NOT NULL, solver_calls INTEGER NOT NULL, unique_candidates INTEGER NOT NULL,
                    in_band INTEGER NOT NULL, inconclusive INTEGER NOT NULL, quality_rejected INTEGER NOT NULL,
                    diversity_rejected INTEGER NOT NULL, geometry_exhausted INTEGER NOT NULL,
                    details TEXT NOT NULL, PRIMARY KEY(bucket,slot))
                    """);
                s.execute("CREATE INDEX IF NOT EXISTS bank_puzzle_bucket ON puzzles(COALESCE(board_rows,size),COALESCE(board_cols,size),difficulty)");
            }
        } catch (Exception e) {
            try { if (opened != null) opened.close(); }
            finally { try { lock.release(); } finally { channel.close(); } }
            throw e;
        }
    }

    public PuzzleRepository repository() { return repository; }

    public Config configure(Long requestedSeed, Integer requestedAttempts) throws SQLException {
        try (var s = repository.connection().createStatement(); var r = s.executeQuery("SELECT * FROM bank_config WHERE singleton=1")) {
            if (r.next()) {
                Config config = new Config(r.getLong("seed"), r.getInt("attempts"));
                if (!VERSION.equals(r.getString("algorithm")) || requestedSeed != null && requestedSeed != config.seed()
                        || requestedAttempts != null && requestedAttempts != config.attempts())
                    throw new IllegalArgumentException("Bank seed, algorithm and max-attempts are immutable; omit them on resume or use the original values");
                return config;
            }
        }
        if (requestedSeed == null) throw new IllegalArgumentException("A new bank requires --seed");
        int attempts = requestedAttempts == null ? 500 : requestedAttempts;
        if (attempts < 1) throw new IllegalArgumentException("max-attempts must be positive");
        try (var s = repository.connection().prepareStatement("INSERT INTO bank_config VALUES(1,?,?,?)")) {
            s.setString(1, VERSION); s.setLong(2, requestedSeed); s.setInt(3, attempts); s.executeUpdate();
        }
        return new Config(requestedSeed, attempts);
    }

    public long nextSlot(Bucket bucket) throws SQLException {
        try (var s = repository.connection().prepareStatement("SELECT next_slot FROM bank_cursor WHERE bucket=?")) {
            s.setString(1, bucket.toString());
            try (var r = s.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
        }
    }

    /** Even exhausted and duplicate slots advance, so resuming never retries committed failures. */
    public String complete(Bucket bucket, long slot, BatchGenerator.Outcome outcome) throws SQLException {
        var stats = outcome.statistics();
        String[] status = new String[1];
        repository.saveCheckpoint(outcome.generated() == null ? null : outcome.generated().puzzle(), inserted -> {
            if (nextSlot(bucket) != slot) throw new SQLException("Stale production checkpoint");
            status[0] = inserted == null ? "EXHAUSTED" : inserted.inserted() ? "ACCEPTED" : "DUPLICATE";
            try (var s = repository.connection().prepareStatement("INSERT INTO bank_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, bucket.toString()); s.setLong(2, slot); s.setLong(3, outcome.task().seed()); s.setString(4, status[0]);
                if (inserted == null) s.setNull(5, java.sql.Types.INTEGER); else s.setLong(5, inserted.id());
                s.setInt(6, stats.candidates()); s.setDouble(7, outcome.seconds());
                s.setLong(8, stats.geometryMasks()); s.setLong(9, stats.geometryAccepted());
                s.setInt(10, stats.fillAttempts() - stats.fillSuccesses()); s.setInt(11, stats.solverCalls());
                s.setInt(12, stats.unique()); s.setInt(13, stats.inBand()); s.setInt(14, stats.inconclusive());
                s.setInt(15, stats.solutionQualityRejected()); s.setInt(16, stats.diversityRejected()); s.setInt(17, stats.geometryExhaustions());
                s.setString(18, outcome.failure() == null ? stats.toString() : outcome.failure()); s.executeUpdate();
            }
            try (var s = repository.connection().prepareStatement("INSERT INTO bank_cursor VALUES(?,?) ON CONFLICT(bucket) DO UPDATE SET next_slot=excluded.next_slot")) {
                s.setString(1, bucket.toString()); s.setLong(2, Math.addExact(slot, 1)); s.executeUpdate();
            }
        });
        return status[0];
    }

    public String totals() throws SQLException {
        try (var s = repository.connection().createStatement(); var r = s.executeQuery("""
            SELECT count(*),coalesce(sum(status='ACCEPTED'),0),coalesce(sum(status='DUPLICATE'),0),
            coalesce(sum(status='EXHAUSTED'),0),coalesce(sum(candidates),0),coalesce(sum(unique_candidates),0),
            coalesce(sum(solver_calls-unique_candidates-inconclusive),0),coalesce(sum(inconclusive),0),
            coalesce(sum(unique_candidates-in_band),0),coalesce(sum(quality_rejected),0),coalesce(sum(diversity_rejected),0),
            coalesce(sum(geometry_exhausted),0),coalesce(sum(fill_failed),0),coalesce(sum(geometry_masks),0),coalesce(sum(geometry_accepted),0)
            FROM bank_events
            """)) {
            r.next();
            return "Lifetime committed slots="+r.getLong(1)+" accepted="+r.getLong(2)+" duplicates="+r.getLong(3)
                +" exhausted="+r.getLong(4)+" candidates="+r.getLong(5)+" unique-candidates="+r.getLong(6)
                +" nonunique="+r.getLong(7)+" inconclusive="+r.getLong(8)+" out-of-band="+r.getLong(9)
                +" quality-rejected="+r.getLong(10)+" diversity-rejected="+r.getLong(11)+" geometry-exhausted="+r.getLong(12)
                +" fill-failed="+r.getLong(13)+" geometry-masks="+r.getLong(14)+" geometry-accepted="+r.getLong(15)
                +" (stage counters overlap; interrupted/speculative work is not committed)";
        }
    }

    /** Versioned stateless stream: independent of workers, target counts and invocation boundaries. */
    public static long seed(long master, Bucket bucket, long slot) {
        long key = ((long) bucket.rows() << 32) ^ ((long) bucket.cols() << 16) ^ bucket.difficulty().ordinal();
        return mix(master ^ mix(key) ^ mix(slot + 0x9e3779b97f4a7c15L));
    }
    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    @Override public void close() throws Exception {
        try { repository.close(); } finally { try { lock.release(); } finally { channel.close(); } }
    }
}
