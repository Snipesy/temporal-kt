plugins {
    id("buildsrc.convention.kotlin-jvm")
    // Runnable: needs the Core native library on the runtime classpath.
    id("buildsrc.convention.temporal-native-runtime")
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
