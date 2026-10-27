package org.melodist.mobile

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MelodistMobileApp :
    Application(),
    SingletonImageLoader.Factory,
    android.app.Application.ActivityLifecycleCallbacks {
    private val startupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)

    /** 定时器自身触发暂停时，避免被“暂停即取消”逻辑干扰。 */
    private var suppressPauseCancel = false

    /** 跟踪已创建的 Activity，用于“定时关闭”时退出整个应用。 */
    private val activityRefs = java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<android.app.Activity>>()

    private fun finishAll(): Boolean {
        val alive = activityRefs.mapNotNull { it.get() }.filter { !it.isFinishing }
        activityRefs.clear()
        alive.forEach { activity ->
            try {
                activity.finish()
            } catch (e: Exception) {
                Log.w("MelodistMobileApp", "Failed to finish activity", e)
            }
        }
        return alive.isNotEmpty()
    }

    override fun onActivityCreated(
        activity: android.app.Activity,
        savedInstanceState: android.os.Bundle?,
    ) {
        activityRefs.removeAll { it.get() == null }
        activityRefs.add(java.lang.ref.WeakReference(activity))
    }

    override fun onActivityStarted(activity: android.app.Activity) {}

    override fun onActivityResumed(activity: android.app.Activity) {}

    override fun onActivityPaused(activity: android.app.Activity) {}

    override fun onActivityStopped(activity: android.app.Activity) {}

    override fun onActivitySaveInstanceState(
        activity: android.app.Activity,
        outState: android.os.Bundle,
    ) {}

    override fun onActivityDestroyed(activity: android.app.Activity) {
        activityRefs.removeAll { it.get() == null || it.get() == activity }
    }

    override fun onCreate() {
        super.onCreate()
        org.melodist.api.ApiLogger.logger = { priority, tag, message, throwable ->
            if (throwable != null) {
                android.util.Log.println(priority, tag, "$message\n${android.util.Log.getStackTraceString(throwable)}")
            } else {
                android.util.Log.println(priority, tag, message)
            }
        }
        org.melodist.data.AppLifecycleManager
            .init(this)
        org.melodist.data.AppLifecycleManager.onTrimMemoryAction = {
            try {
                SingletonImageLoader.get(this@MelodistMobileApp).memoryCache?.clear()
            } catch (e: Exception) {
                Log.w("MelodistMobileApp", "Operation failed", e)
            }
        }
        org.melodist.data.AppSettingsManager
            .init(this)
        org.melodist.data.ListeningStatsManager
            .init(this)
        org.melodist.data.LyricCacheManager
            .init(this)
        org.melodist.playback.AudioQualityAuditor
            .cleanOrphanProbeFiles(this)
        org.melodist.data.AppSettingsManager.imageCacheClearAction = {
            try {
                SingletonImageLoader.get(this@MelodistMobileApp).diskCache?.clear()
                SingletonImageLoader.get(this@MelodistMobileApp).memoryCache?.clear()
                true
            } catch (_: Exception) {
                false
            }
        }
        org.melodist.mobile.coloros.ColorOsLyricBridge
            .init()
        setupSleepTimer()
        registerActivityLifecycleCallbacks(this)
        try {
            if (packageName == "com.tencent.qqmusic") {
                val serviceIntent =
                    android.content.Intent().apply {
                        setComponent(android.content.ComponentName("com.tencent.qqmusic", "com.tencent.qqmusic.third.api.QQMusicApiService"))
                    }
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w("MelodistMobileApp", "Operation failed", e)
        }
    }

    /**
     * 定时关闭：接入播放器，倒计时结束时按设置暂停播放或直接关闭应用。
     */
    private fun setupSleepTimer() {
        org.melodist.data.SleepTimerManager.init()
        org.melodist.data.SleepTimerManager.onTriggerAction = { action ->
            // 定时静默结束：不再触发“暂停即取消”的回调链
            suppressPauseCancel = true
            try {
                org.melodist.playback.PlaybackManager.pause()
            } catch (e: Exception) {
                Log.w("MelodistMobileApp", "Failed to pause playback on sleep timer", e)
            }
            if (action == org.melodist.data.SleepTimerAction.CloseApp) {
                val closed = closeAllActivities()
                if (!closed) {
                    // 没有可关闭的界面时退化为仅暂停播放，避免计时器结束后音乐继续播放
                    Log.w("MelodistMobileApp", "No activity to close for sleep timer")
                }
            } else {
                toast("定时关闭：已暂停播放")
            }
            suppressPauseCancel = false
        }
        // 用户手动暂停时（若开启了“暂停即取消”）结束倒计时
        startupScope.launch {
            org.melodist.playback.PlaybackManager.isPlaying.collect { playing ->
                if (!playing && !suppressPauseCancel) {
                    org.melodist.data.SleepTimerManager.onPlaybackStopped()
                }
            }
        }
    }

    private fun closeAllActivities(): Boolean {
        val finished = activityRefs.finishAll()
        if (!finished) return false
        // 关闭界面后同时停掉播放服务，避免音乐在后台继续播放
        try {
            org.melodist.playback.PlaybackManager.pause()
        } catch (e: Exception) {
            Log.w("MelodistMobileApp", "Failed to pause playback while closing", e)
        }
        toast("定时关闭：已关闭应用")
        return true
    }

    private fun toast(message: String) {
        try {
            android.os.Handler(mainLooper).post {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w("MelodistMobileApp", "Failed to show toast", e)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val okHttpClient =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor { chain: Interceptor.Chain ->
                    val original = chain.request()
                    val request =
                        original
                            .newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; MelodistMobile) AppleWebKit/537.36")
                            .header("Referer", "https://y.qq.com/")
                            .build()
                    chain.proceed(request)
                }.build()

        return ImageLoader
            .Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }.build()
    }
}
