plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    api(project(":compiler:config"))
    api(project(":compiler:resolution"))
    api(project(":core:deserialization"))
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}
