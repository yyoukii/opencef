package com.opencef.android

import com.opencef.core.MessageSerializer
import org.json.JSONObject

class JsonMessageSerializer : MessageSerializer {

    override fun toJson(data: Map<String, Any?>): String {
        val json = JSONObject()
        for ((key, value) in data) {
            json.put(key, value ?: JSONObject.NULL)
        }
        return json.toString()
    }

    // Throws org.json.JSONException for malformed input (from the
    // JSONObject(String) constructor below). This is intentional: the
    // caller finds out immediately rather than silently getting an empty
    // or partial map back.
    override fun fromJson(json: String): Map<String, Any?> {
        val obj = JSONObject(json)
        val result = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.get(key)
            result[key] = if (value == JSONObject.NULL) null else value
        }
        return result
    }
}
