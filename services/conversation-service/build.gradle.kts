import com.github.spotbugs.snom.SpotBugsExtension

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.0"
    id("com.diffplug.spotless") version "8.9.0"
    id("com.github.spotbugs") version "6.5.9"
}

group = "com.sajtech"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

jacoco { toolVersion = "0.8.15" }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql:42.7.13")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    constraints {
        implementation("org.apache.tomcat.embed:tomcat-embed-core:11.0.25") {
            because("Tomcat 11.0.25 is the repository security-fixed baseline")
        }
        implementation("org.apache.tomcat.embed:tomcat-embed-el:11.0.25") {
            because("Align embedded Tomcat modules with the security-fixed core")
        }
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.25") {
            because("Align embedded Tomcat modules with the security-fixed core")
        }
        implementation("org.apache.logging.log4j:log4j-api:2.25.5") {
            because("CVE-2026-49844 is fixed in Log4j API 2.25.5")
        }
        implementation("tools.jackson.core:jackson-databind:3.1.5") {
            because("CVE-2026-59889 is fixed in jackson-databind 3.1.5")
        }
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.36.1")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target("*.gradle.kts", "*.properties", "deploy/**/*.yaml", "Dockerfile")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

configure<SpotBugsExtension> {
    toolVersion.set("4.10.3")
    ignoreFailures.set(false)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

dependencyLocking { lockAllConfigurations() }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test { useJUnitPlatform { excludeTags("integration", "architecture") } }

val integrationTest = tasks.register<Test>("integrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

val architectureTest = tasks.register<Test>("architectureTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("architecture") }
    shouldRunAfter(tasks.test)
}

val jacocoRiskReport = tasks.register<JacocoReport>("jacocoRiskReport") {
    dependsOn(tasks.test, integrationTest)
    executionData(
        layout.buildDirectory.file("jacoco/test.exec"),
        layout.buildDirectory.file("jacoco/integrationTest.exec"),
    )
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
    classDirectories.setFrom(sourceSets.main.get().output)
    reports { xml.required.set(true); html.required.set(true) }
}

val jacocoRiskCoverage = tasks.register<JacocoCoverageVerification>("jacocoRiskCoverage") {
    dependsOn(jacocoRiskReport)
    executionData(
        layout.buildDirectory.file("jacoco/test.exec"),
        layout.buildDirectory.file("jacoco/integrationTest.exec"),
    )
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
    classDirectories.setFrom(sourceSets.main.get().output)
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.75".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.60".toBigDecimal() }
        }
    }
}

tasks.check {
    dependsOn(integrationTest, architectureTest, jacocoRiskCoverage, tasks.named("spotbugsMain"), tasks.named("spotlessCheck"))
}
