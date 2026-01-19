plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":temporal-kt"))
}

application {
    mainClass.set("com.example.multiworker.MainKt")
}
