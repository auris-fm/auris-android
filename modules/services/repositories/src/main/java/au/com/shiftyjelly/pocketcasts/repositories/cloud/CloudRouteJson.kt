package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.Type

internal object CloudRouteJson {
    val moshi: Moshi = Moshi.Builder().build()

    val requestBodyAdapter = CloudRouteRequestBodyJsonAdapter(moshi)
    val tokenAdapter: JsonAdapter<CloudRouteTokenPayload> = CloudRouteTokenPayloadJsonAdapter(moshi)
    val doneAdapter: JsonAdapter<CloudRouteDonePayload> = CloudRouteDonePayloadJsonAdapter(moshi)
    val errorAdapter: JsonAdapter<CloudRouteErrorPayload> = CloudRouteErrorPayloadJsonAdapter(moshi)
    val httpErrorAdapter: JsonAdapter<CloudRouteHttpErrorPayload> = CloudRouteHttpErrorPayloadJsonAdapter(moshi)
    val flexibleMapAdapter: JsonAdapter<Map<String, Any?>> = FlexibleMapAdapter()
}

/**
 * Parses JSON objects whose values may be strings, numbers, booleans, or null.
 */
private class FlexibleMapAdapter : JsonAdapter<Map<String, Any?>>() {
    override fun fromJson(reader: JsonReader): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            result[reader.nextName()] = readValue(reader)
        }
        reader.endObject()
        return result
    }

    override fun toJson(writer: JsonWriter, value: Map<String, Any?>?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        for ((key, entryValue) in value) {
            writer.name(key)
            writeValue(writer, entryValue)
        }
        writer.endObject()
    }

    private fun readValue(reader: JsonReader): Any? = when (reader.peek()) {
        JsonReader.Token.NULL -> reader.nextNull()

        JsonReader.Token.BOOLEAN -> reader.nextBoolean()

        JsonReader.Token.NUMBER -> reader.nextDouble().toLongExactOrDouble()

        JsonReader.Token.STRING -> reader.nextString()

        JsonReader.Token.BEGIN_OBJECT -> fromJson(reader)

        JsonReader.Token.BEGIN_ARRAY -> {
            reader.beginArray()
            val items = mutableListOf<Any?>()
            while (reader.hasNext()) {
                items += readValue(reader)
            }
            reader.endArray()
            items
        }

        else -> {
            reader.skipValue()
            null
        }
    }

    private fun writeValue(writer: JsonWriter, value: Any?) {
        when (value) {
            null -> writer.nullValue()

            is Boolean -> writer.value(value)

            is Int -> writer.value(value.toLong())

            is Long -> writer.value(value)

            is Double -> writer.value(value)

            is Float -> writer.value(value.toDouble())

            is String -> writer.value(value)

            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                toJson(writer, value as Map<String, Any?>)
            }

            is List<*> -> {
                writer.beginArray()
                value.forEach { writeValue(writer, it) }
                writer.endArray()
            }

            else -> writer.value(value.toString())
        }
    }
}

private fun Double.toLongExactOrDouble(): Number {
    val longValue = toLong()
    return if (longValue.toDouble() == this) longValue else this
}
