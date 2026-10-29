package com.famelack.tv

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.famelack.tv.data.repository.ChannelRepository
import com.famelack.tv.data.storage.PreferencesManager
import com.famelack.tv.util.MemoryMonitor

/**
 * Kapyo Tv Application class tuned for extreme 512 MB RAM constraints.
 * - Coil configured with tight 4 MB memory cache and 4 MB disk cache (zero cache bloating).
 * - Central manual DI for lightweight singletons (no Hilt/Dagger overhead).
 * - Automatic cache flushing onLowMemory / onTrimMemory.
 */
class KapyoApp : Application(), ImageLoaderFactory {

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var channelRepository: ChannelRepository
        private set

    private var activeImageLoader: ImageLoader? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize lightweight singletons
        preferencesManager = PreferencesManager(this)
        channelRepository = ChannelRepository(this, preferencesManager)

        // Start memory monitoring in debug/testing
        MemoryMonitor.logHeapStats("App onCreate")
    }

    override fun newImageLoader(): ImageLoader {
        val loader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(4 * 1024 * 1024) // 4 MB ceiling (prevent RAM bloat)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(4 * 1024 * 1024) // 4 MB disk ceiling (prevent eMMC storage bloat)
                    .build()
            }
            .bitmapConfig(Bitmap.Config.RGB_565) // 2 bytes per pixel
            .allowRgb565(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
        activeImageLoader = loader
        return loader
    }

    @OptIn(ExperimentalCoilApi::class)
    fun clearAllImageCaches() {
        activeImageLoader?.let { loader ->
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        val imgDir = cacheDir.resolve("image_cache")
        if (imgDir.exists()) {
            imgDir.deleteRecursively()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        activeImageLoader?.memoryCache?.clear()
        Log.w(TAG, "onLowMemory triggered! Flushed image memory cache.")
        MemoryMonitor.logHeapStats("onLowMemory")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(TAG, "onTrimMemory level: $level")
        if (level >= TRIM_MEMORY_BACKGROUND || level >= TRIM_MEMORY_RUNNING_LOW) {
            activeImageLoader?.memoryCache?.clear()
            MemoryMonitor.logHeapStats("onTrimMemory cleared")
        }
    }

    companion object {
        private const val TAG = "KapyoApp"
        lateinit var instance: KapyoApp
            private set
    }
}
