package com.surrealdev.temporal.compiler.test

import com.surrealdev.temporal.compiler.test.runners.AbstractTemporalBoxTest
import com.surrealdev.temporal.compiler.test.runners.AbstractTemporalDiagnosticTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(
            testDataRoot = "src/test/resources/testData",
            testsRoot = "src/test-gen",
        ) {
            testClass<AbstractTemporalDiagnosticTest> {
                model("diagnostics")
            }
            testClass<AbstractTemporalBoxTest> {
                model("box")
            }
        }
    }
}
