package io.github.skr35.kakuro.persistence;

import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.generator.Generator;
import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.solver.Solver;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Single-owner JDBC repository. Each new puzzle commits in its own transaction. */
public final class PuzzleRepository implements AutoCloseable {
    private static final int APPLICATION_ID = 0x4b414b55;
    public record Saved(long id, Puzzle puzzle) { }
    public record Insert(long id, boolean inserted) { }
    private final Connection connection;

    public PuzzleRepository(Path path) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            int applicationId;
            try (ResultSet r = s.executeQuery("PRAGMA application_id")) { r.next(); applicationId = r.getInt(1); }
            int version;
            try (ResultSet r = s.executeQuery("PRAGMA user_version")) { r.next(); version = r.getInt(1); }
            if (applicationId != 0 && applicationId != APPLICATION_ID || version > 2) {
                throw new SQLException("Unsupported database application or schema version");
            }
            if (applicationId == 0) {
                try (ResultSet r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                    r.next();
                    if (r.getInt(1) != 0) throw new SQLException("Refusing an unrecognized database; use a new file");
                }
            }
            s.execute("PRAGMA journal_mode=WAL");
            connection.setAutoCommit(false);
            s.execute("""
                CREATE TABLE IF NOT EXISTS puzzles (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    fingerprint TEXT NOT NULL UNIQUE,
                    size INTEGER NOT NULL,
                    white_mask TEXT NOT NULL,
                    clues TEXT NOT NULL,
                    solution TEXT NOT NULL,
                    difficulty TEXT NOT NULL,
                    score REAL NOT NULL,
                    seed INTEGER NOT NULL,
                    search_nodes INTEGER NOT NULL,
                    created_utc TEXT NOT NULL
                )
                """);
            s.execute("PRAGMA application_id=" + APPLICATION_ID);
            if (version < 2) {
                s.execute("ALTER TABLE puzzles ADD COLUMN board_rows INTEGER");
                s.execute("ALTER TABLE puzzles ADD COLUMN board_cols INTEGER");
            }
            s.execute("PRAGMA user_version=2");
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    public Insert save(Puzzle puzzle) throws SQLException {
        return saveCheckpoint(puzzle, inserted -> { });
    }

    @FunctionalInterface public interface Checkpoint { void write(Insert inserted) throws SQLException; }

    /** Puzzle insertion and production checkpoint share one atomic SQLite transaction. */
    public Insert saveCheckpoint(Puzzle puzzle, Checkpoint checkpoint) throws SQLException {
        if (puzzle != null) validateProof(puzzle);
        connection.setAutoCommit(false);
        try {
            Insert inserted = puzzle == null ? null : insert(puzzle);
            checkpoint.write(inserted);
            connection.commit();
            return inserted;
        } catch (SQLException | RuntimeException | Error e) {
            connection.rollback();
            throw e;
        } finally { connection.setAutoCommit(true); }
    }

    private static void validateProof(Puzzle puzzle) {
        Solver.Result proof = new Solver().solve(puzzle.board(), Generator.SOLVER_NODE_LIMIT);
        if (proof.status() != Solver.Status.UNIQUE || !Arrays.equals(proof.solutions().getFirst(), puzzle.solution())) {
            throw new IllegalArgumentException("Refusing to store a puzzle without a matching unique solution");
        }
        if (proof.nodes() != puzzle.nodes() || Math.abs(Difficulty.score(puzzle.board(), proof.nodes()) - puzzle.score()) > 1e-9) {
            throw new IllegalArgumentException("Incorrect difficulty statistics");
        }
    }

    private Insert insert(Puzzle puzzle) throws SQLException {
            int updated;
            try (var s = connection.prepareStatement("""
                INSERT INTO puzzles(fingerprint,size,white_mask,clues,solution,difficulty,score,seed,search_nodes,created_utc,board_rows,board_cols)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(fingerprint) DO NOTHING
                """)) {
                StringBuilder mask = new StringBuilder();
                for (boolean white : puzzle.board().white()) mask.append(white ? '1' : '0');
                s.setString(1, puzzle.fingerprint()); s.setInt(2, puzzle.board().rows());
                s.setString(3, mask.toString()); s.setString(4, encode(puzzle.board().sums()));
                s.setString(5, encode(puzzle.solution())); s.setString(6, puzzle.difficulty().name());
                s.setDouble(7, puzzle.score()); s.setLong(8, puzzle.seed());
                s.setLong(9, puzzle.nodes()); s.setString(10, Instant.now().toString());
                s.setInt(11, puzzle.board().rows()); s.setInt(12, puzzle.board().cols());
                updated = s.executeUpdate();
            }
            long id;
            try (var s = connection.prepareStatement("SELECT id FROM puzzles WHERE fingerprint=?")) {
                s.setString(1, puzzle.fingerprint());
                try (ResultSet rows = s.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Saved puzzle could not be found");
                    id = rows.getLong(1);
                }
            }
            return new Insert(id, updated == 1);
    }

