package com.surrealdev.temporal.dependencies

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.activity.ActivityInfo
import com.surrealdev.temporal.activity.ActivityWorkflowInfo
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.Attributes
import com.surrealdev.temporal.util.ExecutionScope
import com.surrealdev.temporal.workflow.SuggestContinueAsNewReason
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.WorkflowInfo
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Basic integration test for dependency injection.
 *
 * This test verifies the core DI functionality:
 * 1. Dependency registration via DSL (application and task queue level)
 * 2. Dependency resolution
 * 3. Scope enforcement
 * 4. Caching behavior
 */
class BasicDependencyInjectionTest {
    interface TestService {
        fun getMessage(): String
    }

    class TestServiceImpl(
        private val message: String = "Hello from DI",
    ) : TestService {
        override fun getMessage() = message
    }

    interface ConfigService {
        fun getConfig(): String
    }

    class ConfigServiceImpl(
        private val config: String = "default-config",
    ) : ConfigService {
        override fun getConfig() = config
    }

    // ===========================================
    // Application-Level Dependency Tests
    // ===========================================

    @Test
    fun `can register dependencies via DSL on application`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Register dependencies using the new DSL
        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl() }
        }

        assertNotNull(app.dependencies, "Dependencies registry should exist")
    }

    @Test
    fun `application DependencyRegistry can create contexts`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Register a dependency
        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl() }
        }

        // Create contexts
        val workflowContext = app.dependencies.createWorkflowContext()
        assertNotNull(workflowContext, "Should create workflow context")

        val activityContext = app.dependencies.createActivityContext()
        assertNotNull(activityContext, "Should create activity context")
    }

    @Test
    fun `application DependencyContext can resolve dependencies`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl() }
        }

        val context = app.dependencies.createWorkflowContext()
        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)
        val service = context.get(key)

        assertNotNull(service)
        assertEquals("Hello from DI", service.getMessage())
    }

    @Test
    fun `application DependencyContext caches resolved dependencies`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        var instanceCount = 0
        app.dependencies {
            workflowSafe<TestService> {
                instanceCount++
                TestServiceImpl()
            }
        }

        val context = app.dependencies.createWorkflowContext()
        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)

        // Get dependency twice
        val service1 = context.get(key)
        val service2 = context.get(key)

        // Should be same instance (cached)
        assertEquals(1, instanceCount, "Factory should only be called once")
        assertSame(service1, service2, "Should return cached instance")
    }

    @Test
    fun `workflow context blocks ACTIVITY_ONLY dependencies`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            activityOnly<TestService> { TestServiceImpl() }
        }

        val context = app.dependencies.createWorkflowContext()
        val key = dependencyKey<TestService>(DependencyScope.ACTIVITY_ONLY)

        try {
            context.get(key)
            throw AssertionError("Should have thrown IllegalDependencyScopeException")
        } catch (e: IllegalDependencyScopeException) {
            // Expected
            assert(e.message?.contains("ACTIVITY_ONLY") == true)
        }
    }

    @Test
    fun `activity context allows ACTIVITY_ONLY dependencies`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            activityOnly<TestService> { TestServiceImpl() }
        }

        val context = app.dependencies.createActivityContext()
        val key = dependencyKey<TestService>(DependencyScope.ACTIVITY_ONLY)
        val service = context.get(key)

        assertNotNull(service)
        assertEquals("Hello from DI", service.getMessage())
    }

    @Test
    fun `activity context allows WORKFLOW_SAFE dependencies`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl() }
        }

        val context = app.dependencies.createActivityContext()
        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)
        val service = context.get(key)

        assertNotNull(service)
        assertEquals("Hello from DI", service.getMessage())
    }

    @Test
    fun `getOrNull returns null for missing dependency`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Access dependencies to create the registry
        app.dependencies

        val context = app.dependencies.createWorkflowContext()
        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)
        val service = context.getOrNull(key)

        assertEquals(null, service, "Should return null for missing dependency")
    }

    @Test
    fun `direct registry access works on application`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Direct registration without DSL block
        app.dependencies.workflowSafe<TestService> { TestServiceImpl() }

        val context = app.dependencies.createWorkflowContext()
        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)
        val service = context.get(key)

        assertNotNull(service)
        assertEquals("Hello from DI", service.getMessage())
    }

    // ===========================================
    // Task-Queue-Level Dependency Tests
    // ===========================================

    @Test
    fun `can register dependencies via DSL on task queue`() {
        var taskQueueRegistry: DependencyRegistry? = null

        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.taskQueue("test-queue") {
            dependencies {
                workflowSafe<TestService> { TestServiceImpl("Task queue specific") }
            }
            // Capture the registry for verification
            taskQueueRegistry = this.dependencies
        }

        assertNotNull(taskQueueRegistry, "Task queue should have a dependency registry")
    }

    @Test
    fun `task queue dependencies can create contexts`() {
        var taskQueueRegistry: DependencyRegistry? = null

        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.taskQueue("test-queue") {
            dependencies {
                workflowSafe<TestService> { TestServiceImpl("From task queue") }
            }
            taskQueueRegistry = this.dependencies
        }

        val registry = taskQueueRegistry!!

        // Create contexts
        val workflowContext = registry.createWorkflowContext()
        assertNotNull(workflowContext, "Should create workflow context")

        val activityContext = registry.createActivityContext()
        assertNotNull(activityContext, "Should create activity context")
    }

    @Test
    fun `task queue dependencies resolve correctly`() {
        var taskQueueRegistry: DependencyRegistry? = null

        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.taskQueue("test-queue") {
            dependencies {
                workflowSafe<TestService> { TestServiceImpl("From task queue") }
            }
            taskQueueRegistry = this.dependencies
        }

        val registry = taskQueueRegistry!!
        val context = registry.createWorkflowContext()
        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)
        val service = context.get(key)

        assertNotNull(service)
        assertEquals("From task queue", service.getMessage())
    }

    @Test
    fun `task queue and application can have different dependencies`() {
        var taskQueueRegistry: DependencyRegistry? = null

        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Application-level dependency
        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl("From application") }
        }

        // Task-queue-level dependency (should be independent for this queue)
        app.taskQueue("test-queue") {
            dependencies {
                workflowSafe<TestService> { TestServiceImpl("From task queue") }
            }
            taskQueueRegistry = this.dependencies
        }

        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)

        // Application context
        val appContext = app.dependencies.createWorkflowContext()
        val appService = appContext.get(key)
        assertEquals("From application", appService.getMessage())

        // Task queue context
        val taskQueueContext = taskQueueRegistry!!.createWorkflowContext()
        val taskQueueService = taskQueueContext.get(key)
        assertEquals("From task queue", taskQueueService.getMessage())
    }

    @Test
    fun `multiple task queues can have different dependencies`() {
        var queue1Registry: DependencyRegistry? = null
        var queue2Registry: DependencyRegistry? = null

        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.taskQueue("queue-1") {
            dependencies {
                workflowSafe<TestService> { TestServiceImpl("From queue 1") }
            }
            queue1Registry = this.dependencies
        }

        app.taskQueue("queue-2") {
            dependencies {
                workflowSafe<TestService> { TestServiceImpl("From queue 2") }
            }
            queue2Registry = this.dependencies
        }

        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)

        // Queue 1
        val queue1Context = queue1Registry!!.createWorkflowContext()
        val queue1Service = queue1Context.get(key)
        assertEquals("From queue 1", queue1Service.getMessage())

        // Queue 2
        val queue2Context = queue2Registry!!.createWorkflowContext()
        val queue2Service = queue2Context.get(key)
        assertEquals("From queue 2", queue2Service.getMessage())
    }

    @Test
    fun `task queue dependencies are independent from other queues`() {
        var queue1Registry: DependencyRegistry? = null
        var queue2Registry: DependencyRegistry? = null

        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        var queue1Count = 0
        var queue2Count = 0

        app.taskQueue("queue-1") {
            dependencies {
                workflowSafe<TestService> {
                    queue1Count++
                    TestServiceImpl("Queue 1")
                }
            }
            queue1Registry = this.dependencies
        }

        app.taskQueue("queue-2") {
            dependencies {
                workflowSafe<TestService> {
                    queue2Count++
                    TestServiceImpl("Queue 2")
                }
            }
            queue2Registry = this.dependencies
        }

        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)

        // Get from queue 1 multiple times (different contexts, same singleton)
        val queue1Context1 = queue1Registry!!.createWorkflowContext()
        val queue1Context2 = queue1Registry.createWorkflowContext()
        queue1Context1.get(key)
        queue1Context2.get(key)

        // Get from queue 2 once
        val queue2Context = queue2Registry!!.createWorkflowContext()
        queue2Context.get(key)

        // Registry-level singletons: factory called once per registry regardless of context count
        assertEquals(1, queue1Count, "Queue 1 factory should be called once (singleton)")
        assertEquals(1, queue2Count, "Queue 2 factory should be called once (singleton)")
    }

    @Test
    fun `each workflow execution context caches independently`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl() }
        }

        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)

        // Create two separate execution contexts
        val context1 = app.dependencies.createWorkflowContext()
        val context2 = app.dependencies.createWorkflowContext()

        val service1a = context1.get(key)
        val service1b = context1.get(key)
        val service2 = context2.get(key)

        // Same context returns same singleton
        assertSame(service1a, service1b, "Same context should return the same singleton")

        // Different contexts from the same registry return the same singleton
        assertSame(service1a, service2, "Different contexts from the same registry share the singleton")
    }

    // ===========================================
    // Lifecycle / AutoCloseable Tests
    // ===========================================

    @Test
    fun `AutoCloseable dependency is closed when registry closes`() {
        var closedCount = 0

        val registry = DependencyRegistry()
        registry.activityOnly<TestService> {
            object : TestService, AutoCloseable {
                override fun getMessage() = "closeable"

                override fun close() {
                    closedCount++
                }
            }
        }

        val key = dependencyKey<TestService>(DependencyScope.ACTIVITY_ONLY)
        val ctx = registry.createActivityContext()
        ctx.get(key)

        assertEquals(0, closedCount, "Should not be closed before registry.close()")
        registry.close()
        assertEquals(1, closedCount, "Should be closed once after registry.close()")
    }

    @Test
    fun `cleanup block is called instead of close() when registry closes`() {
        var cleanupCount = 0
        var autoCloseCount = 0

        val registry = DependencyRegistry()
        registry.activityOnly<TestService> {
            object : TestService, AutoCloseable {
                override fun getMessage() = "with-cleanup"

                override fun close() {
                    autoCloseCount++
                }
            }
        } cleanup { cleanupCount++ }

        val key = dependencyKey<TestService>(DependencyScope.ACTIVITY_ONLY)
        registry.createActivityContext().get(key)
        registry.close()

        assertEquals(1, cleanupCount, "Cleanup block should be called once")
        assertEquals(0, autoCloseCount, "AutoCloseable.close() should NOT be called when cleanup block is set")
    }

    @Test
    fun `cleanup block is called for dependency without AutoCloseable`() {
        var cleanupCount = 0

        val registry = DependencyRegistry()
        registry.workflowSafe<TestService> { TestServiceImpl() } cleanup { cleanupCount++ }

        val key = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)
        registry.createWorkflowContext().get(key)
        registry.close()

        assertEquals(1, cleanupCount, "Cleanup block should be called for non-AutoCloseable dependency")
    }

    @Test
    fun `registry close is idempotent`() {
        var closedCount = 0

        val registry = DependencyRegistry()
        registry.activityOnly<TestService> {
            object : TestService, AutoCloseable {
                override fun getMessage() = "idempotent"

                override fun close() {
                    closedCount++
                }
            }
        }

        val key = dependencyKey<TestService>(DependencyScope.ACTIVITY_ONLY)
        registry.createActivityContext().get(key)

        registry.close()
        registry.close()
        registry.close()

        assertEquals(1, closedCount, "close() should only invoke cleanup once regardless of how many times called")
    }

    // ===========================================
    // Hierarchical Override Tests
    // ===========================================

    @Test
    fun `task queue dependency overrides app-level dependency`() {
        var appRegistry: DependencyRegistry? = null
        var taskQueueRegistry: DependencyRegistry? = null

        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // App-level dependency
        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl("From app") }
        }
        appRegistry = app.dependencies

        // Task queue overrides the same dependency
        app.taskQueue("override-queue") {
            dependencies {
                workflowSafe<TestService> { TestServiceImpl("From task queue") }
            }
            taskQueueRegistry = this.dependencies
        }

        val testServiceKey = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)

        // Create context with task queue as primary, app as fallback
        val context = taskQueueRegistry!!.createWorkflowContext(fallback = appRegistry)

        // Should get task queue version (override)
        val service = context.get(testServiceKey)
        assertEquals("From task queue", service.getMessage())
    }

    @Test
    fun `task queue context can access app-level dependency not overridden`() {
        var appRegistry: DependencyRegistry? = null
        var taskQueueRegistry: DependencyRegistry? = null

        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // App-level dependencies: both TestService and ConfigService
        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl("From app") }
            workflowSafe<ConfigService> { ConfigServiceImpl("app-config") }
        }
        appRegistry = app.dependencies

        // Task queue only overrides TestService
        app.taskQueue("partial-override-queue") {
            dependencies {
                workflowSafe<TestService> { TestServiceImpl("From task queue") }
            }
            taskQueueRegistry = this.dependencies
        }

        val testServiceKey = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)
        val configServiceKey = dependencyKey<ConfigService>(DependencyScope.WORKFLOW_SAFE)

        // Create context with task queue as primary, app as fallback
        val context = taskQueueRegistry!!.createWorkflowContext(fallback = appRegistry)

        // TestService should come from task queue (overridden)
        val testService = context.get(testServiceKey)
        assertEquals("From task queue", testService.getMessage())

        // ConfigService should come from app (not overridden, accessed via fallback)
        val configService = context.get(configServiceKey)
        assertEquals("app-config", configService.getConfig())
    }

    @Test
    fun `app-only context works when no task queue dependencies registered`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Only app-level dependencies
        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl("From app") }
            workflowSafe<ConfigService> { ConfigServiceImpl("app-config") }
        }

        val appRegistry = app.dependencies
        val testServiceKey = dependencyKey<TestService>(DependencyScope.WORKFLOW_SAFE)
        val configServiceKey = dependencyKey<ConfigService>(DependencyScope.WORKFLOW_SAFE)

        // Create context with no fallback (simulates no task queue registry)
        val context = appRegistry.createWorkflowContext(fallback = null)

        // Both should come from app
        assertEquals("From app", context.get(testServiceKey).getMessage())
        assertEquals("app-config", context.get(configServiceKey).getConfig())
    }

    // ===========================================
    // Property Delegate Tests (Ktor-style)
    // ===========================================

    // Helper extension functions that use the Ktor-style delegates
    private fun MockWorkflowContext.getWorkflowService(): TestService {
        val service: TestService by workflowDependencies
        return service
    }

    private fun MockWorkflowContext.getWorkflowConfig(): ConfigService {
        val config: ConfigService by workflowDependencies
        return config
    }

    private fun MockActivityContext.getActivityService(): TestService {
        val service: TestService by activityDependencies
        return service
    }

    private fun MockActivityContext.getWorkflowSafeService(): TestService {
        val service: TestService by workflowDependencies
        return service
    }

    private fun MockActivityContext.getWorkflowSafeConfig(): ConfigService {
        val config: ConfigService by workflowDependencies
        return config
    }

    @Test
    fun `workflowDependencies delegate resolves in workflow context`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl("From workflow") }
        }

        // Create mock workflow context with the app as parent
        val mockContext = MockWorkflowContext(parentScope = app)

        // Use the delegate via extension function (how it's used in production)
        assertEquals("From workflow", mockContext.getWorkflowService().getMessage())
    }

    @Test
    fun `activityDependencies delegate resolves ACTIVITY_ONLY in activity context`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            activityOnly<TestService> { TestServiceImpl("From activity") }
        }

        // Create mock activity context with the app as parent
        val mockContext = MockActivityContext(parentScope = app)

        // Use the delegate via extension function
        assertEquals("From activity", mockContext.getActivityService().getMessage())
    }

    @Test
    fun `workflowDependencies delegate works in activity context for WORKFLOW_SAFE dependencies`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl("Workflow-safe in activity") }
            workflowSafe<ConfigService> { ConfigServiceImpl("config-in-activity") }
        }

        // Create mock activity context with the app as parent
        val mockContext = MockActivityContext(parentScope = app)

        // Use the workflowDependencies delegate in an activity context
        // This should work because WORKFLOW_SAFE dependencies are allowed in activities
        assertEquals("Workflow-safe in activity", mockContext.getWorkflowSafeService().getMessage())
        assertEquals("config-in-activity", mockContext.getWorkflowSafeConfig().getConfig())
    }

    @Test
    fun `activity can use both activityDependencies and workflowDependencies`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            activityOnly<TestService> { TestServiceImpl("Activity-only service") }
            workflowSafe<ConfigService> { ConfigServiceImpl("Workflow-safe config") }
        }

        // Create mock activity context
        val mockContext = MockActivityContext(parentScope = app)

        // Activity can use activityDependencies for activity-only deps
        assertEquals("Activity-only service", mockContext.getActivityService().getMessage())
        // And workflowDependencies for workflow-safe deps
        assertEquals("Workflow-safe config", mockContext.getWorkflowSafeConfig().getConfig())
    }

    @Test
    fun `workflowDependencies fails for missing WORKFLOW_SAFE dependency`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Only register activity-only dependency
        app.dependencies {
            activityOnly<TestService> { TestServiceImpl("Should not be accessible") }
        }

        val mockContext = MockWorkflowContext(parentScope = app)

        // This should fail because workflowDependencies looks for WORKFLOW_SAFE scope,
        // but we only registered ACTIVITY_ONLY scope
        assertFailsWith<MissingDependencyException> {
            mockContext.getWorkflowService()
        }
    }

    @Test
    fun `delegates work with task queue overrides`() {
        var taskQueueScope: AttributeScope? = null

        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // App-level dependency
        app.dependencies {
            workflowSafe<TestService> { TestServiceImpl("From app") }
            workflowSafe<ConfigService> { ConfigServiceImpl("app-config") }
        }

        // Task queue overrides TestService but not ConfigService
        app.taskQueue("test-queue") {
            dependencies {
                workflowSafe<TestService> { TestServiceImpl("From task queue") }
            }
            taskQueueScope = this
        }

        // Create mock context with task queue as parent (which has app as its parent)
        val mockContext = MockWorkflowContext(parentScope = taskQueueScope!!)

        // TestService should come from task queue (overridden)
        assertEquals("From task queue", mockContext.getWorkflowService().getMessage())
        // ConfigService should come from app (not overridden)
        assertEquals("app-config", mockContext.getWorkflowConfig().getConfig())
    }

    @Test
    fun `workflowDependency with qualifier resolves correctly`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            workflowSafe<TestService>(qualifier = "primary") { TestServiceImpl("Primary service") }
            workflowSafe<TestService>(qualifier = "secondary") { TestServiceImpl("Secondary service") }
        }

        val mockContext = MockWorkflowContext(parentScope = app)

        // Helper functions for qualified access
        fun MockWorkflowContext.getPrimaryService(): TestService {
            val service: TestService by workflowDependency("primary")
            return service
        }

        fun MockWorkflowContext.getSecondaryService(): TestService {
            val service: TestService by workflowDependency("secondary")
            return service
        }

        assertEquals("Primary service", mockContext.getPrimaryService().getMessage())
        assertEquals("Secondary service", mockContext.getSecondaryService().getMessage())
    }

    @Test
    fun `activityDependency with qualifier resolves correctly`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.dependencies {
            activityOnly<TestService>(qualifier = "http") { TestServiceImpl("HTTP client") }
            activityOnly<TestService>(qualifier = "grpc") { TestServiceImpl("GRPC client") }
        }

        val mockContext = MockActivityContext(parentScope = app)

        // Helper functions for qualified access
        fun MockActivityContext.getHttpService(): TestService {
            val service: TestService by activityDependency("http")
            return service
        }

        fun MockActivityContext.getGrpcService(): TestService {
            val service: TestService by activityDependency("grpc")
            return service
        }

        assertEquals("HTTP client", mockContext.getHttpService().getMessage())
        assertEquals("GRPC client", mockContext.getGrpcService().getMessage())
    }

    // ===========================================
    // Mock Context Implementations for Testing
    // ===========================================

    /**
     * Mock WorkflowContext that implements ExecutionScope for testing property delegates.
     */
    private class MockWorkflowContext(
        override val parentScope: AttributeScope?,
    ) : WorkflowContext,
        ExecutionScope {
        override val attributes: Attributes = Attributes(concurrent = false)
        override val isWorkflowContext: Boolean = true

        // Required WorkflowContext properties
        override val serializer: PayloadSerializer = CompositePayloadSerializer.default()
        override val info: WorkflowInfo =
            WorkflowInfo(
                workflowId = "test-workflow-id",
                runId = "test-run-id",
                workflowType = "TestWorkflow",
                taskQueue = "test-queue",
                namespace = "test",
                attempt = 1,
                startTime = Instant.fromEpochMilliseconds(0),
            )
        override val historyLength: Int = 0
        override val historySizeBytes: Long = 0

        override val coroutineContext: CoroutineContext = Dispatchers.Unconfined

        override val isReplaying: Boolean
            get() = false

        // Stubs for required methods
        override suspend fun startActivityWithPayloads(
            activityType: String,
            args: TemporalPayloads,
            options: com.surrealdev.temporal.workflow.ActivityOptions,
        ): com.surrealdev.temporal.workflow.RemoteActivityHandle = TODO("Not needed for DI tests")

        override suspend fun startLocalActivityWithPayloads(
            activityType: String,
            args: TemporalPayloads,
            options: com.surrealdev.temporal.workflow.LocalActivityOptions,
        ): com.surrealdev.temporal.workflow.LocalActivityHandle = TODO("Not needed for DI tests")

        override suspend fun sleep(duration: Duration) = TODO("Not needed for DI tests")

        override suspend fun awaitCondition(condition: () -> Boolean) = TODO("Not needed for DI tests")

        override suspend fun awaitCondition(
            timeout: Duration,
            timeoutSummary: String?,
            condition: () -> Boolean,
        ) = TODO("Not needed for DI tests")

        override fun now(): Instant = Instant.fromEpochMilliseconds(0)

        override fun randomUuid(): String = "mock-uuid"

        override fun patched(patchId: String): Boolean = true

        override fun isContinueAsNewSuggested(): Boolean = false

        override val continueAsNewSuggestedReasons: Set<SuggestContinueAsNewReason> = setOf()

        override suspend fun startChildWorkflowWithPayloads(
            workflowType: String,
            args: TemporalPayloads,
            options: com.surrealdev.temporal.workflow.ChildWorkflowOptions,
        ): com.surrealdev.temporal.workflow.ChildWorkflowHandle = TODO("Not needed for DI tests")

        override fun setQueryHandlerWithPayloads(
            name: String,
            handler: (suspend (TemporalPayloads) -> TemporalPayload)?,
        ) = TODO("Not needed for DI tests")

        override fun setDynamicQueryHandlerWithPayloads(
            handler: (suspend (queryType: String, args: TemporalPayloads) -> TemporalPayload)?,
        ) = TODO("Not needed for DI tests")

        override fun setSignalHandlerWithPayloads(
            name: String,
            handler: (suspend (TemporalPayloads) -> Unit)?,
        ) = TODO("Not needed for DI tests")

        override fun setDynamicSignalHandlerWithPayloads(
            handler: (suspend (signalName: String, args: TemporalPayloads) -> Unit)?,
        ) = TODO("Not needed for DI tests")

        override fun setUpdateHandlerWithPayloads(
            name: String,
            handler: (suspend (TemporalPayloads) -> TemporalPayload)?,
            validator: ((TemporalPayloads) -> Unit)?,
        ) = TODO("Not needed for DI tests")

        override fun setDynamicUpdateHandlerWithPayloads(
            handler: (suspend (updateName: String, args: TemporalPayloads) -> TemporalPayload)?,
            validator: ((updateName: String, args: TemporalPayloads) -> Unit)?,
        ) = TODO("Not needed for DI tests")

        override suspend fun upsertSearchAttributes(attributes: com.surrealdev.temporal.common.TypedSearchAttributes) =
            TODO("Not needed for DI tests")

        override fun getExternalWorkflowHandle(
            workflowId: String,
            runId: String?,
        ): com.surrealdev.temporal.workflow.ExternalWorkflowHandle = TODO("Not needed for DI tests")

        override suspend fun continueAsNewInternal(
            options: com.surrealdev.temporal.workflow.ContinueAsNewOptions,
            typedArgs: List<Pair<kotlin.reflect.KType, Any?>>,
        ): Nothing = TODO("Not needed for DI tests")
    }

    /**
     * Mock ActivityContext that implements ExecutionScope for testing property delegates.
     */
    private class MockActivityContext(
        override val parentScope: AttributeScope?,
    ) : ActivityContext,
        ExecutionScope {
        override val attributes: Attributes = Attributes(concurrent = false)
        override val isWorkflowContext: Boolean = false

        // Required ActivityContext properties
        override val serializer: PayloadSerializer = CompositePayloadSerializer.default()
        override val info: ActivityInfo =
            ActivityInfo(
                activityId = "test-activity-id",
                activityType = "TestActivity",
                taskQueue = "test-queue",
                attempt = 1,
                startTime = Instant.fromEpochMilliseconds(0),
                deadline = null,
                heartbeatDetails = null,
                workflowInfo =
                    ActivityWorkflowInfo(
                        workflowId = "test-workflow-id",
                        runId = "test-run-id",
                        workflowType = "TestWorkflow",
                        namespace = "test",
                    ),
            )
        override val isCancellationRequested: Boolean = false

        override val coroutineContext: CoroutineContext = Dispatchers.Unconfined

        // Stubs for required methods
        override suspend fun heartbeatWithPayload(details: TemporalPayload?) {}

        override fun ensureNotCancelled() {}
    }
}
