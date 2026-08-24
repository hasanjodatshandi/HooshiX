import com.github.spotbugs.snom.SpotBugsExtension
plugins {
  java
  id("org.springframework.boot") version "4.1.0"
  id("com.google.protobuf") version "0.10.0"
  id("com.diffplug.spotless") version "8.9.0"
  id("com.github.spotbugs") version "6.5.9"
}
group="com.sajtech";version="0.1.0-SNAPSHOT"
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
dependencies {
    implementation("com.sajtech.hooshix:protobuf-contracts:1.4.0")
  implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
  implementation(platform("io.netty:netty-bom:4.2.16.Final"))
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("io.lettuce:lettuce-core:7.5.2.RELEASE")
  implementation("io.grpc:grpc-netty-shaded:1.83.1")
  implementation("io.grpc:grpc-protobuf:1.83.1")
  implementation("io.grpc:grpc-stub:1.83.1")
  compileOnly("javax.annotation:javax.annotation-api:1.3.2")
  runtimeOnly("io.micrometer:micrometer-registry-prometheus")
  constraints {
    implementation("org.apache.logging.log4j:log4j-api:2.25.5") { because("CVE-2026-49844 is fixed in Log4j API 2.25.5") }
    implementation("tools.jackson.core:jackson-databind:3.1.5") { because("CVE-2026-59889 is fixed in jackson-databind 3.1.5") }
  }
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
  testImplementation("org.yaml:snakeyaml:2.6")
  testImplementation("io.grpc:grpc-inprocess:1.83.1")
  testImplementation("org.testcontainers:testcontainers")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


protobuf { protoc { artifact="com.google.protobuf:protoc:3.25.8" }; plugins { maybeCreate("grpc").artifact="io.grpc:protoc-gen-grpc-java:1.83.1" }; generateProtoTasks { all().forEach { it.plugins.maybeCreate("grpc") } } }
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
tasks.test { useJUnitPlatform { excludeTags("integration", "architecture") } }
tasks.check { dependsOn(integrationTest, architectureTest, tasks.named("spotbugsMain"), tasks.named("spotlessCheck")) }
