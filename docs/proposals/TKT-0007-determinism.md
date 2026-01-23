# TKT-0007: Determinism and Best Practices

With annotation-based definitions, we can do deep introspection via KSP/compiler plugin to ensure
best practices are followed and avoid determinism issues.

## Compile-Time Checks

1. **Single argument pattern** - Warn when using multiple args
2. **Non-temporal randomness** - Error when using `kotlin.random` or `java.util.Random`
3. **Non-temporal time** - Error when using system time
4. **Non-activity I/O** - Warn when performing I/O outside activities
5. **Serialization validation** - Error for non-serializable args/return types
6. **Mutable type warnings** - Warn when using mutable collections as args
7. **Global state detection** - Warn when accessing global mutable state