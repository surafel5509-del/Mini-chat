package com.minichat.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.minichat.app.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MiniChatApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
    }

    /**
     * Avatars are small and repeat constantly while scrolling, so the loader is tuned for a
     * lightweight footprint: modest memory cache, 48 MB disk cache, no crossfade jank.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.12)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(48L * 1024 * 1024)
                .build()
        }
        .crossfade(180)
        .respectCacheHeaders(false)
        .build()
}
