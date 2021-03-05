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
import java.nio.file.Path
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

configPath.asPath() ?: throw IllegalArgumentException("Invalid config path: $configPath")

val properties = Properties().apply { load(FileInputStream(configPath)) }
println(properties)

val config = Config(properties)
val deleter = config.tweetDeleter()
val tweetsToDelete = config.tweetsToDeletePath.tweetJsonSequence()
val statusWriter = CSVWriter(config.deletedTweetsPath.toFile().bufferedWriter())

tweetsToDelete
    .filter(config::canDelete)
    .mapIndexed(deleter)
    .onEach(::println)
    .filter(DeletionStatus::deleted)
    .forEach { status ->
        statusWriter.writeNext(status.tweetDetails.csv)
    }

statusWriter.close()

println("DONE")
// End script


// Utility methods

fun Path.tweetJsonSequence(): Sequence<TweetDetails> {
    val source = Okio.buffer(Okio.source(toFile().inputStream()))
    val reader = JsonReader.of(Okio.buffer(source))
    return reader.tweetDetails()
        .plus(generateSequence(reader::close))
        .filterIsInstance<TweetDetails>()
}

fun Path.tweetCsvSequence(): Sequence<TweetCSV> {
    val inputStream = toFile().inputStream()
    val reader = BufferedReader(InputStreamReader(inputStream, Charset.forName("UTF-8")))
    val csvReader = CSVReader(reader)
    return generateSequence(csvReader::readNext)
}

fun Config.tweetDeleter(): (Int, TweetDetails) -> DeletionStatus {
    val twitter = TwitterFactory(
        ConfigurationBuilder()
            .setDebugEnabled(true)
            .setOAuthConsumerKey(consumerKey)
            .setOAuthConsumerSecret(consumerSecret)
            .setOAuthAccessToken(accessToken)
            .setOAuthAccessTokenSecret(accessTokenSecret)
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
            deleted = deleted,
            message = message,
            tweetDetails = details
        )
    }
}

inline fun <reified T> JsonReader.jsonSequence(
    crossinline open: JsonReader.() -> Unit,
    crossinline close: JsonReader.() -> Unit,
    crossinline nextFunction: JsonReader.() -> T?,
): Sequence<T> = generateSequence { open(this); null }
    .plus(generateSequence { nextFunction(this) })
    .plus(generateSequence { close(this); null })
    .filterIsInstance<T>()

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

fun String.asPath(): Path? = try {
    Paths.get(this)
} catch (e: Exception) {
    null
}

data class DeletionStatus(
    val index: Int,
    val deleted: Boolean,
    val message: String,
    val tweetDetails: TweetDetails
)

class Config(properties: Properties) {

    private val paths = properties.mapValues { (key, value) ->
        if (key.toString().toLowerCase().contains("path")) value.toString().asPath()
            ?: throw IllegalArgumentException("Invalid path for $key: $value")
        else null
    }

    val consumerKey: String by properties
    val consumerSecret: String by properties
    val accessToken: String by properties
    val accessTokenSecret: String by properties

    val tweetsToDeletePath: Path by paths
    val deletedTweetsPath: Path by paths

    private val favoritesThreshold: String by properties
    private val retweetsThreshold: String by properties

    private val cutOffDate: String by properties

    private val deletedTweetIds by lazy {
        deletedTweetsPath.toFile().apply { if (!exists()) createNewFile() }
        deletedTweetsPath.tweetCsvSequence()
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
) : ReadOnlyProperty<Map<String, String>, T> {
    override fun getValue(
        thisRef: Map<String, String>,
        property: KProperty<*>
    ): T = mapper(thisRef.getValue(nameTransform(property.name)).toString())
}
