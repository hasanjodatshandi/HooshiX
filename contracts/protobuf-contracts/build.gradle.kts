plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
    `maven-publish`
}

val prepareBufDependencies = tasks.register<Sync>("prepareBufDependencies") {
    dependsOn("extractIncludeProto")
    from(layout.buildDirectory.dir("extracted-include-protos/main")) {
        include("buf/validate/validate.proto")
    }
    into(layout.buildDirectory.dir("buf-dependencies"))
}

group = "com.sajtech.hooshix"
version = "1.5.0"

java {
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    api("com.google.protobuf:protobuf-java:4.34.2")
    api("io.grpc:grpc-protobuf:1.83.1")
    api("io.grpc:grpc-stub:1.83.1")
    api("build.buf:protovalidate:1.2.2")

    testImplementation("io.grpc:grpc-inprocess:1.83.1")
    testImplementation("com.google.protobuf:protobuf-java-util:4.34.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.1")
}

tasks.test { useJUnitPlatform() }
tasks.named("processResources") { dependsOn(prepareBufDependencies) }

dependencyLocking {
    lockAllConfigurations()
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.34.2" }
    plugins { create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.83.1" } }
    generateProtoTasks { all().forEach { it.plugins { create("grpc") } } }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "protobuf-contracts"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "localRelease"
            url = uri(layout.buildDirectory.dir("repository"))
        }
    }
}
