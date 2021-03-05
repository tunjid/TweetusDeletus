#!/usr/bin/env kotlin

@file:DependsOn(artifactsCoordinates = ["org.twitter4j:twitter4j-core:4.0.7"])
@file:DependsOn(artifactsCoordinates = ["com.squareup.moshi:moshi:1.11.0"])
@file:DependsOn(artifactsCoordinates = ["com.opencsv:opencsv:5.3"])

import TweetusDeletus_main.TweetDetails
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import com.squareup.moshi.JsonReader
import okio.Okio
import twitter4j.TwitterException
import twitter4j.TwitterFactory
import twitter4j.conf.ConfigurationBuilder
import java.io.*
import java.nio.charset.Charset
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

// types
val tweetDateFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy")
val cutOffDateFormat = SimpleDateFormat("yyyy/MM/dd")
val camelCaseRegex = "(?<=[a-zA-Z])[A-Z]".toRegex()
val tweetFields = setOf(
    "id",
    "full_text",
    "created_at",
    "retweet_count",
    "favorite_count"
)

typealias TweetDetails = Map<String, String>
typealias TweetCSV = Array<String>

val TweetDetails.id: String by KeyedDelegate(mapper = { it })

val TweetDetails.createdAt: Date by KeyedDelegate(
    nameTransform = ::camelToSnakeCase,
    mapper = tweetDateFormat::parse
)

val TweetDetails.favoriteCount: Int by KeyedDelegate(
    nameTransform = ::camelToSnakeCase,
    mapper = String::toInt
)

val TweetDetails.retweetCount: Int by KeyedDelegate(
    nameTransform = ::camelToSnakeCase,
    mapper = String::toInt
)

val TweetDetails.csv: TweetCSV
    get() = entries.map { "${it.key}:${it.value}" }.toTypedArray()

val TweetCSV.details: TweetDetails
    get() = map { entry: String ->
        val keyValue = entry.split(":")
        keyValue[0] to keyValue[1]
    }.toMap()

// Start script

if (args.size != 1) throw IllegalArgumentException("Invalid script arguments")
val configPath = args[0]

Paths.get(configPath)

val properties = Properties().apply { load(FileInputStream(configPath)) }
println(properties)

val config = Config(properties)
val deleter = tweetDeleter(config)
val tweetsToDelete = readJsonStream(config.tweetsToDeletePath)
val statusWriter = CSVWriter(
    BufferedWriter(
        FileWriter(config.deletedTweetsPath, true)
    )
)
tweetsToDelete
    .filter(config::canDelete)
    .forEachIndexed { index, tweetDetail ->
        val status = deleter.invoke(index, tweetDetail)
        if (status.deleted) statusWriter.writeNext(tweetDetail.csv)
        println(status)
    }

statusWriter.close()

println("DONE")

// End script


// Utility methods

fun readJsonStream(path: String): Sequence<TweetDetails> {
    val source = Okio.buffer(Okio.source(FileInputStream(path)))
    val reader = JsonReader.of(Okio.buffer(source))
    return reader.tweetDetails()
        .plus(generateSequence { reader.close() })
        .filterIsInstance<TweetDetails>()
}

fun csvSequence(path: String): Sequence<TweetCSV> {
    val inputStream = FileInputStream(path)
    val reader = BufferedReader(InputStreamReader(inputStream, Charset.forName("UTF-8")))
    val csvReader = CSVReader(reader)
    return generateSequence(csvReader::readNext)
}

inline fun <reified T> JsonReader.jsonSequence(
    crossinline open: JsonReader.() -> Unit,
    crossinline close: JsonReader.() -> Unit,
    crossinline nextFunction: JsonReader.() -> T?,
): Sequence<T> = generateSequence { open(this) }
    .plus(generateSequence { nextFunction(this) })
    .plus(generateSequence { close(this) })
    .filterIsInstance<T>()

fun tweetDeleter(config: Config): (Int, TweetDetails) -> DeletionStatus {
    val twitter = TwitterFactory(
        ConfigurationBuilder()
            .setDebugEnabled(true)
            .setOAuthConsumerKey(config.consumerKey)
            .setOAuthConsumerSecret(config.consumerSecret)
            .setOAuthAccessToken(config.accessToken)
            .setOAuthAccessTokenSecret(config.accessTokenSecret)
            .build()
    ).instance

    return { index: Int, details: TweetDetails ->
        val tweetId = details.id
        val (deleted: Boolean, message: String) = try {
            twitter.destroyStatus(tweetId.toLong())
            true to "Deleted"
        } catch (e: Exception) {
            (e is TwitterException && e.statusCode == 404) to (e.message ?: "Error deleting")
        }

        DeletionStatus(
            index = index,
            tweetId = tweetId,
            deleted = deleted,
            message = message
        )
    }
}

fun JsonReader.tweetDetails(): Sequence<TweetDetails> = jsonSequence(
    open = JsonReader::beginArray,
    close = JsonReader::endArray
) {
    if (hasNext()) nextTweetDetails()
    else null
}

fun JsonReader.nextTweetDetails(): TweetDetails = jsonSequence(
    open = JsonReader::beginObject,
    close = JsonReader::endObject,
) {
    if (hasNext()) when (nextName()) {
        "tweet" -> nextTweet()
        else -> skipValue()
    }
    else null
}
    .filterIsInstance<TweetDetails>()
    .last()

fun JsonReader.nextTweet(): TweetDetails = jsonSequence(
    open = JsonReader::beginObject,
    close = JsonReader::endObject,
) {
    if (hasNext()) when (val name = nextName()) {
        in tweetFields -> name to nextString()
        else -> skipValue()
    }
    else null
}
    .filterIsInstance<Pair<String, String>>()
    .toMap()

fun camelToSnakeCase(string: String): String =
    camelCaseRegex.replace(string) { "_${it.value}" }.toLowerCase()

data class DeletionStatus(
    val index: Int,
    val tweetId: String,
    val deleted: Boolean,
    val message: String
)

class Config(properties: Properties) {
    val consumerKey: String by properties
    val consumerSecret: String by properties
    val accessToken: String by properties
    val accessTokenSecret: String by properties

    val tweetsToDeletePath: String by properties
    val deletedTweetsPath: String by properties

    private val favoritesThreshold: String by properties
    private val retweetsThreshold: String by properties

    private val cutOffDate: String by properties

    private val deletedTweetIds by lazy {
        File(deletedTweetsPath).apply { if (!exists()) createNewFile() }
        csvSequence(deletedTweetsPath)
            .map { it.details.id }
            .toSet()
    }

    private val cutOff: Date = cutOffDateFormat.parse(cutOffDate)

    fun canDelete(details: TweetDetails): Boolean {
        if (deletedTweetIds.contains(details.id)) return false
        if (details.createdAt > cutOff) return false
        return details.favoriteCount < favoritesThreshold.toInt() && details.retweetCount < retweetsThreshold.toInt()
    }
}

class KeyedDelegate<T>(
    private val nameTransform: (String) -> String = { it },
    private val mapper: (String) -> T,
) : ReadOnlyProperty<TweetDetails, T> {
    override fun getValue(
        thisRef: Map<String, String>,
        property: KProperty<*>
    ): T = mapper(thisRef.getValue(nameTransform(property.name)))
}
