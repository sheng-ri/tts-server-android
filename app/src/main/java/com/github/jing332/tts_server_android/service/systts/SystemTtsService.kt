package com.github.jing332.tts_server_android.service.systts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import androidx.core.content.ContextCompat
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.app
import com.github.jing332.tts_server_android.service.systts.help.TtsManager
import com.github.jing332.tts_server_android.ui.systts.SystemTtsActivity
import com.github.jing332.tts_server_android.util.GcManager
import com.github.jing332.tts_server_android.util.StringUtils
import kotlinx.coroutines.*
import java.util.*
import kotlin.system.exitProcess


@Suppress("DEPRECATION")
class SystemTtsService : TextToSpeechService(), TtsManager.Callback {
    companion object {
        const val TAG = "SysTtsService"
        const val ACTION_ON_LOG = "SYS_TTS_ON_LOG"
        const val ACTION_REQUEST_UPDATE_CONFIG = "on_config_changed"
        const val ACTION_NOTIFY_CANCEL = "SYS_TTS_NOTIFY_CANCEL"
        const val ACTION_KILL_PROCESS = "SYS_TTS_NOTIFY_EXIT_0"
        const val NOTIFICATION_CHAN_ID = "system_tts_service"
        const val NOTIFICATION_ID = 2

        /**
         * 更新配置
         */
        fun notifyUpdateConfig() {
            app.sendBroadcast(Intent(ACTION_REQUEST_UPDATE_CONFIG))
        }
    }

    private val mCurrentLanguage: MutableList<String> = mutableListOf("zho", "CHN", "")

    private val mTtsManager: TtsManager by lazy { TtsManager(this) }
    private val mReceiver: MyReceiver by lazy { MyReceiver() }

    // WIFI 锁
    private val mWifiLock by lazy {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tts-server:wifi_lock")
    }

    // 唤醒锁
    private val mWakeLock by lazy {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "tts-server:wake_lock"
        )
    }

    override fun onCreate() {
        super.onCreate()

        IntentFilter(ACTION_KILL_PROCESS).apply {
            addAction(ACTION_NOTIFY_CANCEL)
            addAction(ACTION_REQUEST_UPDATE_CONFIG)
            registerReceiver(mReceiver, this)
        }

        mWakeLock.acquire(60 * 20 * 100)
        mWifiLock.acquire()

        mTtsManager.callback = this
        mTtsManager.loadConfig()
    }

    override fun onDestroy() {
        super.onDestroy()

        mTtsManager.destroy()
        unregisterReceiver(mReceiver)

        mWakeLock.release()
        mWifiLock.release()

        stopForeground(true)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (Locale.SIMPLIFIED_CHINESE.isO3Language == lang || Locale.US.isO3Language == lang) {
            if (Locale.SIMPLIFIED_CHINESE.isO3Country == country || Locale.US.isO3Country == country) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
        } else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return mCurrentLanguage.toTypedArray()
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        mCurrentLanguage.clear()
        mCurrentLanguage.addAll(
            mutableListOf(
                lang.toString(),
                country.toString(),
                variant.toString()
            )
        )

        return result
    }

    override fun onStop() {
        Log.d(TAG, "onStop")
        mTtsManager.stop()
    }


    lateinit var mCurrentText: String

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        synchronized(this) {
            reNewWakeLock()
            startForegroundService()
            val text = request?.charSequenceText.toString().trim()
            mCurrentText = text
            updateNotification(getString(R.string.tts_state_playing), text)

            if (StringUtils.isSilent(text)) {
                callback?.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback?.done()
                return
            }
            runBlocking { mTtsManager.synthesizeText(text, request, callback) }
            callback?.done()
        }
    }

    override fun onError(title: String, content: String) {
        updateNotification(title, content)
    }

    override fun onRetrySuccess() {
        updateNotification(getString(R.string.tts_state_playing), mCurrentText)
    }

    private fun reNewWakeLock() {
        if (!mWakeLock.isHeld) {
            mWakeLock.acquire(60 * 20 * 1000)
            GcManager.doGC()
            Log.i(TAG, "刷新WakeLock 20分钟")
        }
    }

    private lateinit var mNotificationBuilder: Notification.Builder
    private lateinit var mNotificationManager: NotificationManager
    private var isNotificationDisplayed = false

    private val scope = object : CoroutineScope {
        override val coroutineContext = Job()
    }

    /* 启动前台服务通知 */
    private fun startForegroundService() {
        if (!isNotificationDisplayed) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    NOTIFICATION_CHAN_ID,
                    getString(R.string.system_tts_service),
                    NotificationManager.IMPORTANCE_NONE
                )
                chan.lightColor = Color.CYAN
                chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                mNotificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mNotificationManager.createNotificationChannel(chan)
            }
            startForeground(NOTIFICATION_ID, getNotification())
            isNotificationDisplayed = true

            scope.launch {
                while (true) {
                    delay(5000)
                    if (!mTtsManager.isSynthesizing) {
                        stopForeground(true)
                        isNotificationDisplayed = false
                        return@launch
                    }
                }
            }

        }
    }

    /* 更新通知 */
    private fun updateNotification(title: String, content: String? = null) {
        content?.let {
            val bigTextStyle = Notification.BigTextStyle().bigText(it).setSummaryText("TTS")
            mNotificationBuilder.style = bigTextStyle
            mNotificationBuilder.setContentText(it)
        }

        mNotificationBuilder.setContentTitle(title)
        startForeground(NOTIFICATION_ID, mNotificationBuilder.build())
    }

    /* 获取通知 */
    @Suppress("DEPRECATION")
    private fun getNotification(): Notification {
        val notification: Notification
        /*Android 12(S)+ 必须指定PendingIntent.FLAG_*/
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        /*点击通知跳转*/
        val pendingIntent =
            PendingIntent.getActivity(
                this, 0, Intent(
                    this,
                    SystemTtsActivity::class.java
                ), pendingIntentFlags
            )

        val killProcessPendingIntent = PendingIntent.getBroadcast(
            this, 0, Intent(
                ACTION_KILL_PROCESS
            ), pendingIntentFlags
        )
        val cancelPendingIntent =
            PendingIntent.getBroadcast(this, 0, Intent(ACTION_NOTIFY_CANCEL), pendingIntentFlags)

        mNotificationBuilder = Notification.Builder(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationBuilder.setChannelId(NOTIFICATION_CHAN_ID)
        }
        notification = mNotificationBuilder
            .setSmallIcon(R.mipmap.ic_app_notification)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
            .addAction(0, getString(R.string.kill_process), killProcessPendingIntent)
            .addAction(0, getString(R.string.cancel), cancelPendingIntent)
            .build()

        return notification
    }

    @Suppress("DEPRECATION")
    inner class MyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REQUEST_UPDATE_CONFIG -> { /* 配置更改 */
                    mTtsManager.loadConfig()
                }
                ACTION_KILL_PROCESS -> { /* 通知按钮{结束进程} */
                    stopForeground(true)
                    exitProcess(0)
                }
                ACTION_NOTIFY_CANCEL -> { /* 通知按钮{取消}*/
                    if (mTtsManager.isSynthesizing)
                        onStop() /* 取消当前播放 */
                    else /* 无播放，关闭通知 */ {
                        stopForeground(true)
                        isNotificationDisplayed = false
                    }
                }
            }
        }
    }


}