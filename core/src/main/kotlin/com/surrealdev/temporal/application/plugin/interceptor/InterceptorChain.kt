package com.surrealdev.temporal.application.plugin.interceptor

/**
 * A function that intercepts an operation, optionally modifying the input or output.
 *
 * Interceptors follow the chain-of-responsibility pattern. Each interceptor receives
 * the input and a `proceed` function that calls the next interceptor (or the terminal handler).
 *
 * Example:
 * ```kotlin
 * val loggingInterceptor: Interceptor<MyInput, MyOutput> = { input, proceed ->
 *     println("Before: $input")
 *     val result = proceed(input)
 *     println("After: $result")
 *     result
 * }
 * ```
 */
typealias Interceptor<TInput, TOutput> =
    suspend (input: TInput, proceed: suspend (TInput) -> TOutput) -> TOutput

/**
 * Executes a chain of interceptors in order, with a terminal handler at the end.
 *
 * Interceptors are composed so that the first interceptor in the list is the outermost
 * (called first, returns last), and the last interceptor is closest to the terminal handler.
 *
 * @param TInput The input type for the operation
 * @param TOutput The output type for the operation
 */
class InterceptorChain<TInput, TOutput>(
    private val interceptors: List<Interceptor<TInput, TOutput>>,
) {
    /**
     * Executes the interceptor chain with the given input and terminal handler.
     *
     * @param input The initial input to the chain
     * @param terminal The final handler that performs the actual operation
     * @return The output from the chain
     */
    suspend fun execute(
        input: TInput,
        terminal: suspend (TInput) -> TOutput,
    ): TOutput {
        if (interceptors.isEmpty()) return terminal(input)

        var current: suspend (TInput) -> TOutput = terminal
        for (interceptor in interceptors.asReversed()) {
            val next = current
            current = { inp -> interceptor(inp, next) }
        }
        return current(input)
    }
}
