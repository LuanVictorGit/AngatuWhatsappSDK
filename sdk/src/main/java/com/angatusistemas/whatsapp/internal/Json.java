package com.angatusistemas.whatsapp.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Null-tolerant reads and writes on Gson trees, so decoding a frame never throws on a missing or
 * null field.
 *
 * @author Angatu Sistemas
 */
public final class Json {

    private Json() {
    }

    public static String str(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    public static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : fallback;
    }

    public static long number(JsonObject object, String key, long fallback) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return primitive.getAsLong();
        }
        try {
            return Long.parseLong(primitive.getAsString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static JsonObject object(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    public static JsonArray array(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    public static List<String> strings(JsonObject object, String key) {
        JsonArray array = array(object, key);
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    public static JsonObject put(JsonObject object, String key, String value) {
        if (value != null) {
            object.addProperty(key, value);
        }
        return object;
    }

    public static JsonArray stringArray(Collection<String> values) {
        JsonArray array = new JsonArray(values.size());
        values.forEach(array::add);
        return array;
    }
}
