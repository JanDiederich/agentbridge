import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build scripts gate classpath requests on these numbers (2025.3 = 253 has JCEF and lucene on
 * the auto-included classpath; 2026.1 = 261 needs the lucene-common library requested; 2026.2 = 262
 * needs JCEF and the VCS modules requested; 2026.3 = 263 also needs the VCS implementation
 * modules), so a wrong number compiles the plugin against a classpath the release does not use —
 * an unresolved-reference wall, not a warning. Every form the properties accept is asserted here.
 */
class IntelliJTargetBuildTest {

    @Test
    void releaseVersionsMapToTheirPlatformBuildNumbers() {
        assertEquals(253, IntelliJTargetBuild.fromVersionString("2025.3", "intellijPlatformVersion"));
        assertEquals(253, IntelliJTargetBuild.fromVersionString("2025.3.1.1", "intellijPlatformVersion"));
        assertEquals(261, IntelliJTargetBuild.fromVersionString("2026.1.3", "intellijPlatformVersion"));
        assertEquals(262, IntelliJTargetBuild.fromVersionString("2026.2", "intellijPlatformVersion"));
        assertEquals(263, IntelliJTargetBuild.fromVersionString("2026.3", "intellijPlatformVersion"));
    }

    @Test
    void anIdePrefixIsIgnoredBecauseTheTargetBuildNumberIsWhatMatters() {
        assertEquals(253, IntelliJTargetBuild.fromVersionString("IU-2025.3", "intellijPlatformVersion"));
        assertEquals(261, IntelliJTargetBuild.fromVersionString("PY-2026.1", "intellijPlatformVersion"));
        assertEquals(261, IntelliJTargetBuild.fromVersionString("iu-2026.1.3", "intellijPlatformVersion"));
        // The data-services IDEs publish editions as a suffix: IU-2026.1.3.U202.
        assertEquals(261, IntelliJTargetBuild.fromVersionString("IU-2026.1.3.U202", "intellijPlatformVersion"));
    }

    @Test
    void bareBuildNumbersIncludingEapsAreTakenAsGiven() {
        assertEquals(253, IntelliJTargetBuild.fromVersionString("253.6.1", "intellijPlatformVersion"));
        assertEquals(262, IntelliJTargetBuild.fromVersionString("IU-262.8665.258", "intellijPlatformVersion"));
        assertEquals(263, IntelliJTargetBuild.fromVersionString("263.7307.44-EAP", "intellijPlatformVersion"));
        assertEquals(263, IntelliJTargetBuild.fromVersionString("IU-263.7307.200", "intellijPlatformVersion"));
    }

    @Test
    void aBareYearIsThatYearsFirstRelease() {
        assertEquals(261, IntelliJTargetBuild.fromVersionString("2026", "intellijPlatformVersion"));
        assertEquals(261, IntelliJTargetBuild.fromVersionString("IU-2026", "intellijPlatformVersion"));
    }

    @Test
    void futureReleasesAreAcceptedAndKeepTheModernLayoutGates() {
        // 2027.x and beyond use the same calendar scheme, so newer IDEs must not need a build edit.
        assertEquals(271, IntelliJTargetBuild.fromVersionString("2027.1", "intellijPlatformVersion"));
        assertEquals(273, IntelliJTargetBuild.fromVersionString("IU-2027.3", "intellijPlatformVersion"));
    }

    @Test
    void aLocalIdeBuildFileNamesItsBuild() {
        assertEquals(263, IntelliJTargetBuild.fromBuildFile("IU-263.7307.200", "/opt/idea/build.txt"));
        assertEquals(261, IntelliJTargetBuild.fromBuildFile("IC-261.25134.95\n", "/opt/idea/build.txt"));
    }

    @Test
    void aVersionThatNamesNoBuildFailsNamingThePropertyNameAndValue() {
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> IntelliJTargetBuild.fromVersionString("latest", "intellijPlatformVersion"));

        assertTrue(failure.getMessage().contains("intellijPlatformVersion"), failure.getMessage());
        assertTrue(failure.getMessage().contains("latest"), failure.getMessage());
        // The message has to say what to write instead, or the build stops at a stack trace.
        assertTrue(failure.getMessage().contains("2026.1.3"), failure.getMessage());
        assertTrue(failure.getMessage().contains("localPath"), failure.getMessage());
    }

    @Test
    void anEmptyVersionFailsInsteadOfInventingATarget() {
        assertThrows(IllegalStateException.class,
            () -> IntelliJTargetBuild.fromVersionString("", "intellijPlatformVersion"));
        assertThrows(IllegalStateException.class,
            () -> IntelliJTargetBuild.fromVersionString(null, "intellijPlatformVersion"));
    }

    @Test
    void aNonNumericFeatureNumberFailsInsteadOfFallingBackToTheFirstRelease() {
        assertThrows(IllegalStateException.class,
            () -> IntelliJTargetBuild.fromVersionString("2026.x", "intellijPlatformVersion"));
        assertThrows(IllegalStateException.class,
            () -> IntelliJTargetBuild.fromVersionString("2026.", "intellijPlatformVersion"));
        assertThrows(IllegalStateException.class,
            () -> IntelliJTargetBuild.fromVersionString("abc", "intellijPlatformVersion"));
    }

    @Test
    void aTargetBelowThePluginSinceBuildIsRejectedNamingBothNumbers() {
        // 2024.3 (243) predates since-build 253: building against it is a typo, not a layout gate.
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> IntelliJTargetBuild.fromVersionString("2024.3", "intellijPlatformVersion"));

        assertTrue(failure.getMessage().contains("243"), failure.getMessage());
        assertTrue(failure.getMessage().contains("253"), failure.getMessage());
        assertThrows(IllegalStateException.class,
            () -> IntelliJTargetBuild.fromVersionString("251.25134.95", "intellijPlatformVersion"));
    }

    @Test
    void aLocalIdeOlderThanThePluginSinceBuildIsRejected() {
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> IntelliJTargetBuild.fromBuildFile("IU-251.25134.95", "/opt/idea-2025.1/build.txt"));

        assertTrue(failure.getMessage().contains("251"), failure.getMessage());
        assertTrue(failure.getMessage().contains("253"), failure.getMessage());
    }

    @Test
    void anUnreadableBuildFileFailsWithItsLocationAndContents() {
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> IntelliJTargetBuild.fromBuildFile("IntelliJ IDEA 2026.3", "/opt/idea/build.txt"));

        assertTrue(failure.getMessage().contains("/opt/idea/build.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("IU-263.7307.200"), failure.getMessage());
        assertThrows(IllegalStateException.class,
            () -> IntelliJTargetBuild.fromBuildFile(null, "/opt/idea/build.txt"));
    }
}
