plugins {
    kotlin("jvm") version "2.1.20"
    id("com.gradleup.shadow") version "8.3.3"
    id("maven-publish")

}

group = "dev.cirosanchez"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.mongodb:bson:4.3.4")
    implementation("org.mongodb:mongodb-driver-kotlin-sync:4.11.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation(kotlin("reflect"))
}

kotlin {
    jvmToolchain(8)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifact(tasks.named("shadowJar").get()) {
                classifier = null
            }
            groupId = "dev.cirosanchez"
            artifactId = "mongodbstandalone"
            version = "v0.0.1"

        }
    }
}

tasks.shadowJar {
    minimize()
}