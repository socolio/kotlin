plugins {
    kotlin("jvm")
    id("jps-compatible")
}

description = "Common klib metadata reader and writer"

dependencies {
    api(kotlinStdlib())
    api(project(":core:deserialization"))
    api(project(":kotlin-util-klib"))
    implementation(project(":compiler:cli-common"))
    implementation(project(":compiler:compiler.deserialization"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { none() }
}

publish()

standardPublicJars()
