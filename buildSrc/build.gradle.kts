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
    // No external dependencies needed for these utilities.
    // buildSrc is a separate build: the root project's allprojects block and its
    // gradle.properties do not reach it, so the test stack is declared here and the shared
    // JUnit version is read from the file rather than duplicated.
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
