package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Feed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

@Serializable
data class Country(
    val name: String,
    @SerialName("iso_3166_1")
    val code: String,
)

@Serializable
data class Station(
    @SerialName("stationuuid")
    val id: String,
    val name: String,
    val url: String,
    @SerialName("url_resolved")
    val urlResolved: String,
    val favicon: String,
    val tags: String,
    val bitrate: Int,
    val hls: Int,
)

class TestExtension : ExtensionClient, HomeFeedClient, TrackClient, RadioClient, SearchFeedClient {
    override suspend fun onExtensionSelected() {}

    override suspend fun onInitialize() {
        if (setting.getBoolean("countries_initialized") == null)  {
            setting.putString("countries_serialized", call("$getServer$countriesSubdirectory"))
            setting.putBoolean("countries_initialized", true)
        }
    }

    override suspend fun getSettingItems() = listOf(
        SettingList(
            "Radio Browser Server",
            "radio_browser_server",
            "Select which server to use",
            listOf("Server 1", "Server 2", "Server 3"),
            listOf(api1Link, api2Link, api3Link),
            0
        ),
        SettingList(
            "Default Country",
            "default_country_code",
            "Select a default country to be displayed as the first tab on the home page",
            setting.getString("countries_serialized")!!.toData<List<Country>>().distinctBy { it.code.lowercase() }.map { it.name },
            setting.getString("countries_serialized")!!.toData<List<Country>>().distinctBy { it.code.lowercase() }.map { it.code }
        ),
        SettingList(
            "Station Order",
            "station_order",
            "Select the order in which the station list will be sorted",
            listOf("Name", "Votes", "Click Count", "Click Trend", "Recently Changed"),
            listOf("name", "votes", "clickcount", "clicktrend", "changetimestamp"),
            3
        ),
        SettingSwitch(
            "Show Categories",
            "show_categories",
            "Whether to sort stations by category on the home page",
            showCategories
        ),
        SettingSwitch(
            "Use Resolved URL",
            "resolved_url",
            "Radio Browser offers resolved URL for each station, this option specifies which URL to use",
            resolvedUrl
        )
    )

    private val getServer get() = setting.getString("radio_browser_server") ?: api1Link
    private val defaultCountryCode get() = setting.getString("default_country_code")
    private val stationOrder get() = setting.getString("station_order")
    private val showCategories get() = setting.getBoolean("show_categories") ?: true
    private val resolvedUrl get() = setting.getBoolean("resolved_url") ?: false

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val api1Link = "https://fi1.api.radio-browser.info"
    private val api2Link = "https://de2.api.radio-browser.info"
    private val api3Link = "https://de1.api.radio-browser.info"
    private val stationsSubdirectory = "/json/stations/bycountrycodeexact"
    private val countriesSubdirectory = "/json/countries"
    private val searchSubdirectory = "/json/stations/search"

    private val client by lazy { OkHttpClient.Builder().build() }
    private suspend fun call(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(url).build()
        ).await().body.string()
    }

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private inline fun <reified T> String.toData() =
        runCatching { json.decodeFromString<T>(this) }.getOrElse {
            throw IllegalStateException("Failed to parse JSON: $this", it)
        }

    private fun getTrack(station: Station): Shelf =
        Track(
            id = station.id,
            title = station.name,
            cover = station.favicon.toImageHolder(),
            streamables = listOf(
                Streamable.server(
                    if (resolvedUrl) station.urlResolved else station.url,
                    0,
                    if (station.bitrate != 0) "${station.bitrate} kbps" else null,
                    mapOf("hls" to station.hls.toString())
                )
            )
        ).toShelf()

    private fun getTagStations(stations: List<Station>, tag: String): List<Shelf> =
        stations.filter {
            it.tags.split(",").any { subtag -> subtag == tag } ||
                    (it.tags.isEmpty() && tag == "unknown")
        }.map {
            getTrack(it)
        }

    private fun String.toShelf(): List<Shelf> {
        val allStations = this.toData<List<Station>>()
        return if (showCategories) {
            val tags = allStations
                .asSequence()
                .flatMap { it.tags.split(",").map { tag -> tag.ifEmpty { "unknown" } }.distinct() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
            listOf(
                Shelf.Lists.Categories(
                    "categories",
                    "Categories",
                    tags.map { tag ->
                        Shelf.Category(
                            tag.replace(" ", "_"),
                            tag.split(" ")
                                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } },
                            PagedData.Single {
                                getTagStations(allStations, tag)
                            }.toFeed()
                        )
                    },
                    type = Shelf.Lists.Type.Grid
                )
            )
        }
        else {
            allStations.map {
                getTrack(it)
            }
        }
    }

    private suspend fun getStations(code: String): List<Shelf> =
        call("$getServer$stationsSubdirectory/$code?order=$stationOrder").toShelf()

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val countries = call("$getServer$countriesSubdirectory")
            .toData<List<Country>>()
            .distinctBy { it.code.lowercase() }
            .sortedBy { it.name }
        val (default, others) = countries.partition { it.code == defaultCountryCode }
        return listOf(
            Shelf.Lists.Categories(
                "countries",
                "Countries",
                (default + others).map {
                    Shelf.Category(
                        it.code,
                        it.name,
                        PagedData.Single {
                            getStations(it.code)
                        }.toFeed()
                    )
                },
                type = Shelf.Lists.Type.Grid
            )
        ).toFeed()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    private fun urlPlsExtension(url: String) =
        url.endsWith(".pls", true) ||
                url.substringAfterLast('.').take(4)
                    .equals("pls?", true)

    private suspend fun parsePLS(stream: String?): String {
        if (stream != null) {
            val content = call(stream)
            for (line in content.lines()) {
                if (line.startsWith("File1=")) {
                    return line.substring(6)
                }
            }
        }
        return ""
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val source = if (urlPlsExtension(streamable.id))
            parsePLS(streamable.id) else streamable.id
        val type = if (streamable.extras["hls"] == "1")
            Streamable.SourceType.HLS else Streamable.SourceType.Progressive
        return Streamable.Media.Server(
            listOf(source.toSource(type = type, isLive = true)),
            false
        )
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track
    override suspend fun loadTracks(radio: Radio): Feed<Track> = PagedData.empty<Track>().toFeed()
    override suspend fun loadRadio(radio: Radio): Radio  = Radio("", "")
    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = Radio("", "")

    private fun String.toSearchShelf(): List<Shelf> {
        return this.toData<List<Station>>().map {
            getTrack(it)
        }
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> =
        call("$getServer$searchSubdirectory?name=$query&limit=100").toSearchShelf().toFeed()
}
