package au.com.shiftyjelly.pocketcasts.repositories.lists

import au.com.shiftyjelly.pocketcasts.preferences.gateway.GatewayUrlProvider
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncManager
import au.com.shiftyjelly.pocketcasts.servers.model.Discover
import au.com.shiftyjelly.pocketcasts.servers.model.DiscoverCategory
import au.com.shiftyjelly.pocketcasts.servers.model.ListFeed
import au.com.shiftyjelly.pocketcasts.servers.server.ListWebService
import timber.log.Timber

class ListRepository(
    private val listWebService: ListWebService,
    private val syncManager: SyncManager?,
    private val platform: String,
    private val gatewayUrlProvider: GatewayUrlProvider,
) {

    suspend fun getDiscoverFeed(): Discover {
        return listWebService.getDiscoverFeed(platform = platform, version = 3)
    }

    suspend fun getListFeed(url: String, authenticated: Boolean? = false): ListFeed? {
        return runCatching {
            if (authenticated == true) {
                checkNotNull(syncManager) { "Sync Manager is null" }
                check(syncManager.isLoggedIn()) { "User is not logged in" }
                syncManager.getCacheTokenOrLogin { token ->
                    listWebService.getListFeedAuthenticated(url, "Bearer ${token.value}")
                }
            } else {
                listWebService.getListFeed(url)
            }
        }
            .onFailure { exception ->
                Timber.e(exception, "Failed to fetch list feed $url")
            }
            .getOrNull()
    }

    suspend fun getCategoriesList(url: String): List<DiscoverCategory> {
        return listWebService.getCategoriesList(url)
    }

    suspend fun getPodcastRecommendations(podcastUuid: String, countryCode: String?): ListFeed? {
        return getListFeed(url = "${gatewayUrlProvider.serverApiUrl()}/recommendations/podcast/$podcastUuid?country=${countryCode ?: "global"}")
    }

    companion object {
        const val PLATFORM_ANDROID = "android"
        const val PLATFORM_AUTOMOTIVE = "automotive"
        const val PLATFORM_TV = "tv"
    }
}
