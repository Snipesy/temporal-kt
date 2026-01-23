package com.surrealdev.temporal.annotation

/**
 * DSL marker for Temporal builders to prevent scope leakage.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class TemporalDsl

/**
 * Marks a class as a Temporal workflow definition.
 *
 * The annotated class should contain exactly one method annotated with [WorkflowRun]
 * which serves as the workflow's entry point.
 *
 * Example:
 * ```kotlin
 * @Workflow("GreetingWorkflow")
 * class GreetingWorkflow {
 *     @WorkflowRun
 *     suspend fun WorkflowContext.execute(arg: GreetingArg): String {
 *         // workflow implementation
 *     }
 * }
 * ```
 *
 * @param name The workflow type name. If empty, the class name will be used.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Workflow(
    val name: String = "",
)

/**
 * Marks a method as the workflow's entry point.
 *
 * Must be used within a class annotated with [Workflow].
 * Only one method per workflow class should have this annotation.
 *
 * The method should:
 * - Be a suspending function
 * - Use [com.surrealdev.temporal.workflow.WorkflowContext] as a receiver
 * - Accept a single serializable argument (recommended for backwards compatibility)
 *
 * Example:
 * ```kotlin
 * @WorkflowRun
 * suspend fun WorkflowContext.execute(arg: MyArg): String {
 *     return "Result: ${arg.value}"
 * }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class WorkflowRun

/**
 * Marks a method as a Temporal activity.
 *
 * Activities are the building blocks of workflows that perform side effects
 * and interact with external systems.
 *
 * The activity name is registered directly as provided (or the function name if not specified).
 * There is no automatic prefixing with class names.
 *
 * The method can optionally use [com.surrealdev.temporal.activity.ActivityContext] as a receiver
 * to access activity information and heartbeat functionality.
 *
 * Example:
 * ```kotlin
 * @Activity("greet")
 * fun greet(name: String): String = "Hello, $name!"
 *
 * @Activity  // Uses function name "processOrder"
 * suspend fun ActivityContext.processOrder(order: Order): Result {
 *     heartbeat("Processing...")
 *     return doWork(order)
 * }
 * ```
 *
 * @param name The activity type name. If empty, the function name will be used.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Activity(
    val name: String = "",
)

/**
 * Marks a method as a signal handler in a workflow.
 *
 * Signals are asynchronous messages sent to a running workflow.
 * Signal handlers should not return values (return Unit).
 *
 * Example:
 * ```kotlin
 * @Workflow("OrderWorkflow")
 * class OrderWorkflow {
 *     private var approved = false
 *
 *     @Signal("approve")
 *     suspend fun WorkflowContext.approveOrder(approvedBy: String) {
 *         approved = true
 *     }
 *
 *     @WorkflowRun
 *     suspend fun WorkflowContext.execute(order: Order): OrderResult {
 *         awaitCondition { approved }
 *         // continue processing...
 *     }
 * }
 * ```
 *
 * @param name The signal name. If empty, the function name will be used.
 * @param dynamic If true, this handler receives all signals not handled by other handlers.
 *                Dynamic handlers receive the signal name as the first parameter.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Signal(
    val name: String = "",
    val dynamic: Boolean = false,
)

/**
 * Marks a method as a query handler in a workflow.
 *
 * Queries are synchronous read-only operations that inspect workflow state.
 * Query handlers must not modify workflow state or perform side effects.
 *
 * Example:
 * ```kotlin
 * @Workflow("OrderWorkflow")
 * class OrderWorkflow {
 *     private var status: OrderStatus = OrderStatus.PENDING
 *
 *     @Query("getStatus", description = "Returns the current order status")
 *     fun WorkflowContext.getOrderStatus(): OrderStatus {
 *         return status
 *     }
 * }
 * ```
 *
 * @param name The query name. If empty, the function name will be used.
 * @param dynamic If true, this handler receives all queries not handled by other handlers.
 *                Dynamic handlers must have a String as their first parameter to receive the query type.
 * @param description Human-readable description of what this query returns. Shown in Temporal UI/CLI.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Query(
    val name: String = "",
    val dynamic: Boolean = false,
    val description: String = "",
)

/**
 * Marks a method as an update handler in a workflow.
 *
 * Updates are synchronous operations that can both read and modify workflow state.
 * Unlike signals, updates can return values and the caller waits for completion.
 *
 * Example:
 * ```kotlin
 * @Workflow("CartWorkflow")
 * class CartWorkflow {
 *     private val items = mutableListOf<CartItem>()
 *
 *     @Update("addItem")
 *     suspend fun WorkflowContext.addItem(item: CartItem): Int {
 *         items.add(item)
 *         return items.size
 *     }
 * }
 * ```
 *
 * @param name The update name. If empty, the function name will be used.
 * @param dynamic If true, this handler receives all updates not handled by other handlers.
 * @param unfinishedPolicy How to handle unfinished updates when the workflow completes.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Update(
    val name: String = "",
    val dynamic: Boolean = false,
    val unfinishedPolicy: UpdateUnfinishedPolicy = UpdateUnfinishedPolicy.WARN,
)

/**
 * Policy for handling unfinished updates when a workflow completes.
 */
enum class UpdateUnfinishedPolicy {
    /** Log a warning about unfinished updates. */
    WARN,

    /** Abort the workflow if there are unfinished updates. */
    ABORT,
}

/**
 * Marks a method as an update validator.
 *
 * Validators run before the corresponding update handler and can reject
 * invalid update requests by throwing an exception.
 *
 * Example:
 * ```kotlin
 * @UpdateValidator("addItem")
 * fun validateAddItem(item: CartItem) {
 *     require(item.quantity > 0) { "Quantity must be positive" }
 * }
 *
 * @Update("addItem")
 * suspend fun WorkflowContext.addItem(item: CartItem): Int {
 *     items.add(item)
 *     return items.size
 * }
 * ```
 *
 * @param updateName The name of the update this validator is for.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class UpdateValidator(
    val updateName: String,
)
