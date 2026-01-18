plugins {
    id("buildsrc.convention.kotlin-jvm")
    kotlin("kapt")
    `maven-publish`
}

group = property("GROUP") as String
version = property("VERSION") as String

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    compileOnly(libs.kotlinCompilerEmbeddable)

    // Auto-service for automatic service registration
    compileOnly(libs.autoServiceAnnotations)
    kapt(libs.autoService)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinCompilerEmbeddable)
}
