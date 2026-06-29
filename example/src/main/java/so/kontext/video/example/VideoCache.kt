package so.kontext.video.example

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-wide media cache. Media3 allows only ONE [SimpleCache] per directory
 * per process, so it must be a singleton (a fresh [androidx.media3.exoplayer.ExoPlayer]
 * is built per film, but they all share this cache). Byte-ranges already on
 * disk are served locally, so re-opening a film — or scrubbing back over parts
 * already seen — doesn't re-download. Progressive MP4 caches fully; HLS/DASH
 * cache their media segments (the manifest is still re-fetched each load).
 */
@OptIn(UnstableApi::class)
object VideoCache {
    private const val TAG = "VideoExample"

    /** LRU cap; the oldest content is evicted once the cache grows past this. */
    private const val MAX_BYTES = 256L * 1024 * 1024 // 256 MB

    @Volatile
    private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "media"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }

    /** Read-through data source factory: serve from cache, fall back to network. */
    fun dataSourceFactory(context: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            // A cache read error must not kill playback — fall through to network.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Bytes currently held on disk — surfaced in Settings. */
    fun cachedBytes(context: Context): Long = cache(context).cacheSpace

    /** Drops everything on disk (locked spans of the playing item are skipped). */
    fun clear(context: Context) {
        val c = cache(context)
        c.keys.toSet().forEach { key ->
            runCatching { c.removeResource(key) }
                .onFailure { Log.w(TAG, "cache clear skipped key=$key", it) }
        }
    }
}
