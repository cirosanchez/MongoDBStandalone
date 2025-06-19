package dev.cirosanchez.mongodb

class MongoDBStandalone {
    var mongoURI: String = ""
    var mongoDB: String = ""
    var adapters: MutableList<Adapter<Any>> = mutableListOf()

    companion object {
        lateinit var instance: MongoDBStandalone
        fun get(): MongoDBStandalone {
            return instance
        }
    }

    init {
        instance = this
    }

    fun setupMongo(){
        MongoDB.mongo(MongoDB.Settings(mongoURI, mongoDB))
    }
}

fun mongoDBStandalone(unit: MongoDBStandalone.() -> Unit = {}): MongoDBStandalone {
    return MongoDBStandalone().apply {
        unit()
        setupMongo()
    }
}