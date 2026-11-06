import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a command in a working directory, capturing its combined stdout/stderr.
 * <p>
 * <b>Why extracted:</b> the build script used {@code ProcessBuilder.inheritIO()}. A Gradle
 * daemon has no console, and inheritIO hands the child the daemon's own standard streams,
 * which the Gradle client never forwards to build output. A failing npm script was therefore
 * reported as a bare exit status — {@code Command failed (exit127): npm test} — while the
 * reason for it, {@code /usr/bin/env: 'node': No such file or directory}, went where nobody
 * could read it. Output is captured here: replayed at info level on success, at error level
 * and in the exception message on failure.
 */
public final class CommandRunner {

    /**
     * Cap on stored output. A broken toolchain can spew (npm writes a debug log to stdout),
     * and a Gradle build log full of dependency noise helps nobody. Output is drained past
     * the cap so a child writing to a full pipe cannot deadlock.
     */
    private static final int MAX_STORED_LINES = 200;

    private static final Logger LOG = Logging.getLogger(CommandRunner.class);

    private CommandRunner() {
    }

    /**
     * Exit code and captured output of a finished command.
     *
     * @param exit   process exit code, or -1 when the process could not be started or waited on
     * @param output captured output lines, in order, truncated at {@link #MAX_STORED_LINES}
     */
    public record Result(int exit, List<String> output) {

        public boolean ok() {
            return exit == 0;
        }

        /**
         * First output line, trimmed — npm prints the value queried for on one line.
         */
        public String firstLine() {
            return output.isEmpty() ? "" : output.getFirst().trim();
        }
    }

    /**
     * Run a command, throwing when it exits non-zero.
     *
     * @param workDir directory to run in
     * @param command executable followed by its arguments
     * @param binDir  directory prepended to the child's PATH, or null to inherit the daemon's
     * @throws CommandFailedException with the captured output when the exit code is non-zero
     */
    public static void run(File workDir, List<String> command, Path binDir) {
        Result result = tryRun(workDir, command, binDir);
        if (result.ok()) {
            result.output().forEach(LOG::info);
            return;
        }
        LOG.error("{} failed (exit {}) in {}", command, result.exit(), workDir);
        result.output().forEach(LOG::error);
        throw new CommandFailedException(command, workDir, result);
    }

    /**
     * Run a command and return its result instead of throwing. Used to probe a toolchain,
     * where a failure is one candidate among several and must not end the build.
     */
    public static Result tryRun(File workDir, List<String> command, Path binDir) {
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true); // one stream keeps stdout and stderr interleaved
        if (binDir != null) {
            prependToPath(builder, binDir);
        }
        List<String> output = new ArrayList<>();
        try {
            Process process = builder.start();
            // Drain to EOF before waiting: stopping early would leave a full pipe blocking the child.
            int stored = 0;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (stored < MAX_STORED_LINES) {
                        output.add(line);
                        stored++;
                    } else if (stored == MAX_STORED_LINES) {
                        output.add("... output truncated at " + MAX_STORED_LINES + " lines ...");
                        stored++;
                    }
                }
            }
            return new Result(process.waitFor(), output);
        } catch (IOException e) {
            // A missing executable: the message says which one, so it is the whole report.
            output.add(String.valueOf(e.getMessage()));
            return new Result(-1, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output.add("Interrupted while waiting for " + command.getFirst());
            return new Result(-1, output);
        }
    }

    /**
     * Put the resolved toolchain directory first on the child's PATH so the command, and the
     * processes it starts, resolve the same npm and node that passed the probe. npm's shebang
     * is {@code #!/usr/bin/env node}, so the child needs node on its PATH, not just npm.
     */
    private static void prependToPath(ProcessBuilder builder, Path binDir) {
        Map<String, String> env = builder.environment();
        String existing = env.getOrDefault("PATH", "");
        env.put("PATH", binDir + File.pathSeparator + existing);
    }

    /**
     * Command failed; carries the captured output so the cause reaches the build log.
     */
    public static final class CommandFailedException extends RuntimeException {

        CommandFailedException(List<String> command, File workDir, Result result) {
            super("Command failed (exit " + result.exit() + "): " + String.join(" ", command)
                + " (run in " + workDir + ")\n"
                + String.join("\n", result.output()));
        }
    }
}
