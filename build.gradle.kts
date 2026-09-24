plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.1.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.1.2")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.1.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.1.2")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:3.1.2")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("sipgsb.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
