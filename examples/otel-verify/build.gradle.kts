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

    // The Core native library, as a JAR -- the same shape a published consumer resolves from
    // Maven as a classifier artifact. Replaces the old resources-srcDir + processResources
    // dependsOn(":core-bridge:copyNativeLib") wiring.
    runtimeOnly(project(mapOf("path" to ":core-bridge", "configuration" to "nativeRuntime")))
}

application {
    mainClass.set("com.example.otelverify.MainKt")
}
