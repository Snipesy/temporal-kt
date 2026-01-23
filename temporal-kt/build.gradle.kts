import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    alias(libs.plugins.kotlinPluginSerialization)
    `java-test-fixtures`
}

dependencies {
    api(project(":core-bridge"))
    api(libs.bundles.kotlinxEcosystem)
    implementation(libs.protobufJava)
    implementation(libs.protobufJavaUtil)
    implementation(libs.protobufKotlin)
    implementation(libs.bundles.hoplite)
    implementation(libs.kotlinReflect)
    implementation(libs.slf4jApi)
    implementation(libs.kotlinCoroutinesSl4j)

    testImplementation(kotlin("test"))
    testImplementation(libs.slf4jSimple)
    testImplementation(libs.kotlinxCoroutinesTest)

    testFixturesImplementation(libs.kotlinxCoroutinesTest)
    testFixturesImplementation(project(":core-bridge"))
    testFixturesImplementation(libs.protobufJava)
    testFixturesImplementation(libs.protobufKotlin)
    testFixturesImplementation(libs.slf4jApi)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Temporal KT")
                description.set("Kotlin-first SDK for Temporal")
            }
        }
    }
}

tasks.named<KotlinCompilationTask<*>>("compileKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}

tasks.named<KotlinCompilationTask<*>>("compileTestFixturesKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}

tasks.named<KotlinCompilationTask<*>>("compileTestKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}
