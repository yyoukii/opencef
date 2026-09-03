package com.opencef.core

interface MessageSerializer {
    fun toJson(data: Map<String, Any?>): String

    // Malformed input is an explicit error, not an empty result: implementations
    // must throw rather than silently return an empty or partial map.
    fun fromJson(json: String): Map<String, Any?>
}
