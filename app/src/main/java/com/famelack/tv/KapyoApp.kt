package com.famelack.tv

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.famelack.tv.data.repository.ChannelRepository
import com.famelack.tv.data.storage.PreferencesManager
import com.famelack.tv.util.MemoryMonitor

/**
 * Kapyo Tv Application class tuned for extreme 512 MB RAM constraints.
 * - Coil configured with strict 8 MB memory cache and RGB_565 bitmaps (50% memory saving).
 * - Central manual DI for lightweight singletons (no Hilt/Dagger overhead).
 * - Continuous memory budget logging in debug mode.
 */
class KapyoApp : Application(), ImageLoaderFactory {

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var channelRepository: ChannelRepository
        private set

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
        // Strict 8 MB memory cache, RGB_565 bitmaps to prevent OOM on 512 MB devices
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(8 * 1024 * 1024) // Exactly 8 MB ceiling
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(16 * 1024 * 1024) // 16 MB disk cache
                    .build()
            }
            .bitmapConfig(Bitmap.Config.RGB_565) // 2 bytes per pixel instead of 4
            .allowRgb565(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false) // Disable crossfade to eliminate animation allocations
            .build()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory triggered! Flushing memory caches...")
        MemoryMonitor.logHeapStats("onLowMemory")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(TAG, "onTrimMemory level: $level")
        if (level >= TRIM_MEMORY_BACKGROUND) {
            MemoryMonitor.logHeapStats("onTrimMemory background")
        }
    }

    companion object {
        private const val TAG = "KapyoApp"
        lateinit var instance: KapyoApp
            private set
    }
}

