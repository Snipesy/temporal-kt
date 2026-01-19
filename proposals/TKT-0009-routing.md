# TKT-0009: Routing

Hierarchical naming for workflows and activities. Routes prepend a prefix to workflow/activity type names.

## Basic Usage

```kotlin
fun TemporalApplication.module() {
    taskQueue("main") {
        route("orders") {
            workflow(CreateOrderWorkflow())      // → "orders/CreateOrder"
            workflow(CancelOrderWorkflow())      // → "orders/CancelOrder"
            activity(PaymentActivity())          // → "orders/Payment"

            route("fulfillment") {
                workflow(ShipOrderWorkflow())    // → "orders/fulfillment/ShipOrder"
                activity(InventoryActivity())    // → "orders/fulfillment/Inventory"
            }
        }

        route("users") {
            workflow(OnboardingWorkflow())       // → "users/Onboarding"
            activity(EmailActivity())            // → "users/Email"
        }
    }
}
```

## With Dependency Injection (TKT-0004)

Routes can scope dependencies for complex setups:

```kotlin
fun TemporalApplication.module() {
    taskQueue("main") {
        route("orders") {
            dependencies {
                provide<PaymentGateway> { StripeGateway() }
            }

            workflow(CreateOrderWorkflow())
            activity(PaymentActivity())
        }

        route("legacy") {
            dependencies {
                provide<PaymentGateway> { LegacyGateway() }
            }

            workflow(CreateOrderWorkflow())  // Same workflow, different gateway
            activity(PaymentActivity())
        }
    }
}
```

## Open Questions

- Default separator: `/`, `.`, or `::` ?
- Should routes be configurable per-environment?
