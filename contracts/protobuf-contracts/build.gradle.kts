plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
    `maven-publish`
}

group = "com.sajtech.hooshix"
version = "1.1.0"

java {
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    api("com.google.protobuf:protobuf-java:3.25.8")
    api("io.grpc:grpc-protobuf:1.83.1")
    api("io.grpc:grpc-stub:1.83.1")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.8" }
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
