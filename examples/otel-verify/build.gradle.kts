plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":plugins:opentelemetry"))

    // OTel SDK to configure exporters
    implementation(libs.opentelemetrySdk)
    // OTLP exporter (HTTP + gRPC) to send data to Grafana LGTM
    implementation(libs.opentelemetryExporterOtlp)

    // Logging with MDC trace context
    implementation(libs.logbackClassic)
    // Bridges Logback → OTel Logs API → OTLP → Loki
    implementation(libs.opentelemetryLogbackAppender)
}

application {
    mainClass.set("com.example.otelverify.MainKt")
}

val nativeLibsDir = rootProject.layout.projectDirectory.dir("core-bridge/build/native-libs")
val skipNativeBuild = project.findProperty("skipNativeBuild")?.toString()?.toBoolean() ?: false

sourceSets {
    main {
        resources.srcDir(nativeLibsDir)
    }
}

tasks.named("processResources") {
    if (!skipNativeBuild) {
        dependsOn(":core-bridge:copyNativeLib")
    }
}
