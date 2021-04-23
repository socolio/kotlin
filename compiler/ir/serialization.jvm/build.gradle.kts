plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(project(":compiler:ir.tree"))
    compile(project(":compiler:ir.serialization.common"))
    compile(project(":core:descriptors.jvm"))
    compile(project(":core:metadata.jvm"))
    implementation(project(":core:deserialization.common.jvm"))
    implementation(project(":compiler:resolution"))
}

sourceSets {
    "main" {
        projectDefault()
    }
    "test" {}
}
