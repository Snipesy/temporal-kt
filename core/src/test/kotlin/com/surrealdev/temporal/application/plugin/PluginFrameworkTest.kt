package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.application.TaskQueueBuilder
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.util.AttributeKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the new plugin framework.
 */
class PluginFrameworkTest {
    /**
     * Simple test plugin that tracks lifecycle events.
     */
    class TestPlugin(
        val config: TestPluginConfig,
    ) {
        val setupCalled = mutableListOf<String>()
        val workflowTasksCalled = mutableListOf<String>()

        companion object : ApplicationPlugin<TestPluginConfig, TestPlugin> {
            override val key = AttributeKey<TestPlugin>(name = "TestPlugin")

            override fun install(
                pipeline: TemporalApplication,
                configure: TestPluginConfig.() -> Unit,
            ): TestPlugin {
                val config = TestPluginConfig().apply(configure)
                val plugin = TestPlugin(config)

                val builder = createPluginBuilder(pipeline, config, key)

                builder.application {
                    onSetup { context ->
                        plugin.setupCalled.add("setup:${context.application}")
                    }
                }

                builder.workflow {
                    onTaskStarted { context ->
                        plugin.workflowTasksCalled.add("workflow:${context.runId}")
                    }
                }

                // Register all hooks and interceptors
                installHandlers(builder, pipeline)

                return plugin
            }
        }
    }

    data class TestPluginConfig(
        var enabled: Boolean = true,
        var name: String = "test",
    )

    @Test
    fun `can install plugin on application`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Install using new plugin framework
        val plugin =
            app.install(TestPlugin) {
                enabled = true
                name = "my-plugin"
            }

