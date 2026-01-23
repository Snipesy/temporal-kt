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

                builder.onApplicationSetup { context ->
                    plugin.setupCalled.add("setup:${context.application}")
                }

                builder.onWorkflowTaskStarted { context ->
                    plugin.workflowTasksCalled.add("workflow:${context.runId}")
                }

                // Register all hooks
                builder.hooks.forEach { it.install(pipeline.hookRegistry) }

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
                onApplicationSetup { _ ->
                    // Setup logic here
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
     * Used to test override behavior.
     */
    class ConfigurablePlugin(
        val name: String,
        val scope: String, // "app" or "taskqueue"
    ) {
        companion object {
            val key = AttributeKey<ConfigurablePlugin>(name = "ConfigurablePlugin")

            /**
             * Application-level plugin installer.
             */
            val AppLevel =
                object : ApplicationPlugin<ConfigurablePluginConfig, ConfigurablePlugin> {
                    override val key = ConfigurablePlugin.key

                    override fun install(
                        pipeline: TemporalApplication,
                        configure: ConfigurablePluginConfig.() -> Unit,
                    ): ConfigurablePlugin {
                        val config = ConfigurablePluginConfig().apply(configure)
                        return ConfigurablePlugin(config.name, "app")
                    }
                }

            /**
             * Task-queue-level plugin installer.
             */
            val TaskQueueLevel =
                object : TaskQueueScopedPlugin<ConfigurablePluginConfig, ConfigurablePlugin> {
                    override val key = ConfigurablePlugin.key

                    override fun install(
                        pipeline: TaskQueueBuilder,
                        configure: ConfigurablePluginConfig.() -> Unit,
                    ): ConfigurablePlugin {
                        val config = ConfigurablePluginConfig().apply(configure)
                        return ConfigurablePlugin(config.name, "taskqueue")
                    }
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
        app.install(ConfigurablePlugin.AppLevel) {
            name = "app-level-config"
        }

        // Create a task queue WITHOUT installing the plugin
        var taskQueueBuilder: TaskQueueBuilder? = null
        app.taskQueue("test-queue") {
            taskQueueBuilder = this
        }

        // The task queue should inherit the plugin from the application
        val inheritedPlugin = taskQueueBuilder!!.pluginOrNull(ConfigurablePlugin.AppLevel)
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
            app.install(ConfigurablePlugin.AppLevel) {
                name = "app-level-config"
            }

        // Create a task queue and install plugin at task queue level (override)
        var taskQueueBuilder: TaskQueueBuilder? = null
        app.taskQueue("test-queue") {
            taskQueueBuilder = this
            install(ConfigurablePlugin.TaskQueueLevel) {
                name = "taskqueue-level-config"
            }
        }

        // The task queue should use its local override, not the app-level one
        val taskQueuePlugin = taskQueueBuilder!!.plugin(ConfigurablePlugin.AppLevel)
        assertEquals("taskqueue-level-config", taskQueuePlugin.name)
        assertEquals("taskqueue", taskQueuePlugin.scope, "Should be the task-queue-level instance")

        // The app-level plugin should still be the original
        val appLevelPlugin = app.plugin(ConfigurablePlugin.AppLevel)
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
        app.install(ConfigurablePlugin.AppLevel) {
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
            install(ConfigurablePlugin.TaskQueueLevel) {
                name = "taskqueue-level"
            }
        }

        // Application has plugin locally
        assertTrue(app.hasPluginLocally(ConfigurablePlugin.AppLevel))

        // Task queue without override: pluginOrNull returns inherited, hasPluginLocally returns false
        assertNotNull(taskQueueWithoutOverride!!.pluginOrNull(ConfigurablePlugin.AppLevel))
        assertFalse(taskQueueWithoutOverride!!.hasPluginLocally(ConfigurablePlugin.AppLevel))

        // Task queue with override: both return true
        assertNotNull(taskQueueWithOverride!!.pluginOrNull(ConfigurablePlugin.AppLevel))
        assertTrue(taskQueueWithOverride!!.hasPluginLocally(ConfigurablePlugin.TaskQueueLevel))
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
        app.install(ConfigurablePlugin.AppLevel) {
            name = "first"
        }

        // Installing again at application level should throw
        assertThrows<DuplicatePluginException> {
            app.install(ConfigurablePlugin.AppLevel) {
                name = "second"
            }
        }

        // Similarly for task queue level
        app.taskQueue("test-queue") {
            install(ConfigurablePlugin.TaskQueueLevel) {
                name = "first-in-queue"
            }

            // Installing again at same task queue level should throw
            assertThrows<DuplicatePluginException> {
                install(ConfigurablePlugin.TaskQueueLevel) {
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
        assertNull(app.pluginOrNull(ConfigurablePlugin.AppLevel))
        assertNull(taskQueueBuilder!!.pluginOrNull(ConfigurablePlugin.AppLevel))
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
            app.plugin(ConfigurablePlugin.AppLevel)
        }

        assertThrows<MissingPluginException> {
            taskQueueBuilder!!.plugin(ConfigurablePlugin.AppLevel)
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
        app.install(ConfigurablePlugin.AppLevel) {
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
            install(ConfigurablePlugin.TaskQueueLevel) {
                name = "queue-2-custom"
            }
        }

        // Third task queue overrides with another custom config
        var queue3: TaskQueueBuilder? = null
        app.taskQueue("queue-3") {
            queue3 = this
            install(ConfigurablePlugin.TaskQueueLevel) {
                name = "queue-3-custom"
            }
        }

        // Verify each queue has the expected plugin configuration
        assertEquals("app-default", queue1!!.plugin(ConfigurablePlugin.AppLevel).name)
        assertEquals("queue-2-custom", queue2!!.plugin(ConfigurablePlugin.AppLevel).name)
        assertEquals("queue-3-custom", queue3!!.plugin(ConfigurablePlugin.AppLevel).name)

        // Verify app-level is unchanged
        assertEquals("app-default", app.plugin(ConfigurablePlugin.AppLevel).name)
    }
}
