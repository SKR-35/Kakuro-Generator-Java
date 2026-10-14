package io.github.skr35.kakuro;

import io.github.skr35.kakuro.cli.KakuroCommand;
import picocli.CommandLine;

/** Standalone command-line entry point. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        CommandLine cli = new CommandLine(new KakuroCommand());
        cli.setExecutionExceptionHandler((error, command, parseResult) -> {
            command.getErr().println("Error: " + error.getMessage());
            return 1;
        });
        System.exit(cli.execute(args));
    }
}
