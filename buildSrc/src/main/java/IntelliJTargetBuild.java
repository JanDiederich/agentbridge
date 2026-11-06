import java.util.Locale;

/**
 * Resolves the IntelliJ <em>build number</em> (253, 261, 263 …) that the plugin modules are built
 * against, so build scripts can gate classpath requests on the platform layout that release uses.
 * <p>
 * <b>Why extracted:</b> the platform's jar layout changed in 2026.1 (lucene-common moved from
 * {@code lib/modules/} to {@code lib/}), again in 2026.2 (JCEF and the VCS product modules stopped
 * being auto-included on the boot classpath) and again in 2026.3 (the VCS log implementation
 * moved into a bundled plugin). A request that is required on one of those releases is a hard
 * failure on another: {@code bundledPlugin("com.intellij.modules.jcef")} and
 * {@code bundledLibrary("lib/intellij.libraries.lucene.common.jar")} resolve on 2026.1+ and fail
 * to resolve on 2025.3, where the same classes ship in the auto-included jars. The gates therefore
 * cannot be unconditional, and a version <em>string</em> is not enough to apply them: "2025.3",
 * "IU-2025.3.1.1" and "253.6.1" all mean build 253, and a local IDE install is identified by a
 * path, not a version. Every accepted form has to map to one number, which is exactly the kind of
 * input-accepting logic that belongs in a unit-tested class rather than inline in three build
 * scripts.
 * <p>
 * An unrecognized value is reported as a failure naming the property and the value, never coerced:
 * a silently defaulted target build number would compile the plugin against a classpath nobody
 * asked for.
 */
public final class IntelliJTargetBuild {

    /**
     * Lowest supported target (2025.3, the plugin's {@code since-build}); a parsed number below
     * this is reported as a typo, because the plugin cannot be built against it.
     */
    private static final int MIN_SUPPORTED = 253;

    /**
     * Highest target this class interprets. Later releases use the same calendar scheme, so a
     * number above this is accepted and treated as the modern layout.
     */
    private static final int MAX_SUPPORTED = 999;

    private IntelliJTargetBuild() {
    }

    /**
     * The build number of the platform a local IDE installation is, read from its
     * {@code build.txt} (one line, such as {@code IU-263.7307.200}).
     *
     * @param buildFileContents the raw contents of {@code <localPath>/build.txt}
     * @param buildFileLocation where it was read from, quoted in the failure message
     * @throws IllegalStateException when the contents are not a build number, or name an IDE older
     *                               than the plugin's {@code since-build}
     */
    public static int fromBuildFile(String buildFileContents, String buildFileLocation) {
        String contents = buildFileContents == null ? "" : buildFileContents.strip();
        int build = parseBuildNumber(contents);
        if (build < 0) {
            throw new IllegalStateException(
                "Cannot read an IntelliJ build number from " + buildFileLocation + " (contents: \"" + contents
                    + "\"). Expected a line like \"IU-263.7307.200\".");
        }
        if (build < MIN_SUPPORTED) {
            throw new IllegalStateException(
                buildFileLocation + " is IntelliJ build " + build + ", older than the plugin's since-build "
                    + MIN_SUPPORTED + ". Point intellijPlatform.localPath at IntelliJ " + MIN_SUPPORTED
                    + " (2025.3) or a newer IDE.");
        }
        return build;
    }

    /**
     * The build number a Gradle IDE version string names. {@code 2025.3}, {@code IU-2025.3.1.1},
     * {@code PY-2026.1}, {@code 2026.3} and the bare build-number forms {@code 253.6.1} and
     * {@code 263.7307.44-EAP} are all accepted. A bare year ({@code 2026}) names that year's first
     * release, the way the platform's release channels address it.
     *
     * @param version         version string, with or without a product prefix
     * @param versionProperty property name, quoted in the failure message
     * @throws IllegalStateException when the value names no supported target
     */
    public static int fromVersionString(String version, String versionProperty) {
        String value = version == null ? "" : version.strip();
        int build = parseBuildNumber(value);
        if (build >= 0) {
            return requireSupported(build, versionProperty, value);
        }
        throw new IllegalStateException(
            "Cannot read an IntelliJ build number from " + versionProperty + "=\"" + value + "\". Use a release "
                + "version such as 2026.1.3, 2025.3 or 2026.3, an IDE-prefixed form such as IU-2025.3, a build "
                + "number such as 253.6.1 or 263.7307.44-EAP, or set intellijPlatform.localPath to an IDE "
                + "installation.");
    }

    /**
     * Parse the leading version token of {@code value}, ignoring an {@code IU-}/{@code PY-}/…
     * product prefix.
     *
     * @return the build number, or -1 when the value carries none
     */
    static int parseBuildNumber(String value) {
        String[] parts = stripProductPrefix(value).split("[.\\-]", 3);
        if (parts.length == 0 || parts[0].isEmpty()) {
            return -1;
        }
        int minor = parseMinor(parts);
        if (minor < 0) {
            return -1;
        }
        String head = parts[0];
        if (isNumber(head, 3)) {
            return buildNumberForm(head);
        }
        if (isNumber(head, 4)) {
            return calendarForm(Integer.parseInt(head), minor);
        }
        return -1;
    }

    /**
     * @return the release's feature number, 0 when the version carries none, -1 when malformed
     */
    private static int parseMinor(String[] parts) {
        if (parts.length < 2) {
            return 0;
        }
        return isAllDigits(parts[1]) ? Integer.parseInt(parts[1]) : -1;
    }

    /**
     * @return the build number of a bare build-number version such as {@code 253.6.1}
     */
    private static int buildNumberForm(String head) {
        int build = Integer.parseInt(head);
        return build >= 100 && build <= 999 ? build : -1;
    }

    /**
     * Map a calendar version to a build number: 20xx.y is (year - 2000) * 10 + y, so 2025.3 → 253
     * and 2026.3 → 263. A bare year is that year's first release (2026 → 261), as the release
     * channels address it.
     */
    private static int calendarForm(int year, int minor) {
        if (year < 2024 || year > 2099 || minor > 9) {
            return -1;
        }
        return (year % 100) * 10 + Math.max(minor, 1);
    }

    private static int requireSupported(int build, String versionProperty, String value) {
        if (build < MIN_SUPPORTED || build > MAX_SUPPORTED) {
            throw new IllegalStateException(
                versionProperty + "=\"" + value + "\" names IntelliJ build " + build + ", outside the supported "
                    + "range " + MIN_SUPPORTED + ".." + MAX_SUPPORTED + " (the plugin's since-build is "
                    + MIN_SUPPORTED + ").");
        }
        return build;
    }

    /**
     * Drop a leading product code and dash: {@code IU-2025.3} → {@code 2025.3}. Lowercase prefixes
     * are accepted because shells and IDE configs write the property both ways.
     */
    private static String stripProductPrefix(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        int dash = upper.indexOf('-');
        if (dash > 0 && dash <= 3 && isAllLetters(upper.substring(0, dash))) {
            return value.substring(dash + 1);
        }
        return value;
    }

    private static boolean isNumber(String text, int length) {
        return text.length() == length && isAllDigits(text);
    }

    private static boolean isAllDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isDigit);
    }

    private static boolean isAllLetters(String text) {
        return text.chars().allMatch(c -> c >= 'A' && c <= 'Z');
    }
}
