import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Finds a usable npm and builds npm command lines for the build scripts.
 * <p>
 * <b>Why extracted:</b> a Gradle daemon inherits the environment of the process that launched
 * it — the IDE — not a login shell, so its PATH is not the developer's shell PATH. Two
 * failures follow on Linux and macOS. Node installed with nvm is invisible, because nvm adds
 * itself to PATH from interactive shell init only. And the npm that <em>is</em> on PATH is a
 * JavaScript file whose shebang is {@code #!/usr/bin/env node}, so it starts, finds no node,
 * and dies: the task sees only "Command failed (exit127)".
 * <p>
 * Candidate bin directories are therefore collected in order — an explicit {@code npm.dir}
 * Gradle property, {@code $NVM_BIN}, the daemon PATH, version-manager installs, system install
 * directories — and each candidate is <em>probed</em> with {@code npm --version}, which proves
 * npm and the node it resolves both work. That is why a broken npm earlier in the list is
 * skipped rather than trusted. The bin directory of the winner is prepended to the child's
 * PATH, and the choice is logged, never made silently. When nothing works the message lists
 * what was probed and the ways to fix it, because guessing at a node install would replace an
 * actionable failure with an unexplained one.
 */
public final class NpmToolchain {

    /**
     * Gradle property naming the directory that contains npm, for machines where node lives
     * under a version manager the daemon cannot see (set it in {@code ~/.gradle/gradle.properties}).
     */
    public static final String BIN_DIR_PROPERTY = "npm.dir";

    /**
     * Node major version CI runs the JS build and tests on; only used in the failure message.
     */
    private static final String CI_NODE_VERSION = "24";

    private static final Logger LOG = Logging.getLogger(NpmToolchain.class);

    private final Path npm;
    private final String version;

    private NpmToolchain(Path npm, String version) {
        this.npm = npm;
        this.version = version;
    }

    /**
     * Resolve the npm to run, probing candidates until one works.
     *
     * @param configuredBinDir value of {@link #BIN_DIR_PROPERTY}, or null when unset
     * @throws IllegalStateException when no candidate runs
     */
    public static NpmToolchain resolve(String configuredBinDir) {
        String osName = System.getProperty("os.name");
        String npmName = npmFileName(osName);
        List<String> probed = new ArrayList<>();
        for (Path binDir : candidateBinDirs(configuredBinDir, System.getenv("PATH"),
            Path.of(System.getProperty("user.home")), osName, System.getenv("NVM_BIN"))) {
            Path executable = binDir.resolve(npmName);
            if (!Files.isRegularFile(executable)) {
                continue;
            }
            probed.add(executable.toString());
            CommandRunner.Result probe = CommandRunner.tryRun(
                binDir.toFile(), List.of(executable.toString(), "--version"), binDir);
            if (probe.ok()) {
                String version = probe.firstLine();
                LOG.lifecycle("npm toolchain: npm {} at {}", version, executable);
                return new NpmToolchain(executable, version.isEmpty() ? "unknown" : version);
            }
            // Typically an npm whose shebang cannot find node on this daemon's PATH.
            LOG.info("Skipping npm candidate {}: exit {} {}", executable, probe.exit(), probe.firstLine());
        }
        throw new IllegalStateException(unusableNpmMessage(configuredBinDir, probed));
    }

    /**
     * Human-readable identity of the resolved toolchain; written into install stamps.
     */
    public String describe() {
        return "npm " + version + " (" + npm + ")";
    }

    /**
     * Run npm in a package directory.
     *
     * @param workDir directory npm runs in; its node_modules is where npm looks for the packages
     * @param args    npm subcommand and its arguments, e.g. {@code ["test"]}
     */
    public void run(File workDir, List<String> args) {
        CommandRunner.run(workDir, command(System.getProperty("os.name"), System.getenv("ComSpec"), npm, args),
            npm.getParent());
    }

    /**
     * Install a package directory's dependencies when they are missing or older than its lock
     * file, so a Gradle build works on a fresh clone. CI installs with npm before Gradle runs,
     * and the check below then skips the install instead of repeating it.
     *
     * @param packageDir directory containing package.json and, once installed, node_modules
     */
    public void ensureDependencies(File packageDir) {
        File nodeModules = new File(packageDir, "node_modules");
        File packageLock = new File(packageDir, "package-lock.json");
        if (!needsInstall(nodeModules.isDirectory(), packageLock.lastModified(), nodeModules.lastModified())) {
            return;
        }
        LOG.lifecycle("Installing npm dependencies in {} ({})", packageDir, installArgs(packageLock.isFile()));
        try {
            run(packageDir, installArgs(packageLock.isFile()));
        } catch (CommandRunner.CommandFailedException e) {
            throw new IllegalStateException("npm failed to install dependencies for " + packageDir
                + ". Delete " + nodeModules + " and rerun the build.\nCaused by: " + e.getMessage(), e);
        }
    }

    // --- pure decisions, unit-tested in isolation ---

    /**
     * npm on Windows is a batch wrapper that a process cannot be started from directly.
     */
    static String npmFileName(String osName) {
        return isWindows(osName) ? "npm.cmd" : "npm";
    }

    static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Ordered, de-duplicated bin directories to look for npm in. Explicit configuration comes
     * first so a developer's choice beats whatever the daemon happens to have on PATH.
     *
     * @param configuredBinDir value of {@link #BIN_DIR_PROPERTY}, may be null or blank
     * @param pathEnv          the daemon's PATH, may be null
     * @param home             home directory used to expand version-manager installs
     * @param osName           {@code os.name} system property
     * @param nvmBin           value of {@code NVM_BIN}, may be null
     */
    static List<Path> candidateBinDirs(String configuredBinDir, String pathEnv, Path home,
                                       String osName, String nvmBin) {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        addDir(dirs, configuredBinDir);
        addDir(dirs, nvmBin);
        if (pathEnv != null) {
            for (String entry : pathEnv.split(File.pathSeparator)) {
                addDir(dirs, entry);
            }
        }
        dirs.addAll(versionManagerBinDirs(home));
        for (String dir : systemBinDirs(osName)) {
            addDir(dirs, dir);
        }
        return List.copyOf(dirs);
    }

    /**
     * Directories that can hold a node install started by a version manager, newest first.
     * The layouts differ per manager, so each is spelled out: nvm on Unix puts node in a
     * {@code bin} directory inside the version directory, nvm on Windows puts it in the
     * version directory itself.
     */
    static List<Path> versionManagerBinDirs(Path home) {
        List<Path> dirs = new ArrayList<>(newestFirst(home.resolve(".nvm/versions/node"), "bin"));
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            dirs.addAll(newestFirst(Path.of(appData, "nvm"), null));
        }
        // volta, asdf and n keep their node under home.
        dirs.add(home.resolve(".volta/bin"));
        dirs.addAll(newestFirst(home.resolve(".asdf/installs/nodejs"), "bin"));
        dirs.add(home.resolve("n/bin"));
        return dirs;
    }

    /**
     * Distribution-provided node locations, tried after the developer's own installs.
     */
    static List<String> systemBinDirs(String osName) {
        if (isWindows(osName)) {
            List<String> dirs = new ArrayList<>();
            for (String envName : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")) {
                String programFiles = System.getenv(envName);
                if (programFiles != null) {
                    dirs.add(Path.of(programFiles, "nodejs").toString());
                }
            }
            return dirs;
        }
        return List.of("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin");
    }

    /**
     * Decide whether dependencies must be installed.
     *
     * @param nodeModulesPresent  true when node_modules exists as a directory
     * @param packageLockModified mtime of package-lock.json, 0 when it does not exist
     * @param nodeModulesModified mtime of node_modules, 0 when it does not exist
     */
    static boolean needsInstall(boolean nodeModulesPresent, long packageLockModified, long nodeModulesModified) {
        if (!nodeModulesPresent) {
            return true;
        }
        // A lock file newer than node_modules means it was installed from an older lock,
        // which is what a dependency bump commit (dependabot, npm update) leaves behind.
        return packageLockModified > nodeModulesModified;
    }

    /**
     * Install arguments, mirroring CI: install exactly what the lock file pins, and run no
     * lifecycle scripts, which is the mode CI installs in.
     */
    static List<String> installArgs(boolean hasPackageLock) {
        return hasPackageLock
            ? List.of("ci", "--silent", "--ignore-scripts")
            : List.of("install", "--ignore-scripts");
    }

    /**
     * Build the command line for a single npm invocation.
     * <p>
     * On Unix the absolute path is used, because the daemon's PATH may not contain npm at all.
     * On Windows npm is a {@code .cmd}, which {@link ProcessBuilder} cannot start directly, so
     * it runs under cmd.exe. cmd resolves bare program names from the PATH it is given, and the
     * resolved bin directory is prepended to that PATH, so the plain name still selects the npm
     * that was probed. It also avoids quoting a path that may contain spaces into cmd's
     * argument parser, which needs nested quotes to survive.
     *
     * @param osName  {@code os.name} system property
     * @param comSpec value of {@code ComSpec}, may be null (falls back to cmd.exe)
     * @param npm     absolute path to the npm executable chosen by {@link #resolve(String)}
     * @param args    npm subcommand and its arguments
     */
    static List<String> command(String osName, String comSpec, Path npm, List<String> args) {
        if (!isWindows(osName)) {
            return Stream.concat(Stream.of(npm.toString()), args.stream()).toList();
        }
        String processor = (comSpec == null || comSpec.isBlank()) ? "cmd.exe" : comSpec;
        return Stream.concat(Stream.of(processor, "/d", "/c", npmFileName(osName)), args.stream()).toList();
    }

    /**
     * Compare version directory names such as {@code v24.16.0} segment by segment numerically.
     */
    static int compareVersions(String a, String b) {
        String[] left = segments(a);
        String[] right = segments(b);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";
            int byNumber = Long.compare(asNumber(l), asNumber(r));
            if (byNumber != 0) {
                return byNumber;
            }
        }
        return 0;
    }

    /**
     * Failure text for an unresolvable toolchain: what was tried, and the ways to fix it.
     */
    static String unusableNpmMessage(String configuredBinDir, List<String> probed) {
        String tried = probed.isEmpty() ? "nothing (no npm file found)" : String.join(", ", probed);
        return "No working npm found. Probed: " + tried + "."
            + (configuredBinDir == null || configuredBinDir.isBlank() ? ""
            : " " + BIN_DIR_PROPERTY + "=" + configuredBinDir + " was set and did not work.")
            + " A Gradle daemon launched from the IDE does not inherit a login shell's PATH,"
            + " so node installed with a version manager is invisible to it."
            + " Fix it by installing Node.js " + CI_NODE_VERSION + " (the version CI uses) on PATH,"
            + " starting the IDE from a terminal so it inherits the shell environment, or setting"
            + " the Gradle property " + BIN_DIR_PROPERTY + "=<directory containing npm> in"
            + " ~/.gradle/gradle.properties. To skip only the JS tests, add -x :plugin-core:jsTest.";
    }

    /**
     * Add a non-blank path to the set, keeping insertion order.
     */
    private static void addDir(LinkedHashSet<Path> dirs, String path) {
        if (path != null && !path.isBlank()) {
            dirs.add(Path.of(path.trim()));
        }
    }

    /**
     * List each version directory under {@code parent}, newest first, as the bin directory
     * inside it.
     *
     * @param binSubDir bin directory name, or null when the version directory is the bin
     *                  directory itself (nvm for Windows puts node.exe there)
     */
    private static List<Path> newestFirst(Path parent, String binSubDir) {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        List<Path> versions = new ArrayList<>();
        try (Stream<Path> children = Files.list(parent)) {
            children.filter(Files::isDirectory).forEach(versions::add);
        } catch (IOException e) {
            LOG.info("Cannot list {} for node installs: {}", parent, e.getMessage());
            return List.of();
        }
        versions.sort(Comparator.comparing((Path p) -> p.getFileName().toString(),
            NpmToolchain::compareVersions).reversed());
        return versions.stream()
            .map(version -> binSubDir == null ? version : version.resolve(binSubDir))
            .toList();
    }

    private static String[] segments(String version) {
        return version.replaceAll("^\\D*", "").split("\\D+");
    }

    /**
     * Version segment as a number; a suffix like {@code nightly} sorts as zero.
     */
    private static long asNumber(String segment) {
        return segment.chars().allMatch(Character::isDigit) ? Long.parseLong(segment) : 0L;
    }
}
