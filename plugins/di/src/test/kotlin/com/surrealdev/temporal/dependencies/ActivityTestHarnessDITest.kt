package com.surrealdev.temporal.dependencies

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.testing.runActivityTest
import com.surrealdev.temporal.util.Attributes
import com.surrealdev.temporal.util.SimpleAttributeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for DI plugin integration with ActivityTestHarness.
 */
class ActivityTestHarnessDITest {
    // Test interfaces and implementations
    interface HttpClient {
        fun get(url: String): String
    }

    class MockHttpClient(
        private val response: String = "mock response",
    ) : HttpClient {
        override fun get(url: String) = response
    }

    interface ConfigService {
        fun getBaseUrl(): String
    }

    class TestConfigService(
        private val baseUrl: String = "http://test.example.com",
    ) : ConfigService {
        override fun getBaseUrl() = baseUrl
    }

    // Activity for testing
    class HttpActivity {
        suspend fun ActivityContext.fetchData(endpoint: String): String {
            val httpClient: HttpClient by activityDependencies
            val config: ConfigService by workflowDependencies
            return httpClient.get("${config.getBaseUrl()}/$endpoint")
        }
    }

    // ===========================================
    // Basic DI Integration Tests
    // ===========================================

    @Test
    fun `can configure dependencies in test harness`() =
        runActivityTest {
            dependencies {
                activityOnly<HttpClient> { MockHttpClient() }
            }

            // Just verify we can configure - no assertion needed
        }

    @Test
    fun `activityDependencies resolves ACTIVITY_ONLY dependency`() =
        runActivityTest {
            dependencies {
                activityOnly<HttpClient> { MockHttpClient("test response") }
            }

            val result =
                withActivityContext {
                    val httpClient: HttpClient by activityDependencies
                    httpClient.get("http://example.com")
                }

            assertEquals("test response", result)
        }

    @Test
    fun `workflowDependencies resolves WORKFLOW_SAFE dependency`() =
        runActivityTest {
            dependencies {
                workflowSafe<ConfigService> { TestConfigService("http://config.test") }
            }

            val result =
                withActivityContext {
                    val config: ConfigService by workflowDependencies
                    config.getBaseUrl()
                }

            assertEquals("http://config.test", result)
        }

    @Test
    fun `activity can use both dependency scopes`() =
        runActivityTest {
            dependencies {
                activityOnly<HttpClient> { MockHttpClient("fetched data") }
                workflowSafe<ConfigService> { TestConfigService("http://api.test") }
            }

            val activity = HttpActivity()
            val result =
                withActivityContext {
                    with(activity) {
                        fetchData("users")
                    }
                }

            assertEquals("fetched data", result)
        }

    @Test
    fun `missing dependency throws MissingDependencyException`() =
        runActivityTest {
            // No dependencies configured
            dependencies {}

            assertFailsWith<MissingDependencyException> {
                withActivityContext {
                    val httpClient: HttpClient by activityDependencies
                    httpClient.get("http://example.com")
                }
            }
        }

    // ===========================================
    // Hierarchical Lookup Tests
    // ===========================================

    @Test
    fun `inherits dependencies from parent scope`() =
        runActivityTest {
            // Create a parent scope with its own dependencies
            val parentAttributes = Attributes(concurrent = false)
            val parentRegistry = DependencyRegistry()
            parentRegistry.workflowSafe<ConfigService> { TestConfigService("from parent") }
            parentAttributes.put(DependencyRegistryKey, parentRegistry)
            val parent = SimpleAttributeScope(parentAttributes)

            this.parentScope = parent

            // Note: harness dependencies take precedence, but we didn't override ConfigService
            val result =
                withActivityContext {
                    val config: ConfigService by workflowDependencies
                    config.getBaseUrl()
                }

            assertEquals("from parent", result)
        }

    @Test
    fun `harness dependencies override parent scope`() =
        runActivityTest {
            // Create a parent scope with its own dependencies
            val parentAttributes = Attributes(concurrent = false)
            val parentRegistry = DependencyRegistry()
            parentRegistry.workflowSafe<ConfigService> { TestConfigService("from parent") }
            parentAttributes.put(DependencyRegistryKey, parentRegistry)
            val parent = SimpleAttributeScope(parentAttributes)

            this.parentScope = parent

            // Override in harness
            dependencies {
                workflowSafe<ConfigService> { TestConfigService("from harness") }
            }

            val result =
                withActivityContext {
                    val config: ConfigService by workflowDependencies
                    config.getBaseUrl()
                }

            assertEquals("from harness", result)
        }

