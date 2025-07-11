package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import kotlinx.serialization.SerialName
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.Serializable
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

    override val settingItems
        get() = listOf(
            SettingSwitch(
                "Show Categories",
                "show_categories",
                "Whether to sort stations by category on the home page",
                showCategories
            ),
            SettingSwitch(
                "Use Resolved URLs",
                "resolved_url",
                "Radio Browser offers resolved URLs for stations, this option specifies which URL to use",
                resolvedUrl
            ),
            SettingTextInput(
                "Default Country",
                "default_country",
                "Set a default country to be displayed as the first tab on the home page",
                defaultCountry
            )
        )

    private val showCategories get() = setting.getBoolean("show_categories") ?: true
    private val resolvedUrl get() = setting.getBoolean("resolved_url") ?: false
    private val defaultCountry get() = setting.getString("default_country")

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val stationsLink = "https://de2.api.radio-browser.info/json/stations/bycountrycodeexact"
    private val countriesLink = "https://de2.api.radio-browser.info/json/countries"
    private val searchLink = "https://de2.api.radio-browser.info/json/stations/search"

    private val client by lazy { OkHttpClient.Builder().build() }
    private suspend fun call(url: String) = client.newCall(
        Request.Builder().url(url).build()
    ).await().body.string()

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private inline fun <reified T> String.toData() =
        runCatching { json.decodeFromString<T>(this) }.getOrElse {
            throw IllegalStateException("Failed to parse JSON: $this", it)
        }

    private fun String.toShelf(): List<Shelf> {
        val allStations = this.toData<List<Station>>()
        return if (showCategories) {
            val tags = allStations.flatMap { it.tags.split(",") }
                .map { it.ifEmpty { "unknown" } }
                .distinct()
            tags.map { tag ->
                Shelf.Category(
                    title = tag,
                    items = PagedData.Single {
                        allStations.filter {
                            it.tags.split(",").any { subtag -> subtag == tag } ||
                                    (it.tags.isEmpty() && tag == "unknown")
                        }.map {
                            Track(
                                id = it.id,
                                title = it.name,
                                cover = it.favicon.toImageHolder(),
                                streamables = listOf(
                                    Streamable.server(
                                        if (resolvedUrl) it.urlResolved else it.url,
                                        0,
                                        if (it.bitrate != 0) "${it.bitrate} kbps" else null,
                                        mapOf("hls" to it.hls.toString())
                                    )
                                )
                            ).toMediaItem().toShelf()
                        }
                    }
                )
            }
        }
        else {
            allStations.map {
                Track(
                    id = it.id,
                    title = it.name,
                    cover = it.favicon.toImageHolder(),
                    streamables = listOf(
                        Streamable.server(
                            if (resolvedUrl) it.urlResolved else it.url,
                            0,
                            if (it.bitrate != 0) "${it.bitrate} kbps" else null,
                            mapOf("hls" to it.hls.toString())
                        )
                    )
                ).toMediaItem().toShelf()
            }
        }
    }

    override fun getHomeFeed(tab: Tab?) = PagedData.Single {
        call("$stationsLink/${tab!!.id}").toShelf()
    }.toFeed()

    override suspend fun getHomeTabs(): List<Tab> {
        val countries = call(countriesLink).toData<List<Country>>().distinctBy { it.code.lowercase() }
        val (default, others) = countries.partition { it.name.equals(defaultCountry, true)}
        return (default + others).map {
            Tab(title = it.name, id = it.code)
        }
    }

    override fun getShelves(track: Track): PagedData<Shelf> {
        return PagedData.empty()
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val type = if (streamable.extras["hls"] == "1")
            Streamable.SourceType.HLS
        else
            Streamable.SourceType.Progressive
        return Streamable.Media.Server(
            listOf(streamable.id.toSource(type = type)),
            false
        )
    }

    override suspend fun loadTrack(track: Track) = track
    override fun loadTracks(radio: Radio) = PagedData.empty<Track>()
    override suspend fun radio(track: Track, context: EchoMediaItem?) = Radio("", "")
    override suspend fun radio(album: Album) = throw ClientException.NotSupported("Album radio")
    override suspend fun radio(artist: Artist) = throw ClientException.NotSupported("Artist radio")
    override suspend fun radio(user: User) = throw ClientException.NotSupported("User radio")
    override suspend fun radio(playlist: Playlist) =
        throw ClientException.NotSupported("Playlist radio")

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {}
    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        return emptyList()
    }

    private fun String.toSearchShelf(query: String): List<Shelf> {
        return this.toData<List<Station>>().map {
            Track(
                id = it.id,
                title = it.name,
                cover = it.favicon.toImageHolder(),
                streamables = listOf(
                    Streamable.server(
                        if (resolvedUrl) it.urlResolved else it.url,
                        0,
                        if (it.bitrate != 0) "${it.bitrate} kbps" else null,
                        mapOf("hls" to it.hls.toString())
                    )
                )
            ).toMediaItem().toShelf()
        }
    }

    override fun searchFeed(query: String, tab: Tab?) =
        PagedData.Single {
            call("$searchLink?&name=$query&limit=100").toSearchShelf(query)
        }.toFeed()

    override suspend fun searchTabs(query: String) = emptyList<Tab>()
}