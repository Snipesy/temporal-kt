plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.example.helloworld.MainKt")
}