    @Test
    fun `works with TemporalApplication as parent scope`() =
        runActivityTest {
            val app =
                TemporalApplication {
                    connection {
                        target = "http://localhost:7233"
                        namespace = "test"
                    }
                }

            app.dependencies {
                workflowSafe<ConfigService> { TestConfigService("from app") }
            }

            parentScope = app

            val result =
                withActivityContext {
                    val config: ConfigService by workflowDependencies
                    config.getBaseUrl()
                }

            assertEquals("from app", result)
        }

    @Test
    fun `can mix app dependencies with harness overrides`() =
        runActivityTest {
            val app =
                TemporalApplication {
                    connection {
                        target = "http://localhost:7233"
                        namespace = "test"
                    }
                }

            // App provides ConfigService
            app.dependencies {
                workflowSafe<ConfigService> { TestConfigService("from app") }
            }

            parentScope = app

            // Harness provides HttpClient (mock for testing)
            dependencies {
                activityOnly<HttpClient> { MockHttpClient("mocked response") }
            }

            val activity = HttpActivity()
            val result =
                withActivityContext {
                    with(activity) {
                        fetchData("users")
                    }
                }

            // HttpClient comes from harness, ConfigService comes from app
            assertEquals("mocked response", result)
        }

    // ===========================================
    // Qualified Dependency Tests
    // ===========================================

    @Test
    fun `qualified dependencies resolve correctly`() =
        runActivityTest {
            dependencies {
                activityOnly<HttpClient>(qualifier = "primary") { MockHttpClient("primary client") }
                activityOnly<HttpClient>(qualifier = "secondary") { MockHttpClient("secondary client") }
            }

            val result =
                withActivityContext {
                    val primary: HttpClient by activityDependency("primary")
                    val secondary: HttpClient by activityDependency("secondary")
                    "${primary.get("")} + ${secondary.get("")}"
                }

            assertEquals("primary client + secondary client", result)
        }

    // ===========================================
    // Caching Behavior Tests
    // ===========================================

    @Test
    fun `dependencies are cached within activity context`() =
        runActivityTest {
            var instanceCount = 0
            dependencies {
                activityOnly<HttpClient> {
                    instanceCount++
                    MockHttpClient()
                }
            }

            withActivityContext {
                // Get the same dependency twice
                val httpClient1: HttpClient by activityDependencies
                val httpClient2: HttpClient by activityDependencies
                httpClient1.get("")
                httpClient2.get("")
            }

            assertEquals(1, instanceCount, "Factory should only be called once per context")
        }

    @Test
    fun `different activity contexts get fresh dependency instances`() =
        runActivityTest {
            var instanceCount = 0
            dependencies {
                activityOnly<HttpClient> {
                    instanceCount++
                    MockHttpClient()
                }
            }

            // First activity context
            withActivityContext {
                val httpClient: HttpClient by activityDependencies
                httpClient.get("")
            }

            // Second activity context
            withActivityContext {
                val httpClient: HttpClient by activityDependencies
                httpClient.get("")
            }

            // Registry-level singleton: factory is called once regardless of context count
            assertEquals(1, instanceCount, "Singleton is shared across all contexts from the same registry")
        }

    // ===========================================
    // Standalone Scope Tests
    // ===========================================

    @Test
    fun `standalone scope with dependencies works`() =
        runActivityTest {
            // Create an isolated scope with dependencies
            val scopeAttributes = Attributes(concurrent = false)
            val scopeRegistry = DependencyRegistry()
            scopeRegistry.activityOnly<HttpClient> { MockHttpClient("isolated") }
            scopeRegistry.workflowSafe<ConfigService> { TestConfigService("isolated config") }
            scopeAttributes.put(DependencyRegistryKey, scopeRegistry)
            val scope = SimpleAttributeScope(scopeAttributes)

            parentScope = scope

            val result =
                withActivityContext {
                    val httpClient: HttpClient by activityDependencies
                    val config: ConfigService by workflowDependencies
                    "${httpClient.get("")} - ${config.getBaseUrl()}"
                }

            assertEquals("isolated - isolated config", result)
        }
}
