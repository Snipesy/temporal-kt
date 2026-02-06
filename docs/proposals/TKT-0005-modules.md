# TKT-0005: Module Registration

Modules are extension functions of `TemporalApplication` that group related workflows, activities,
and configuration. This pattern (inspired by Ktor) enables clean organization and easy worker specialization.

## Basic Module

```kotlin
fun TemporalApplication.ordersModule() {
    taskQueue("orders") {
        workflow<CreateOrderWorkflow>()
        workflow<CancelOrderWorkflow>()
        activity(PaymentActivity())
        activity(InventoryActivity())
    }
}

fun TemporalApplication.notificationsModule() {
    taskQueue("notifications") {
        activity(EmailActivity())
        activity(SmsActivity())
        activity(PushNotificationActivity())
    }
}
```

## Composing Modules

Modules can call other modules:

```kotlin
fun TemporalApplication.allModules() {
    ordersModule()
    notificationsModule()
    analyticsModule()
}

// Or selectively compose
fun TemporalApplication.minimalWorker() {
    ordersModule()
}
```

## Specialized Workers

Different workers can load different module combinations:

```kotlin
// worker-orders/src/main/kotlin/Main.kt
fun main() = temporalApplication {
    ordersModule()
}.start()

// worker-notifications/src/main/kotlin/Main.kt
fun main() = temporalApplication {
    notificationsModule()
}.start()

// worker-all/src/main/kotlin/Main.kt
fun main() = temporalApplication {
    ordersModule()
    notificationsModule()
    analyticsModule()
}.start()
```

## Configuration-Based Loading

Load modules dynamically via config:

```yaml
# application.yaml
temporal:
  modules:
    - com.example.OrdersModuleKt.ordersModule
    - com.example.NotificationsModuleKt.notificationsModule
```

```kotlin
fun main() = temporalApplication {
    loadModulesFromConfig()  // Reflectively loads configured modules
}.start()
```

## Environment-Specific Modules

```kotlin
fun TemporalApplication.paymentsModule() {
    val gateway: PaymentGateway = when (environment.config.property("payments.provider").getString()) {
        "stripe" -> StripeGateway()
        "mock" -> MockGateway()
        else -> error("Unknown payment provider")
    }

    dependencies {
        provide<PaymentGateway> { gateway }
    }

    taskQueue("payments") {
        activity(ChargeActivity())
        activity(RefundActivity())
    }
}
```

## Module with Shared Dependencies

```kotlin
fun TemporalApplication.baseModule() {
    install(SerializationPlugin) {
        json { prettyPrint = true }
    }

    dependencies {
        provide<Logger> { SlfLogger() }
        provide<MetricsClient> { PrometheusClient() }
    }
}

fun TemporalApplication.ordersModule() {
    baseModule()  // Include shared config

    taskQueue("orders") {
        workflow<CreateOrderWorkflow>()
    }
}
```

## Conditional Module Loading

```kotlin
fun TemporalApplication.featureFlagsModule() {
    if (environment.config.propertyOrNull("features.analytics")?.getString() == "enabled") {
        analyticsModule()
    }

    if (environment.developmentMode) {
        debugModule()
    }
}
```