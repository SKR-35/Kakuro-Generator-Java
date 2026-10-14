package io.github.skr35.kakuro.model;

import io.github.skr35.kakuro.generator.Difficulty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record Puzzle(Board board, int[] solution, Difficulty difficulty, double score, long seed, long nodes) {
    public Puzzle {
        board.validateSolution(solution);
        if (difficulty == null || !Double.isFinite(score) || !difficulty.accepts(score) || nodes < 1) {
            throw new IllegalArgumentException("Puzzle score must match its difficulty band");
        }
        solution = solution.clone();
    }
    @Override public int[] solution() { return solution.clone(); }
    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(board.canonical().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
