package dev.cirosanchez.mongodb

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoClientSettings.getDefaultCodecRegistry
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult
import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import com.mongodb.kotlin.client.MongoIterable
import dev.cirosanchez.mongodb.MongoDB.executor
import org.bson.Document
import org.bson.UuidRepresentation
import org.bson.codecs.configuration.CodecRegistries.fromProviders
import org.bson.conversions.Bson
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField

object MongoDB {

    private lateinit var client: MongoClient
    internal lateinit var database: MongoDatabase
    internal val executor: Executor = Executors.newCachedThreadPool()

    fun mongo(mongo: Settings) {
        println("AAAAAAAAAAAAAAAAAAAAAAAAA")
        client = MongoClient.create(
            MongoClientSettings.builder()
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .codecRegistry(fromProviders(getDefaultCodecRegistry()))
                .applyConnectionString(ConnectionString(mongo.uri))
                .build()
        )
        database = client.getDatabase(mongo.database)
    }

    fun collection(name: String): MongoCollection<Document> = database.getCollection(name)

    @Deprecated("Use collection(clazz: KClass<out MongoSerializable>)", ReplaceWith("collection(`class`)"))
    fun <T : Any> collection(name: String, `class`: Class<T>): MongoCollection<T> =
        database.getCollection(name, `class`)

    data class Settings(val uri: String, val database: String)

    val collections = mutableMapOf<KClass<out MongoSerializable>, TwilightMongoCollection<out MongoSerializable>>()

    inline fun <reified T : MongoSerializable> collection(
        name: String = T::class.simpleName!!.pluralize().formatCase(Case.CAMEL)
    ): TwilightMongoCollection<T> {
        @Suppress("unchecked_cast")
        return collections.getOrPut(T::class) { TwilightMongoCollection(T::class, name) } as TwilightMongoCollection<T>
    }

    fun collection(clazz: KClass<out MongoSerializable>): TwilightMongoCollection<out MongoSerializable> {
        return collections.getOrPut(clazz) {
            TwilightMongoCollection(
                clazz,
                clazz.simpleName!!.pluralize().formatCase(Case.CAMEL)
            )
        }
    }

}

class TwilightMongoCollection<T : MongoSerializable>(
    private val clazz: KClass<out MongoSerializable>,
    val name: String
) {

    val idField = IdField(clazz)
    val documents: MongoCollection<Document> = MongoDB.database.getCollection(name, Document::class.java)

    fun saveSync(serializable: MongoSerializable): UpdateResult = with(serializable.toDocument()) {
        documents.replaceOne(
            eq(idField.name, this[idField.name]),
            this,
            ReplaceOptions().upsert(true)
        )
    }

    fun save(serializable: MongoSerializable): CompletableFuture<UpdateResult> =
        CompletableFuture.supplyAsync({ saveSync(serializable) }, executor)

    fun findSync(filter: Bson? = null): MongoIterable<T> =
        (if (filter == null) documents.find() else documents.find(filter)).map {
            @Suppress("unchecked_cast")
            getGson().fromJson(it.toJson(), clazz.java) as T
        }

    fun find(filter: Bson? = null): CompletableFuture<MongoIterable<T>> =
        CompletableFuture.supplyAsync({ findSync(filter) }, executor)

    fun findByIdSync(id: Any): MongoIterable<T> = findSync(eq(idField.name, id))

    fun findById(id: Any): CompletableFuture<MongoIterable<T>> =
        CompletableFuture.supplyAsync({ findByIdSync(id) }, executor)

    fun deleteSync(filter: Bson): DeleteResult = documents.deleteMany(filter)

    fun delete(filter: Bson): CompletableFuture<DeleteResult> =
        CompletableFuture.supplyAsync({ deleteSync(filter) }, executor)

    fun deleteByIdSync(id: Any): DeleteResult = deleteSync(eq(idField.name, id))

    fun deleteById(id: Any): CompletableFuture<DeleteResult> =
        CompletableFuture.supplyAsync({ deleteByIdSync(id) }, executor)

}

interface MongoSerializable {
    fun saveSync(): UpdateResult = MongoDB.collection(this::class).saveSync(this)

