package org.melodist.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 定时关闭（睡眠定时器）状态。
 *
 * @property enabled 是否已启用
 * @property action 触发后的行为
 * @property totalMillis 本轮倒计时总时长
 * @property remainingMillis 剩余时长
 * @property triggered 是否已触发
 */
data class SleepTimerState(
    val enabled: Boolean = false,
    val action: SleepTimerAction = SleepTimerAction.PausePlayback,
    val totalMillis: Long = 0L,
    val remainingMillis: Long = 0L,
    val triggered: Boolean = false,
) {
    val progress: Float
        get() = if (totalMillis <= 0L) 0f else (1f - remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
}

/**
 * 定时关闭（睡眠定时器）管理器。
 *
 * 倒计时基于应用内协程与前台生命周期驱动，因此不会像系统闹钟那样被后台挂起；
 * 应用退到后台时会暂停倒计时（[onForegroundChanged]），回到前台继续。
 * 触发时只回调 [onTriggerAction]，由应用层决定暂停播放或关闭应用。
 */
object SleepTimerManager {
    private const val TICK_INTERVAL_MS = 1_000L

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    /** 触发回调：应用层注册，负责暂停播放 / 关闭应用。 */
    var onTriggerAction: ((SleepTimerAction) -> Unit)? = null

    private var tickJob: Job? = null
    /** 前台运行时的目标时刻（SystemClock.elapsedRealtime 基准）。 */
    private var deadlineElapsedMs: Long = 0L

    /** 后台冻结时保存的剩余时长。 */
    private var deadlineOffsetMs: Long = 0L
    private var isForeground: Boolean = true
    private var isInitialized: Boolean = false

    /**
     * 绑定应用设置与前台状态，仅需在 Application 创建时调用一次。
     */
    fun init() {
        if (isInitialized) return
        isInitialized = true

        scope.launch {
            AppSettingsManager.settings.collect { settings ->
                val action = settings.sleepTimerAction
                if (settings.sleepTimerDuration == SleepTimerDuration.Off) {
                    if (_state.value.enabled) cancel() else _state.value = _state.value.copy(action = action)
                } else if (_state.value.enabled && _state.value.action != action) {
                    // 仅同步播放行为偏好，不重置正在进行的倒计时
                    _state.value = _state.value.copy(action = action)
                } else if (!_state.value.enabled) {
                    _state.value = _state.value.copy(action = action)
                }
            }
        }

        scope.launch {
            AppLifecycleManager.isForeground.collect { foreground ->
                onForegroundChanged(foreground)
            }
        }
    }

    /** 当前设置项对应的默认时长。 */
    private fun configuredDuration(): SleepTimerDuration = AppSettingsManager.settings.value.sleepTimerDuration

    /**
     * 按指定时长开始倒计时。
     */
    fun start() = start(configuredDuration())

    fun start(duration: SleepTimerDuration) {
        if (duration.minutes <= 0) {
            cancel()
            return
        }
        val total = duration.millis
        deadlineElapsedMs = android.os.SystemClock.elapsedRealtime() + total
        _state.value =
            SleepTimerState(
                enabled = true,
                action = AppSettingsManager.settings.value.sleepTimerAction,
                totalMillis = total,
                remainingMillis = total,
                triggered = false,
            )
        scheduleTicks()
    }

    /** 在当前剩余时长基础上追加 [minutes] 分钟。 */
    fun extend(minutes: Int) {
        if (minutes <= 0 || !_state.value.enabled) return
        val extra = minutes * 60_000L
        deadlineElapsedMs += extra
        _state.value =
            _state.value.copy(
                totalMillis = _state.value.totalMillis + extra,
                remainingMillis = _state.value.remainingMillis + extra,
            )
        scheduleTicks()
    }

    /** 取消倒计时并清空状态。 */
    fun cancel() {
        tickJob?.cancel()
        tickJob = null
        _state.value = SleepTimerState(action = AppSettingsManager.settings.value.sleepTimerAction)
    }

    /** 已启用时取消，未启用时按设置启动。 */
    fun toggle() {
        if (_state.value.enabled) cancel() else start()
    }

    /**
     * 播放状态变化回调：开启“暂停即取消”后，用户手动暂停音乐时会结束倒计时，
     * 避免之后恢复播放时被残留的定时器误关。
     */
    fun onPlaybackStopped() {
        if (!_state.value.enabled) return
        if (AppSettingsManager.settings.value.sleepTimerStopOnPause) cancel()
    }

    private fun onForegroundChanged(foreground: Boolean) {
        if (isForeground == foreground) return
        isForeground = foreground
        val state = _state.value
        if (!state.enabled) return
        if (foreground) {
            // 回到前台：从冻结的剩余时长继续倒计时
            deadlineElapsedMs = android.os.SystemClock.elapsedRealtime() + deadlineOffsetMs
            val remaining = deadlineOffsetMs
            if (remaining <= 0L) {
                trigger()
            } else {
                _state.value = _state.value.copy(remainingMillis = remaining)
                scheduleTicks()
            }
        } else {
            // 退到后台时冻结倒计时：把剩余时长折算成新的“后台起点”
            tickJob?.cancel()
            tickJob = null
            val remaining = (deadlineElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            deadlineOffsetMs = remaining
            _state.value = _state.value.copy(remainingMillis = remaining)
        }
    }

    private fun scheduleTicks() {
        tickJob?.cancel()
        if (!isForeground) return
        tickJob =
            scope.launch {
                while (true) {
                    val remaining = (deadlineElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    val current = _state.value
                    if (!current.enabled) return@launch
                    _state.value = current.copy(remainingMillis = remaining)
                    if (remaining <= 0L) {
                        trigger()
                        return@launch
                    }
                    delay(TICK_INTERVAL_MS.coerceAtMost(remaining))
                }
            }
    }

    private fun trigger() {
        tickJob?.cancel()
        tickJob = null
        val action = _state.value.action
        _state.value =
            _state.value.copy(
                enabled = false,
                remainingMillis = 0L,
                triggered = true,
            )
        onTriggerAction?.invoke(action)
    }

    /** 将毫秒格式化为 mm:ss / h:mm:ss。 */
    fun formatRemaining(millis: Long): String {
        val totalSeconds = (millis + 999L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }
}
