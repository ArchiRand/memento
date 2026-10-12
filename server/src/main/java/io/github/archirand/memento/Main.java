package io.github.archirand.memento;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/**
 * Entry point: no arguments — MCP server; {@code prime} — SessionStart hook; {@code report [--no-open]} — HTML report.
 */
public final class Main
{
    private Main()
    {
    }

    public static void main(String[] args) throws Exception
    {
        // Claude Code reads hook output, tool output and stderr as UTF-8; Java 17 defaults to the platform charset (cp1252 on Windows pipes)
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        String mode = args.length == 0 ? "server" : args[0];
        switch (mode)
        {
            case "server" -> McpServerApp.run(Vault.fromEnv());
            case "prime" ->
            {
                Prime.run(System.in, System.out, Vault.fromEnv(), Clock.systemUTC());
                System.exit(0);
            }
            case "report" -> System.exit(Report.run(Vault.fromEnv(), Clock.systemUTC(), !List.of(args).contains("--no-open")));
            default ->
            {
                System.err.println("usage: java -jar memento.jar [prime | report [--no-open]]");
                System.exit(2);
            }
        }
    }
}
