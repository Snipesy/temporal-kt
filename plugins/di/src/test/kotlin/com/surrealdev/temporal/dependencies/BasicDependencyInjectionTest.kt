package com.surrealdev.temporal.dependencies

import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.taskQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

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

        // Get from queue 1 multiple times (different contexts)
        val queue1Context1 = queue1Registry!!.createWorkflowContext()
        val queue1Context2 = queue1Registry!!.createWorkflowContext()
        queue1Context1.get(key)
        queue1Context2.get(key)

        // Get from queue 2 once
        val queue2Context = queue2Registry!!.createWorkflowContext()
        queue2Context.get(key)

        // Each context creates its own instance
        assertEquals(2, queue1Count, "Queue 1 should have been invoked twice (different contexts)")
        assertEquals(1, queue2Count, "Queue 2 should have been invoked once")
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

        // Same context should cache
        assertSame(service1a, service1b, "Same context should return cached instance")

        // Different contexts should have different instances
        assertNotSame(service1a, service2, "Different contexts should have different instances")
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
}