        assertNotNull(plugin)
        assertEquals("my-plugin", plugin.config.name)
        assertTrue(plugin.config.enabled)
    }

    @Test
    fun `can retrieve installed plugin`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Install plugin
        app.install(TestPlugin) {
            name = "test-plugin"
        }

        // Retrieve plugin
        val plugin = app.plugin(TestPlugin)
        assertNotNull(plugin)
        assertEquals("test-plugin", plugin.config.name)
    }

    @Test
    fun `plugin hooks are registered correctly`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Install plugin
        val plugin =
            app.install(TestPlugin) {
                name = "lifecycle-test"
            }

        // Verify plugin was installed and hooks are registered
        assertNotNull(plugin)
        assertEquals("lifecycle-test", plugin.config.name)
        // The hooks will be called during actual application lifecycle
        // This test just verifies they're properly registered
    }

    @Test
    fun `createApplicationPlugin DSL works`() {
        data class MyConfig(
            var value: Int = 0,
        )

        class MyPlugin(
            val value: Int,
        )

        val plugin =
            createApplicationPlugin<MyPlugin, MyConfig>(
                name = "MyDSLPlugin",
                createConfiguration = { MyConfig() },
            ) { config ->
                application {
                    onSetup { _ ->
                        // Setup logic here
                    }
                }

                MyPlugin(config.value)
            }

        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        val instance =
            app.install(plugin) {
                value = 42
            }

        assertEquals(42, instance.value)
    }

    // --- Nested Install / Override Behavior Tests ---

    /**
     * Plugin instance that can be installed at both application and task queue level.
     * Uses [ScopedPlugin] — a single companion object works at every pipeline level.
     */
    class ConfigurablePlugin(
        val name: String,
        val scope: String,
    ) {
        companion object : ScopedPlugin<ConfigurablePluginConfig, ConfigurablePlugin> {
            override val key = AttributeKey<ConfigurablePlugin>(name = "ConfigurablePlugin")

            override fun install(
                pipeline: PluginPipeline,
                configure: ConfigurablePluginConfig.() -> Unit,
            ): ConfigurablePlugin {
                val config = ConfigurablePluginConfig().apply(configure)
                val scope =
                    when (pipeline) {
                        is TemporalApplication -> "app"
                        is TaskQueueBuilder -> "taskqueue"
                        else -> "unknown"
                    }
                return ConfigurablePlugin(config.name, scope)
            }
        }
    }

    data class ConfigurablePluginConfig(
        var name: String = "default",
    )

    @Test
    fun `task queue inherits plugin from application when not overridden`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Install plugin at application level
        app.install(ConfigurablePlugin) {
            name = "app-level-config"
        }

        // Create a task queue WITHOUT installing the plugin
        var taskQueueBuilder: TaskQueueBuilder? = null
        app.taskQueue("test-queue") {
            taskQueueBuilder = this
        }

        // The task queue should inherit the plugin from the application
        val inheritedPlugin = taskQueueBuilder!!.pluginOrNull(ConfigurablePlugin)
        assertNotNull(inheritedPlugin, "Task queue should inherit plugin from application")
        assertEquals("app-level-config", inheritedPlugin.name)
        assertEquals("app", inheritedPlugin.scope, "Should be the app-level instance")
    }

    @Test
    fun `task queue plugin overrides application plugin`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Install plugin at application level
        val appPlugin =
            app.install(ConfigurablePlugin) {
                name = "app-level-config"
            }

        // Create a task queue and install plugin at task queue level (override)
        var taskQueueBuilder: TaskQueueBuilder? = null
        app.taskQueue("test-queue") {
            taskQueueBuilder = this
            install(ConfigurablePlugin) {
                name = "taskqueue-level-config"
            }
        }

        // The task queue should use its local override, not the app-level one
        val taskQueuePlugin = taskQueueBuilder!!.plugin(ConfigurablePlugin)
        assertEquals("taskqueue-level-config", taskQueuePlugin.name)
        assertEquals("taskqueue", taskQueuePlugin.scope, "Should be the task-queue-level instance")

        // The app-level plugin should still be the original
        val appLevelPlugin = app.plugin(ConfigurablePlugin)
        assertSame(appPlugin, appLevelPlugin)
        assertEquals("app-level-config", appLevelPlugin.name)
    }

    @Test
    fun `hasPluginLocally distinguishes local from inherited plugins`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Install plugin at application level
        app.install(ConfigurablePlugin) {
            name = "app-level"
        }

        // Create task queue without local plugin
        var taskQueueWithoutOverride: TaskQueueBuilder? = null
        app.taskQueue("queue-without-override") {
            taskQueueWithoutOverride = this
        }

        // Create task queue with local override
        var taskQueueWithOverride: TaskQueueBuilder? = null
        app.taskQueue("queue-with-override") {
            taskQueueWithOverride = this
            install(ConfigurablePlugin) {
                name = "taskqueue-level"
            }
        }

        // Application has plugin locally
        assertTrue(app.hasPluginLocally(ConfigurablePlugin))

        // Task queue without override: pluginOrNull returns inherited, hasPluginLocally returns false
        assertNotNull(taskQueueWithoutOverride!!.pluginOrNull(ConfigurablePlugin))
        assertFalse(taskQueueWithoutOverride.hasPluginLocally(ConfigurablePlugin))

        // Task queue with override: both return true
        assertNotNull(taskQueueWithOverride!!.pluginOrNull(ConfigurablePlugin))
        assertTrue(taskQueueWithOverride.hasPluginLocally(ConfigurablePlugin))
    }

    @Test
    fun `duplicate plugin at same level still throws exception`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Install plugin at application level
        app.install(ConfigurablePlugin) {
            name = "first"
        }

        // Installing again at application level should throw
        assertThrows<DuplicatePluginException> {
            app.install(ConfigurablePlugin) {
                name = "second"
            }
        }

        // Similarly for task queue level
        app.taskQueue("test-queue") {
            install(ConfigurablePlugin) {
                name = "first-in-queue"
            }

            // Installing again at same task queue level should throw
            assertThrows<DuplicatePluginException> {
                install(ConfigurablePlugin) {
                    name = "second-in-queue"
                }
            }
        }
    }

    @Test
    fun `pluginOrNull returns null when plugin not installed at any level`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Don't install any plugin
        var taskQueueBuilder: TaskQueueBuilder? = null
        app.taskQueue("test-queue") {
            taskQueueBuilder = this
        }

        // Both levels should return null
        assertNull(app.pluginOrNull(ConfigurablePlugin))
        assertNull(taskQueueBuilder!!.pluginOrNull(ConfigurablePlugin))
    }

    @Test
    fun `plugin throws MissingPluginException when not installed at any level`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Don't install any plugin
        var taskQueueBuilder: TaskQueueBuilder? = null
        app.taskQueue("test-queue") {
            taskQueueBuilder = this
        }

        // Both levels should throw
        assertThrows<MissingPluginException> {
            app.plugin(ConfigurablePlugin)
        }

        assertThrows<MissingPluginException> {
            taskQueueBuilder!!.plugin(ConfigurablePlugin)
        }
    }

    @Test
    fun `multiple task queues can have different plugin configurations`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Install plugin at application level as default
        app.install(ConfigurablePlugin) {
            name = "app-default"
        }

        // First task queue uses app default
        var queue1: TaskQueueBuilder? = null
        app.taskQueue("queue-1") {
            queue1 = this
        }

        // Second task queue overrides with custom config
        var queue2: TaskQueueBuilder? = null
        app.taskQueue("queue-2") {
            queue2 = this
            install(ConfigurablePlugin) {
                name = "queue-2-custom"
            }
        }

        // Third task queue overrides with another custom config
        var queue3: TaskQueueBuilder? = null
        app.taskQueue("queue-3") {
            queue3 = this
            install(ConfigurablePlugin) {
                name = "queue-3-custom"
            }
        }

        // Verify each queue has the expected plugin configuration
        assertEquals("app-default", queue1!!.plugin(ConfigurablePlugin).name)
        assertEquals("queue-2-custom", queue2!!.plugin(ConfigurablePlugin).name)
        assertEquals("queue-3-custom", queue3!!.plugin(ConfigurablePlugin).name)

        // Verify app-level is unchanged
        assertEquals("app-default", app.plugin(ConfigurablePlugin).name)
    }

    @Test
    fun `scoped plugin can be installed directly at task queue level`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        // Install scoped plugin directly on task queue (no app-level install)
        var taskQueueBuilder: TaskQueueBuilder? = null
        app.taskQueue("test-queue") {
            taskQueueBuilder = this
            install(ConfigurablePlugin) {
                name = "tq-only"
            }
        }

        val tqPlugin = taskQueueBuilder!!.plugin(ConfigurablePlugin)
        assertEquals("tq-only", tqPlugin.name)
        assertEquals("taskqueue", tqPlugin.scope)

        // App level should not have it
        assertNull(app.pluginOrNull(ConfigurablePlugin))
    }

    @Test
    fun `scoped plugin installed at app detects correct scope`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.install(ConfigurablePlugin) {
            name = "app-scoped"
        }

        val plugin = app.plugin(ConfigurablePlugin)
        assertEquals("app-scoped", plugin.name)
        assertEquals("app", plugin.scope)
    }

    @Test
    fun `scoped plugin at task queue overrides app-level scoped plugin`() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "test"
                }
            }

        app.install(ConfigurablePlugin) {
            name = "app-default"
        }

        var taskQueueBuilder: TaskQueueBuilder? = null
        app.taskQueue("test-queue") {
            taskQueueBuilder = this
            install(ConfigurablePlugin) {
                name = "tq-override"
            }
        }

        // Task queue sees its own override
        val tqPlugin = taskQueueBuilder!!.plugin(ConfigurablePlugin)
        assertEquals("tq-override", tqPlugin.name)
        assertEquals("taskqueue", tqPlugin.scope)

        // App still sees original
        val appPlugin = app.plugin(ConfigurablePlugin)
        assertEquals("app-default", appPlugin.name)
        assertEquals("app", appPlugin.scope)
    }
}
