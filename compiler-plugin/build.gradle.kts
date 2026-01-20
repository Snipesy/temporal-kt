plugins {
    id("buildsrc.convention.kotlin-jvm")
    kotlin("kapt")
    alias(libs.plugins.kotlinPluginSerialization)
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
    compileOnly(project(":temporal-kt"))
    implementation(libs.kotlinxSerialization)

    // Auto-service for automatic service registration
    compileOnly(libs.autoServiceAnnotations)
    kapt(libs.autoService)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinCompilerEmbeddable)
    testImplementation(libs.kotlinxCoroutines)
    testImplementation(project(":temporal-kt"))
}
