package io.github.skr35.kakuro;

import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.solver.Solver;

final class Fixtures {
    static Board tiny(int... sums) {
        return new Board(3, new boolean[]{false,false,false,false,true,true,false,true,true}, sums);
    }
    static Puzzle unique() {
        Board board = tiny(3,7,4,6);
        Solver.Result proof = new Solver().solve(board, 100);
        return new Puzzle(board, proof.solutions().getFirst(), Difficulty.EASY,
                Difficulty.score(board, proof.nodes()), 42, proof.nodes());
    }
}
