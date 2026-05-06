package com.surrealdev.temporal.compiler.fir

import org.jetbrains.kotlin.GeneratedDeclarationKey

/**
 * Origin key for every declaration the [TemporalFirCompanionGenerator] synthesises:
 * - the companion object (when none was user-written)
 * - its `start(...)` and `execute(...)` member functions
 * - its private no-arg constructor (only when the companion itself is plugin-synthesised)
 *
 * The IR body filler matches on this key to know which declarations need its bodies populated.
 */
object TemporalCompanionKey : GeneratedDeclarationKey() {
    override fun toString(): String = "TemporalCompanionKey"
}
