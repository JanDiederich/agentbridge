import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CommandRunnerTest {

    private static final File WORK_DIR = new File(".");

    @Test
    void onlyAZeroExitCodeIsOk() {
        assertTrue(new CommandRunner.Result(0, List.of()).ok());
        assertFalse(new CommandRunner.Result(127, List.of("env: node: No such file")).ok());
    }

    @Test
    void firstLineIsTheTrimmedHeadOfTheOutput() {
        assertEquals("24.16.0", new CommandRunner.Result(0, List.of(" 24.16.0 ", "extra")).firstLine());
        assertEquals("", new CommandRunner.Result(0, List.of()).firstLine());
    }

    @Test
    void failureMessageCarriesTheExitCodeCommandWorkingDirAndOutput() {
        CommandRunner.CommandFailedException failure = new CommandRunner.CommandFailedException(
            List.of("npm", "test"), new File("/project/js-tests"),
            new CommandRunner.Result(127, List.of("/usr/bin/env: 'node': No such file or directory")));

        String message = failure.getMessage();
        assertTrue(message.contains("exit 127"), message);
        assertTrue(message.contains("npm test"), message);
        assertTrue(message.contains("/project/js-tests"), message);
        // The line inheritIO used to swallow is the point of the exception.
        assertTrue(message.contains("/usr/bin/env: 'node': No such file or directory"), message);
    }

    @Test
    void mergedOutputKeepsStdoutAndStderrTogether() {
        assumeFalse(NpmToolchain.isWindows(System.getProperty("os.name")));

        CommandRunner.Result result = CommandRunner.tryRun(
            WORK_DIR, List.of("/bin/sh", "-c", "echo to-stdout; echo to-stderr 1>&2"), null);

        assertTrue(result.ok());
        assertTrue(result.output().contains("to-stdout"), result.output().toString());
        assertTrue(result.output().contains("to-stderr"), result.output().toString());
    }

    @Test
    void failingCommandThrowsWithItsOutputSoTheBuildLogExplainsWhy() {
        assumeFalse(NpmToolchain.isWindows(System.getProperty("os.name")));

        CommandRunner.CommandFailedException failure = assertThrows(
            CommandRunner.CommandFailedException.class,
            () -> CommandRunner.run(WORK_DIR, List.of("/bin/sh", "-c", "echo reason; exit 3"), null));

        assertTrue(failure.getMessage().contains("exit 3"), failure.getMessage());
        assertTrue(failure.getMessage().contains("reason"), failure.getMessage());
    }

    @Test
    void outputIsTruncatedAtTheCapWhileTheChildKeepsDraining() {
        assumeFalse(NpmToolchain.isWindows(System.getProperty("os.name")));

        CommandRunner.Result result = CommandRunner.tryRun(
            WORK_DIR, List.of("/bin/sh", "-c", "seq 1 1500"), null);

        // Returning early would leave a full pipe blocking the child, so the drain must finish.
        assertTrue(result.ok(), result.output().toString());
        assertTrue(result.output().stream().anyMatch(line -> line.contains("output truncated at 200 lines")),
            result.output().toString());
        assertEquals(201, result.output().size(), result.output().toString());
    }

    @Test
    void missingExecutableIsReportedAsAFailedResultNotAnIoException() {
        CommandRunner.Result result = CommandRunner.tryRun(
            WORK_DIR, List.of("/definitely/not/an/executable", "--version"), null);

        assertFalse(result.ok());
        assertTrue(result.output().getFirst().contains("/definitely/not/an/executable"),
            result.output().toString());
    }

    @Test
    void resolvedBinDirectoryIsPrependedToTheChildPathSoItsShebangResolvesNode() {
        assumeFalse(NpmToolchain.isWindows(System.getProperty("os.name")));
        Path binDir = Path.of("/opt/agentbridge-test-bin");

        CommandRunner.Result result = CommandRunner.tryRun(WORK_DIR, List.of("/bin/sh", "-c", "printenv PATH"), binDir);

        assertTrue(result.ok());
        assertTrue(result.firstLine().startsWith("/opt/agentbridge-test-bin:"), result.firstLine());
    }

    @Test
    void pathIsInheritedUnchangedWhenNoBinDirectoryIsGiven() {
        assumeFalse(NpmToolchain.isWindows(System.getProperty("os.name")));
        String daemonPath = System.getenv("PATH");

        CommandRunner.Result result = CommandRunner.tryRun(WORK_DIR, List.of("/bin/sh", "-c", "printenv PATH"), null);

        assertEquals(daemonPath, result.firstLine());
    }
}
