package io.github.skr35.kakuro.model;

import java.util.List;

/** Cell positions are row-major indices into the square board. */
public record Run(Direction direction, int clueCell, List<Integer> cells, int sum) {
    public enum Direction { ACROSS, DOWN }

    public Run {
        cells = List.copyOf(cells);
        if (direction == null || cells.size() < 2 || cells.size() > 9) {
            throw new IllegalArgumentException("A run requires a direction and 2..9 cells");
        }
    }
}