    fun save(): CompletableFuture<UpdateResult> = MongoDB.collection(this::class).save(this)

    fun deleteSync(): DeleteResult = with(MongoDB.collection(this::class)) {
        deleteSync(eq(idField.name, idField.value(this@MongoSerializable)))
    }

    fun delete(): CompletableFuture<DeleteResult> = with(MongoDB.collection(this::class)) {
        delete(eq(idField.name, idField.value(this@MongoSerializable)))
    }

    fun toDocument(): Document = Document.parse(toJson())
}

@Target(AnnotationTarget.FIELD)
annotation class Id

data class IdField(val clazz: KClass<out MongoSerializable>) {

    private val idField: KProperty1<out MongoSerializable, *>
    val name: String
    val type: KType

    init {
        val idFields = clazz.declaredMemberProperties.filter { it.javaField?.isAnnotationPresent(Id::class.java) == true }
        println(idFields)

        require(idFields.size == 1) {
            when (idFields.size) {
                0 -> "Class does not have a field annotated with @Id"
                else -> "Class must not have more than one field annotated with @Id"
            }
        }

        idField = idFields.first()

        name = idField.name
        type = idField.returnType
    }

    @Suppress("unchecked_cast")
    fun value(instance: MongoSerializable): Any = (idField as KProperty1<Any, *>).get(instance)
        ?: throw IllegalStateException("Field annotated with @Id must not be null")

}

fun Any.toDocument(): Document = Document.parse(toJson())

fun getGson(): Gson {
    val builder = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()

    MongoDBStandalone.get().adapters.forEach { adapter ->
        builder.registerTypeHierarchyAdapter(adapter.getTypeClass(), adapter)
    }

    return builder.create()
}

fun Any.toJson(): String {
    return getGson().toJson(this)
}

infix fun <V> KProperty<V>.eq(other: Any): Bson = eq(this.name, other)

var CASE_DELIMITER_REGEX = Regex("(?<!^)(?=[A-Z])|[_\\-\\s]+")


enum class Case {
    CAMEL,
    SNAKE,
    PASCAL,
}


fun String.formatCase(case: Case): String = CASE_DELIMITER_REGEX.split(this)
    .filter { it.isNotEmpty() }
    .map { it.lowercase() }
    .run {
        when (case) {
            Case.CAMEL -> mapIndexed { index, word ->
                if (index == 0) word else word.capitalizeFirstLetter()
            }.joinToString("")

            Case.SNAKE -> joinToString("_")

            Case.PASCAL -> joinToString("") { it.capitalizeFirstLetter() }
        }
    }

fun String.capitalizeFirstLetter(): String = replaceFirstChar {
    if (it.isLowerCase()) it.titlecase() else it.toString()
}

val IRREGULAR_NOUNS = mapOf(
    "man" to "men",
    "woman" to "women",
    "child" to "children",
    "tooth" to "teeth",
    "foot" to "feet",
    "mouse" to "mice",
    "person" to "people",
    "goose" to "geese",
    "ox" to "oxen",
    "leaf" to "leaves",
    "sheep" to "sheep",
    "deer" to "deer",
    "fish" to "fish",
    "moose" to "moose",
    "aircraft" to "aircraft",
    "hovercraft" to "hovercraft",
    "spacecraft" to "spacecraft",
    "watercraft" to "watercraft",
    "offspring" to "offspring",
    "species" to "species",
    "series" to "series",
)

fun String.pluralize(): String = IRREGULAR_NOUNS[this]?.let { return it } ?: when {
    endsWith("y") && length > 1 && !this[lastIndex - 1].isVowel() -> dropLast(1) + "ies"
    endsWith("us") -> dropLast(2) + "i"  // Latin origin, e.g., "cactus" to "cacti"
    endsWith("is") -> dropLast(2) + "es" // Greek origin, e.g., "analysis" to "analyses"
    endsWith("ch") || endsWith("sh") || endsWith("x") || endsWith("s") || endsWith("z") -> this + "es"
    else -> this + "s"
}

var VOWELS = listOf('a', 'e', 'i', 'o', 'u')

fun Char.isVowel(): Boolean = lowercaseChar() in VOWELS