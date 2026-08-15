package com.danidipp.sneakymail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

object PocketbaseJson {
    inline fun <reified T> encodeCreate(value: T): String {
        val fields = Json.encodeToJsonElement(serializer<T>(), value).jsonObject.toMutableMap()
        if (fields["id"] == JsonNull) fields.remove("id")
        return JsonObject(fields).toString()
    }

    inline fun <reified T> encodeUpdate(value: T): String {
        val fields = Json.encodeToJsonElement(serializer<T>(), value).jsonObject.toMutableMap()
        fields.remove("id")
        return JsonObject(fields).toString()
    }
}
