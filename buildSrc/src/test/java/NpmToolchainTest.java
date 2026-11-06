import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class NpmToolchainTest {

    /**
     * A home with no version-manager installs, so candidate order is machine-independent.
     */
    private static final Path EMPTY_HOME = Path.of("/agentbridge-test-home");

    @Test
    void npmIsABatchWrapperOnWindowsAndAPlainExecutableElsewhere() {
        assertEquals("npm.cmd", NpmToolchain.npmFileName("Windows 11"));
        assertEquals("npm", NpmToolchain.npmFileName("Linux"));
        assertEquals("npm", NpmToolchain.npmFileName("Mac OS X"));
    }

    @Test
    void unixCommandStartsTheResolvedAbsolutePathBecauseTheDaemonPathLacksNpm() {
        Path npm = Path.of("/home/dev/.nvm/versions/node/v24.16.0/bin/npm");

        List<String> command = NpmToolchain.command("Linux", null, npm, List.of("test", "--reporter=json"));

        assertEquals(List.of(npm.toString(), "test", "--reporter=json"), command);
    }

    @Test
    void windowsCommandRunsNpmUnderCmdBecauseProcessBuilderCannotStartABatchFile() {
        Path npm = Path.of("C:\\Program Files\\nodejs\\npm.cmd");

        List<String> command = NpmToolchain.command("Windows 11", null, npm, List.of("run", "build"));

        // The bare name resolves against the PATH the child is given, which starts with the
        // resolved bin directory, so quoting the (space-containing) path into cmd is unnecessary.
        assertEquals(List.of("cmd.exe", "/d", "/c", "npm.cmd", "run", "build"), command);
    }

    @Test
    void windowsCommandUsesComSpecWhenItIsSet() {
        List<String> command = NpmToolchain.command(
            "Windows 11", "C:\\Windows\\System32\\cmd.exe", Path.of("C:\\nodejs\\npm.cmd"), List.of("test"));

        assertEquals("C:\\Windows\\System32\\cmd.exe", command.getFirst());
    }

    @Test
    void blankComSpecFallsBackToCmdExe() {
        List<String> command = NpmToolchain.command(
            "Windows 11", "  ", Path.of("C:\\nodejs\\npm.cmd"), List.of("test"));

        assertEquals("cmd.exe", command.getFirst());
    }

    @Test
    void configuredBinDirIsTriedBeforeAnythingOnThePath() {
        List<Path> dirs = NpmToolchain.candidateBinDirs(
            "/opt/pin/node/bin", "/usr/local/bin:/usr/bin", EMPTY_HOME, "Linux", null);

        assertEquals(Path.of("/opt/pin/node/bin"), dirs.getFirst());
        assertTrue(dirs.contains(Path.of("/usr/local/bin")));
        assertTrue(dirs.contains(Path.of("/usr/bin")));
    }

    @Test
    void nvmShellVariableIsTriedBeforeThePathBecauseItNamesTheActiveInstall() {
        Path nvmBin = Path.of("/home/dev/.nvm/versions/node/v24.16.0/bin");

        List<Path> dirs = NpmToolchain.candidateBinDirs(null, "/usr/bin", EMPTY_HOME, "Linux", nvmBin.toString());

        assertEquals(nvmBin, dirs.getFirst());
        assertTrue(dirs.indexOf(nvmBin) < dirs.indexOf(Path.of("/usr/bin")));
    }

    @Test
    void blankAndRepeatedPathEntriesAreDropped() {
        List<Path> dirs = NpmToolchain.candidateBinDirs(
            "  ", ":/usr/local/bin::/usr/local/bin:", EMPTY_HOME, "Linux", "");

        assertEquals(List.of(Path.of("/usr/local/bin")), dirs.subList(0, 1));
        assertEquals(1, dirs.stream().filter(Path.of("/usr/local/bin")::equals).count());
        assertFalse(dirs.contains(Path.of("")));
    }

    @Test
    void systemBinDirsCoverTheLocalAndDistributionLocations() {
        assumeFalse(NpmToolchain.isWindows(System.getProperty("os.name")));

        List<String> dirs = NpmToolchain.systemBinDirs("Linux");

        assertTrue(dirs.contains("/usr/local/bin"), dirs.toString());
        assertTrue(dirs.contains("/usr/bin"), dirs.toString());
    }

    @Test
    void newestNodeVersionComesFirstAndSegmentCountsDoNotMatter() {
        List<String> versions = List.of("v22.11.0", "v24.16.0", "v24.9.0", "v24");
        Comparator<String> newestFirst = NpmToolchain::compareVersions;

        List<String> sorted = versions.stream().sorted(newestFirst.reversed()).toList();

        // Compared as numbers, 24.16.0 outranks 24.9.0; compared as strings it would not.
        assertEquals(List.of("v24.16.0", "v24.9.0", "v24", "v22.11.0"), sorted);
        assertEquals(0, NpmToolchain.compareVersions("v24", "v24.0.0"));
    }

    @Test
    void preReleaseSuffixDoesNotBreakVersionComparison() {
        assertTrue(NpmToolchain.compareVersions("v24.16.0-nightly", "v24.15.0") > 0);
    }

    @Test
    void dependenciesAreInstalledWhenNodeModulesIsAbsent() {
        assertTrue(NpmToolchain.needsInstall(false, 1_000L, 0L));
    }

    @Test
    void dependenciesAreReinstalledWhenTheLockFileIsNewerThanNodeModules() {
        // A dependency bump commit leaves a lock file newer than an existing node_modules.
        assertTrue(NpmToolchain.needsInstall(true, 2_000L, 1_000L));
    }

    @Test
    void dependenciesAreNotReinstalledWhenNodeModulesIsFresh() {
        assertFalse(NpmToolchain.needsInstall(true, 1_000L, 2_000L));
        assertFalse(NpmToolchain.needsInstall(true, 1_000L, 1_000L));
    }

    @Test
    void installMirrorsCiWhenThereIsALockFile() {
        assertEquals(List.of("ci", "--silent", "--ignore-scripts"), NpmToolchain.installArgs(true));
    }

    @Test
    void installWithoutALockFileCannotUseCi() {
        assertEquals(List.of("install", "--ignore-scripts"), NpmToolchain.installArgs(false));
    }

    @Test
    void unresolvableNpmMessageNamesThePropertyTheSkipFlagAndTheProbedFiles() {
        String message = NpmToolchain.unusableNpmMessage(
            "/opt/pin/node/bin", List.of("/usr/local/bin/npm", "/usr/bin/npm"));

        assertTrue(message.contains("/usr/local/bin/npm"), message);
        assertTrue(message.contains("npm.dir="), message);
        assertTrue(message.contains("-x :plugin-core:jsTest"), message);
    }

    @Test
    void unresolvableNpmMessageSaysSoWhenNothingWasFoundAtAll() {
        String message = NpmToolchain.unusableNpmMessage(null, List.of());

        assertTrue(message.contains("nothing (no npm file found)"), message);
        assertFalse(message.contains("npm.dir="), message);
    }
}
