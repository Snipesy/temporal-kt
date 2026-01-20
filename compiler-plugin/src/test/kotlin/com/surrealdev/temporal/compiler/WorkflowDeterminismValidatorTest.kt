package com.surrealdev.temporal.compiler

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the WorkflowDeterminismValidator compiler plugin.
 *
 * These tests actually compile test source files with the plugin enabled
 * and verify that determinism violations are detected at compile time.
 */
class WorkflowDeterminismValidatorTest {
    private val testDataPath = File("src/test/resources/testData/determinism/coroutine")
    private val harness = CompilerTestHarness()

    // ===== Tests for INVALID workflows (should fail compilation) =====

    @Test
    fun `GlobalScope async usage causes compilation error`() {
        val sourceFile = File(testDataPath, "GlobalScopeUsage.kt")
        assertTrue(sourceFile.exists(), "Test file should exist: ${sourceFile.absolutePath}")

        val result = harness.compileWithTemporalPlugin(sourceFile)

        assertFalse(result.success, "Compilation should fail for GlobalScope.async usage")
        assertContains(
            result.allErrors,
            "GlobalScope",
            message = "Error should mention GlobalScope. Errors: ${result.allErrors}",
        )
    }

    @Test
    fun `GlobalScope launch usage causes compilation error`() {
        val sourceFile = File(testDataPath, "GlobalScopeLaunch.kt")
        assertTrue(sourceFile.exists(), "Test file should exist: ${sourceFile.absolutePath}")

        val result = harness.compileWithTemporalPlugin(sourceFile)

        assertFalse(result.success, "Compilation should fail for GlobalScope.launch usage")
        assertContains(
            result.allErrors,
            "GlobalScope",
            message = "Error should mention GlobalScope. Errors: ${result.allErrors}",
        )
    }

    @Test
    fun `withContext Dispatchers IO causes compilation error`() {
        val sourceFile = File(testDataPath, "WithContextIoUsage.kt")
        assertTrue(sourceFile.exists(), "Test file should exist: ${sourceFile.absolutePath}")

        val result = harness.compileWithTemporalPlugin(sourceFile)

        assertFalse(result.success, "Compilation should fail for withContext(Dispatchers.IO)")
        assertTrue(
            result.allErrors.contains("withContext") ||
                result.allErrors.contains("Dispatchers") ||
                result.allErrors.contains("IO"),
            "Error should mention withContext or Dispatchers.IO. Errors: ${result.allErrors}",
        )
    }

    @Test
    fun `withContext Dispatchers Default causes compilation error`() {
        val sourceFile = File(testDataPath, "DispatchersDefaultUsage.kt")
        assertTrue(sourceFile.exists(), "Test file should exist: ${sourceFile.absolutePath}")

        val result = harness.compileWithTemporalPlugin(sourceFile)

        assertFalse(result.success, "Compilation should fail for withContext(Dispatchers.Default)")
        assertTrue(
            result.allErrors.contains("withContext") ||
                result.allErrors.contains("Dispatchers") ||
                result.allErrors.contains("Default"),
            "Error should mention withContext or Dispatchers.Default. Errors: ${result.allErrors}",
        )
    }

    // ===== Tests for VALID workflows (should compile successfully) =====

    @Test
    fun `valid workflow with WorkflowContext async compiles successfully`() {
        val sourceFile = File(testDataPath, "ValidWorkflow.kt")
        assertTrue(sourceFile.exists(), "Test file should exist: ${sourceFile.absolutePath}")

        val result = harness.compileWithTemporalPlugin(sourceFile)

        assertTrue(
            result.success,
            "Valid workflow should compile successfully. Errors: ${result.allErrors}",
        )
    }

    @Test
    fun `correct async and launch usage compiles successfully`() {
        val sourceFile = File(testDataPath, "CorrectAsyncUsage.kt")
        assertTrue(sourceFile.exists(), "Test file should exist: ${sourceFile.absolutePath}")

        val result = harness.compileWithTemporalPlugin(sourceFile)

        assertTrue(
            result.success,
            "Correct async/launch usage should compile successfully. Errors: ${result.allErrors}",
        )
    }

    @Test
    fun `non-workflow class with GlobalScope and Dispatchers compiles successfully`() {
        val sourceFile = File(testDataPath, "NonWorkflowClass.kt")
        assertTrue(sourceFile.exists(), "Test file should exist: ${sourceFile.absolutePath}")

        val result = harness.compileWithTemporalPlugin(sourceFile)

        assertTrue(
            result.success,
            "Non-workflow class should compile successfully even with GlobalScope. Errors: ${result.allErrors}",
        )
    }

    @Test
    fun `indirect Deferred usage with GlobalScope async causes compilation error`() {
        val sourceFile = File(testDataPath, "IndirectDefferedUsage.kt")
        assertTrue(sourceFile.exists(), "Test file should exist: ${sourceFile.absolutePath}")

        val result = harness.compileWithTemporalPlugin(sourceFile)

        assertFalse(result.success, "Indirect GlobalScope.async usage should fail compilation")
        assertContains(
            result.allErrors,
            "GlobalScope",
            message = "Error should mention GlobalScope. Errors: ${result.allErrors}",
        )
    }

    @Test
    fun `GlobalScope field usage causes compilation error`() {
        val sourceFile = File(testDataPath, "GlobalScopeFieldUsage.kt")
        assertTrue(sourceFile.exists(), "Test file should exist: ${sourceFile.absolutePath}")

        val result = harness.compileWithTemporalPlugin(sourceFile)

        assertFalse(result.success, "GlobalScope field usage should fail compilation")
        assertContains(
            result.allErrors,
            "GlobalScope",
            message = "Error should mention GlobalScope. Errors: ${result.allErrors}",
        )
    }
}
