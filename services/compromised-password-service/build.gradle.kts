import com.github.spotbugs.snom.SpotBugsExtension

plugins {
    java
    jacoco
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

jacoco { toolVersion = "0.8.15" }

dependencies {
    implementation("com.sajtech.hooshix:protobuf-contracts:1.8.0")
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.grpc:grpc-netty-shaded:1.83.1")
    implementation("io.grpc:grpc-protobuf:1.83.1")
    implementation("io.grpc:grpc-stub:1.83.1")
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
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
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testImplementation("io.grpc:grpc-inprocess:1.83.1")
    testImplementation("io.grpc:grpc-testing:1.83.1")
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
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration", "architecture")
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

val architectureTest = tasks.register<Test>("architectureTest") {
    description = "Runs architecture tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
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
    description = "Enforces the measured service baseline and critical dataset coverage."
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
            limit { counter = "LINE"; minimum = "0.78".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.58".toBigDecimal() }
        }
        rule {
            element = "CLASS"
            includes = listOf(
                "com.sajtech.compromisedpassword.application.lookup.usecase.LookupCompromisedPasswordsUseCase",
                "com.sajtech.compromisedpassword.infrastructure.lookup.runtime.BoundedLookupCompromisedPasswords",
                "com.sajtech.compromisedpassword.infrastructure.lookup.dataset.SqliteCompromisedPasswordRepository",
                "com.sajtech.compromisedpassword.infrastructure.lookup.dataset.DatasetReleaseManifestReader",
                "com.sajtech.compromisedpassword.infrastructure.lookup.dataset.DatasetGuard",
            )
            limit { counter = "LINE"; minimum = "0.78".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.48".toBigDecimal() }
        }
    }
}

tasks.register<JavaExec>("buildCompromisedPasswordDataset") {
    description = "Builds an immutable Compromised Password SQLite dataset from an approved local source."
    group = "dataset"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(
        "com.sajtech.compromisedpassword.infrastructure.lookup.datasetbuild.DatasetBuilderCli"
    )
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    exclude("com/sajtech/compromisedpassword/infrastructure/lookup/datasetbuild/**")
    exclude("BOOT-INF/classes/com/sajtech/compromisedpassword/infrastructure/lookup/datasetbuild/**")
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
