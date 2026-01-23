import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
}

dependencies {
    api(project(":temporal-kt"))
    implementation(libs.kotlinReflect)

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":temporal-kt")))
    testImplementation(libs.kotlinxCoroutinesTest)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Temporal KT Dependencies")
                description.set("Dependency Injection plugin for Temporal KT")
            }
        }
    }
}

tasks.named<KotlinCompilationTask<*>>("compileKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}

tasks.named<KotlinCompilationTask<*>>("compileTestKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}
