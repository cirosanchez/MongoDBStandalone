package dev.cirosanchez.mongodb

import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer

interface Adapter<T : Any> : JsonSerializer<T>, JsonDeserializer<T> {

    fun getTypeClass(): Class<T>
}