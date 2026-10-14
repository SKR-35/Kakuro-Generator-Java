package io.github.skr35.kakuro;

import io.github.skr35.kakuro.cli.KakuroCommand;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @Test
    void helpIsReal() {
        StringWriter text = new StringWriter();
        CommandLine command = new CommandLine(new KakuroCommand()).setOut(new PrintWriter(text));
        assertEquals(0, command.execute("--help"));
        assertTrue(text.toString().contains("--max-attempts"));
        assertTrue(text.toString().contains("--evil-size"));
    }
}