    /** Package-private access for additive production metadata on the same transaction. */
    Connection connection() { return connection; }

    public long count(int rows, int cols, Difficulty difficulty) throws SQLException {
        try (var s = connection.prepareStatement("SELECT count(*) FROM puzzles WHERE COALESCE(board_rows,size)=? AND COALESCE(board_cols,size)=? AND difficulty=?")) {
            s.setInt(1, rows); s.setInt(2, cols); s.setString(3, difficulty.name());
            try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    /** Keyset pagination avoids loading a large bank into memory for analysis or verification. */
    public List<Saved> page(long afterId, int limit) throws SQLException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Page limit must be 1..1000");
        List<Saved> result = new ArrayList<>();
        try (var s = connection.prepareStatement("SELECT * FROM puzzles WHERE id>? ORDER BY id LIMIT ?")) {
            s.setLong(1, afterId); s.setInt(2, limit);
            try (var r = s.executeQuery()) { while (r.next()) result.add(decode(r)); }
        }
        return List.copyOf(result);
    }

    /** Bounded-memory scan with a starting high-water ID; keep the repository open. */
    public Iterable<Saved> scan() throws SQLException {
        final long last;
        try (var s = connection.createStatement(); var r = s.executeQuery("SELECT COALESCE(max(id),0) FROM puzzles")) { r.next(); last = r.getLong(1); }
        return () -> new java.util.Iterator<>() {
            long after;
            java.util.Iterator<Saved> page = List.<Saved>of().iterator();
            @Override public boolean hasNext() {
                if (!page.hasNext() && after < last) {
                    try { page = page(after,100).stream().filter(saved -> saved.id() <= last).iterator(); }
                    catch (SQLException e) { throw new IllegalStateException("Database scan failed",e); }
                    if (!page.hasNext()) after = last;
                }
                return page.hasNext();
            }
            @Override public Saved next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                Saved saved = page.next(); after = saved.id(); return saved;
            }
        };
    }

    public List<Puzzle> bucket(int rows, int cols, Difficulty difficulty) throws SQLException {
        List<Puzzle> result = new ArrayList<>();
        try (var s = connection.prepareStatement("SELECT * FROM puzzles WHERE COALESCE(board_rows,size)=? AND COALESCE(board_cols,size)=? AND difficulty=? ORDER BY id")) {
            s.setInt(1, rows); s.setInt(2, cols); s.setString(3, difficulty.name());
            try (var r = s.executeQuery()) { while (r.next()) result.add(decode(r).puzzle()); }
        }
        return List.copyOf(result);
    }

    public Optional<Saved> find(long id) throws SQLException {
        try (var s = connection.prepareStatement("SELECT * FROM puzzles WHERE id=?")) {
            s.setLong(1, id);
            try (ResultSet row = s.executeQuery()) { return row.next() ? Optional.of(decode(row)) : Optional.empty(); }
        }
    }

    public List<Saved> all() throws SQLException {
        List<Saved> result = new ArrayList<>();
        try (var s = connection.createStatement(); var rows = s.executeQuery("SELECT * FROM puzzles ORDER BY id")) {
            while (rows.next()) result.add(decode(rows));
        }
        return List.copyOf(result);
    }

    private static Saved decode(ResultSet row) throws SQLException {
        try {
            String mask = row.getString("white_mask");
            boolean[] white = new boolean[mask.length()];
            for (int i = 0; i < mask.length(); i++) {
                if (mask.charAt(i) != '0' && mask.charAt(i) != '1') throw new IllegalArgumentException("Invalid mask");
                white[i] = mask.charAt(i) == '1';
            }
            int height = row.getInt("board_rows"); if (row.wasNull()) height = row.getInt("size");
            int width = row.getInt("board_cols"); if (row.wasNull()) width = row.getInt("size");
            Board board = new Board(height, width, white, decodeNumbers(row.getString("clues")));
            Puzzle puzzle = new Puzzle(board, decodeNumbers(row.getString("solution")),
                    Difficulty.valueOf(row.getString("difficulty")), row.getDouble("score"),
                    row.getLong("seed"), row.getLong("search_nodes"));
            if (!puzzle.fingerprint().equals(row.getString("fingerprint"))) throw new IllegalArgumentException("Fingerprint mismatch");
            return new Saved(row.getLong("id"), puzzle);
        } catch (IllegalArgumentException e) { throw new SQLException("Invalid stored puzzle: " + e.getMessage(), e); }
    }

    private static String encode(int[] values) {
        return Arrays.stream(values).mapToObj(Integer::toString).collect(Collectors.joining(","));
    }
    private static int[] decodeNumbers(String text) { return Arrays.stream(text.split(",")).mapToInt(Integer::parseInt).toArray(); }
    @Override public void close() throws SQLException { connection.close(); }
}
