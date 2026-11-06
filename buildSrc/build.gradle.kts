import java.util.*

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    // No external (Maven) dependencies: buildSrc is a separate build, so the root project's
    // allprojects block and its gradle.properties do not reach it — the test stack is declared
    // here, and the shared JUnit version is read from the file rather than duplicated.
    //
    // gradleApi() is implicit when the main build compiles buildSrc, which is why the utilities
    // can import org.gradle.api.* with nothing declared. Declaring it makes `gradle -p buildSrc
    // test` work, which is the only way to run these unit tests on their own.
    implementation(gradleApi())
    val rootProperties = Properties().also { properties ->
        file("../gradle.properties").inputStream().use { stream -> properties.load(stream) }
    }
    val junitVersion = requireNotNull(rootProperties.getProperty("junitVersion")) {
        "junitVersion is not set in ../gradle.properties, which buildSrc needs to compile its tests"
    }
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
