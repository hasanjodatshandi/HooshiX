import com.github.spotbugs.snom.SpotBugsExtension

plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("com.google.protobuf") version "0.10.0"
    id("com.diffplug.spotless") version "8.9.0"
    id("com.github.spotbugs") version "6.5.9"
}

group = "com.sajtech"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

sourceSets {
    main {
        proto {
            srcDir("../compromised-password-service/src/main/proto")
            srcDir("../notification-service/src/main/proto")
        }
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(platform("io.netty:netty-bom:4.2.16.Final"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.lettuce:lettuce-core:7.5.2.RELEASE")
    implementation("io.grpc:grpc-netty-shaded:1.83.1")
    implementation("io.grpc:grpc-protobuf:1.83.1")
    implementation("io.grpc:grpc-stub:1.83.1")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    runtimeOnly("org.postgresql:postgresql:42.7.13")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    constraints {
        implementation("org.apache.logging.log4j:log4j-api:2.25.5") {
            because("CVE-2026-49844 is fixed in Log4j API 2.25.5")
        }
        implementation("tools.jackson.core:jackson-databind:3.1.5") {
            because("CVE-2026-59889 is fixed in jackson-databind 3.1.5")
        }
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testImplementation("io.cucumber:cucumber-java:7.34.6")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.34.6")
    testImplementation("org.junit.platform:junit-platform-suite")
    testImplementation("io.grpc:grpc-inprocess:1.83.1")
    testImplementation("io.grpc:grpc-testing:1.83.1")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.8"
    }
    plugins {
        maybeCreate("grpc").artifact = "io.grpc:protoc-gen-grpc-java:1.83.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins.maybeCreate("grpc")
        }
    }
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
        target("*.gradle.kts", "*.properties", "src/**/*.proto", "deploy/**/*.yaml", "Dockerfile")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

configure<SpotBugsExtension> {
    toolVersion.set("4.10.3")
    ignoreFailures.set(false)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration", "architecture")
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs Identity integration tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        excludeTestsMatching("com.sajtech.identity.bdd.*")
    }
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

val architectureTest = tasks.register<Test>("architectureTest") {
    description = "Runs Identity architecture tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        excludeTestsMatching("com.sajtech.identity.bdd.*")
    }
    useJUnitPlatform {
        includeTags("architecture")
    }
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest, architectureTest, tasks.named("spotbugsMain"), tasks.named("spotlessCheck"))
}
