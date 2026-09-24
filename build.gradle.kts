plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
}

tasks.register("run") {
    group = "application"
    description = "Delegate run to :app"
    dependsOn(":app:run")
}

tasks.register("test") {
    group = "verification"
    description = "Delegate test to :app"
    dependsOn(":app:test")
}
