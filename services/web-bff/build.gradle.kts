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
group="com.sajtech";version="0.1.0-SNAPSHOT"
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
jacoco { toolVersion = "0.8.15" }

pitest {
  pitestVersion.set("1.22.1")
  junit5PluginVersion.set("1.2.3")
  targetClasses.set(setOf(
    "com.sajtech.webbff.infrastructure.security.BrowserSecurityFilter",
    "com.sajtech.webbff.infrastructure.security.SessionCrypto",
    "com.sajtech.webbff.infrastructure.security.TrustedClientAddress",
    "com.sajtech.webbff.infrastructure.quota.OidcClockSafetyGuard",
  ))
  targetTests.set(setOf(
    "com.sajtech.webbff.infrastructure.security.BrowserSecurityFilterTest",
    "com.sajtech.webbff.infrastructure.security.SessionCryptoTest",
    "com.sajtech.webbff.infrastructure.security.TrustedClientAddressTest",
    "com.sajtech.webbff.infrastructure.quota.OidcClockSafetyGuardTest",
  ))
  mutators.set(setOf("DEFAULTS"))
  threads.set(2)
  outputFormats.set(setOf("XML", "HTML"))
  timestampedReports.set(false)
  failWhenNoMutations.set(true)
  coverageThreshold.set(65)
  mutationThreshold.set(40)
  testStrengthThreshold.set(55)
}
dependencies {
    implementation("com.sajtech.hooshix:protobuf-contracts:1.8.0")
  implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
  implementation(platform("io.netty:netty-bom:4.2.16.Final"))
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.springframework.boot:spring-boot-starter-jooq")
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-kafka")
  implementation("org.flywaydb:flyway-database-postgresql")
  implementation("org.springframework.security:spring-security-oauth2-jose")
  implementation("io.lettuce:lettuce-core:7.5.2.RELEASE")
  implementation("io.grpc:grpc-netty-shaded:1.83.1")
  implementation("io.grpc:grpc-protobuf:1.83.1")
  implementation("io.grpc:grpc-stub:1.83.1")
  compileOnly("javax.annotation:javax.annotation-api:1.3.2")
  runtimeOnly("io.micrometer:micrometer-registry-prometheus")
  runtimeOnly("org.postgresql:postgresql:42.7.13")
  constraints {
    implementation("org.apache.logging.log4j:log4j-api:2.25.5") { because("CVE-2026-49844 is fixed in Log4j API 2.25.5") }
    implementation("tools.jackson.core:jackson-databind:3.1.5") { because("CVE-2026-59889 is fixed in jackson-databind 3.1.5") }
    implementation("at.yawk.lz4:lz4-java:1.11.1") { because("GHSA-xx22-p4ch-683r is fixed in lz4-java 1.11.1") }
  }
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
  testImplementation("org.yaml:snakeyaml:2.6")
  testImplementation("io.grpc:grpc-inprocess:1.83.1")
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-postgresql")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


protobuf { protoc { artifact="com.google.protobuf:protoc:4.34.2" }; plugins { maybeCreate("grpc").artifact="io.grpc:protoc-gen-grpc-java:1.83.1" }; generateProtoTasks { all().forEach { it.plugins.maybeCreate("grpc") } } }
spotless { java { target("src/**/*.java"); googleJavaFormat("1.36.1"); removeUnusedImports(); trimTrailingWhitespace(); endWithNewline() }; format("misc") { target("*.gradle.kts","*.properties","src/**/*.yaml","contracts/**/*.yaml","Dockerfile"); trimTrailingWhitespace(); endWithNewline() } }
configure<SpotBugsExtension> { toolVersion.set("4.10.3"); ignoreFailures.set(false); excludeFilter.set(file("config/spotbugs/exclude.xml")) }
dependencyLocking { lockAllConfigurations() }
tasks.withType<Test>().configureEach { useJUnitPlatform() }
val architectureTest = tasks.register<Test>("architectureTest") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform { includeTags("architecture") }
  shouldRunAfter(tasks.test)
}
val integrationTest = tasks.register<Test>("integrationTest") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform { includeTags("integration") }
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
  description = "Enforces the measured service baseline and critical browser-edge coverage."
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
      limit { counter = "LINE"; minimum = "0.44".toBigDecimal() }
      limit { counter = "BRANCH"; minimum = "0.44".toBigDecimal() }
    }
    rule {
      element = "CLASS"
      includes = listOf(
        "com.sajtech.webbff.infrastructure.security.BrowserSecurityFilter",
        "com.sajtech.webbff.infrastructure.security.SessionCrypto",
        "com.sajtech.webbff.infrastructure.security.TrustedClientAddress",
        "com.sajtech.webbff.infrastructure.quota.OidcClockSafetyGuard",
      )
      limit { counter = "LINE"; minimum = "0.81".toBigDecimal() }
      limit { counter = "BRANCH"; minimum = "0.48".toBigDecimal() }
    }
  }
}
tasks.test { useJUnitPlatform { excludeTags("integration", "architecture") } }
tasks.check {
  dependsOn(
    integrationTest,
    architectureTest,
    jacocoRiskCoverage,
    tasks.named("spotbugsMain"),
    tasks.named("spotlessCheck"),
  )
}
