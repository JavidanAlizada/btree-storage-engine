import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort

plugins {
    `java-library`
    checkstyle
    jacoco
    id("com.github.spotbugs") version "6.0.26"
}

group = "dev.storage"
version = "0.4.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // Every mini-transaction commit replays its own log group and checks it rebuilds the same page bytes.
    systemProperty("dev.storage.redoCheck", "true")
    // 64 KiB log chunks instead of 4 MiB: the crash tests create hundreds of logs.
    systemProperty("dev.storage.walChunkBytes", "65536")
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

checkstyle {
    toolVersion = "10.20.2"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

spotbugs {
    toolVersion.set("4.8.6")
    effort.set(Effort.MAX)
    reportLevel.set(Confidence.MEDIUM)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.spotbugsMain {
    reports.create("html") {
        required.set(true)
    }
}

tasks.spotbugsTest {
    reports.create("html") {
        required.set(true)
    }
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestReport)
}
