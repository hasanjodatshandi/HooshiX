import com.github.spotbugs.snom.SpotBugsExtension

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.0"
    id("info.solidsoft.pitest") version "1.19.0"
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

jacoco { toolVersion = "0.8.15" }

pitest {
    pitestVersion.set("1.22.1")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(
        setOf(
            "com.sajtech.identity.infrastructure.security.challenge.HmacPasswordRecoverySecret",
            "com.sajtech.identity.infrastructure.security.externalidentity.AesGcmExternalIdentityResultCrypto",
            "com.sajtech.identity.application.erasure.usecase.ParticipantErasureUseCase",
        )
    )
    targetTests.set(
        setOf(
            "com.sajtech.identity.infrastructure.security.challenge.HmacPasswordRecoverySecretTest",
            "com.sajtech.identity.infrastructure.security.externalidentity.AesGcmExternalIdentityResultCryptoTest",
            "com.sajtech.identity.application.erasure.usecase.ParticipantErasureUseCaseTest",
        )
    )
    mutators.set(setOf("DEFAULTS"))
    threads.set(2)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    failWhenNoMutations.set(true)
    coverageThreshold.set(95)
    mutationThreshold.set(95)
    testStrengthThreshold.set(95)
}


dependencies {
    implementation("com.sajtech.hooshix:protobuf-contracts:1.8.0")
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(platform("io.netty:netty-bom:4.2.16.Final"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
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
        implementation("at.yawk.lz4:lz4-java:1.11.1") {
            because("GHSA-xx22-p4ch-683r is fixed in lz4-java 1.11.1")
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
        artifact = "com.google.protobuf:protoc:4.34.2"
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

val jacocoRiskReport = tasks.register<JacocoReport>("jacocoRiskReport") {
    description = "Generates combined unit and integration coverage evidence."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.test, integrationTest)
    executionData(
        layout.buildDirectory.file("jacoco/test.exec"),
        layout.buildDirectory.file("jacoco/integrationTest.exec"),
    )
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
    classDirectories.setFrom(sourceSets.main.get().output)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

val jacocoRiskCoverage = tasks.register<JacocoCoverageVerification>("jacocoRiskCoverage") {
    description = "Enforces the measured service baseline and critical identity coverage."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(jacocoRiskReport)
    executionData(
        layout.buildDirectory.file("jacoco/test.exec"),
        layout.buildDirectory.file("jacoco/integrationTest.exec"),
    )
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
    classDirectories.setFrom(sourceSets.main.get().output)
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.48".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.35".toBigDecimal() }
        }
        rule {
            element = "CLASS"
            includes = listOf(
                "com.sajtech.identity.infrastructure.security.session.HmacSessionCredential",
                "com.sajtech.identity.infrastructure.security.mfa.JcaMfaCryptography",
                "com.sajtech.identity.infrastructure.security.jwt.RsaJwtAccessTokenSigner",
                "com.sajtech.identity.infrastructure.security.externalidentity.AesGcmExternalIdentityResultCrypto",
                "com.sajtech.identity.infrastructure.quota.ClockSafetyGuard",
                "com.sajtech.identity.infrastructure.security.challenge.HmacChallengeSecret",
                "com.sajtech.identity.infrastructure.security.challenge.HmacPasswordRecoverySecret",
            )
            limit { counter = "LINE"; minimum = "0.82".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.50".toBigDecimal() }
        }
        rule {
            element = "CLASS"
            includes = listOf(
                "com.sajtech.identity.application.erasure.usecase.ErasureUseCase",
                "com.sajtech.identity.application.erasure.usecase.ParticipantErasureUseCase",
            )
            limit { counter = "LINE"; minimum = "0.80".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.52".toBigDecimal() }
        }
    }
}

tasks.check {
    dependsOn(
        integrationTest,
        architectureTest,
        jacocoRiskCoverage,
        tasks.named("spotbugsMain"),
        tasks.named("spotlessCheck"),
    )
}
