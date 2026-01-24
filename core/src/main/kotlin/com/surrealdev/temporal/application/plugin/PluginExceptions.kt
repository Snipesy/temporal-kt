package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.util.AttributeKey

/**
 * Exception thrown when attempting to install a plugin that is already installed.
 */
class DuplicatePluginException(
    message: String,
) : IllegalStateException(message) {
    constructor(key: AttributeKey<*>) : this("Plugin with key ${key.name} is already installed")
}

/**
 * Exception thrown when attempting to access a plugin that is not installed.
 */
class MissingPluginException(
    message: String,
) : IllegalStateException(message) {
    constructor(key: AttributeKey<*>) : this("Plugin with key ${key.name} is not installed")
}
