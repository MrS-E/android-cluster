package ch.stix.android_cluster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import ch.stix.android_cluster.MainActivity
import ch.stix.android_cluster.R
import ch.stix.android_cluster.data.VmRepository
import ch.stix.android_cluster.engine.VmState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the QEMU VM process so it keeps running while
 * the app is closed. While the VM runs it holds a partial wake lock so the CPU
 * — and therefore the VM — does not sleep when the screen turns off.
 *
 * The VM itself is owned by [ch.stix.android_cluster.data.VmRepository]; this
 * service only observes its state, keeps the process alive, shows the
 * persistent notification and manages the wake lock.
 */
@AndroidEntryPoint
class QemuForegroundService : Service() {

    @Inject
    lateinit var vmRepository: VmRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wakeLock: PowerManager.WakeLock? = null
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVm()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startAsForeground()
                observeVmState()
                return START_STICKY
            }
            else -> {
                // The system restarted the service with a null intent after the
                // process was killed; no VM is running in a fresh process.
                Log.i(TAG, "Service restarted with no action; stopping")
                stopForegroundAndStopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The VM keeps running when the user removes the app from recents;
        // only refresh the notification so it reflects the current state.
        updateNotification(running = true)
        Log.i(TAG, "Task removed; VM keeps running in background")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification(running = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Mirrors [VmRepository] state into the wake lock and the notification:
     * the wake lock is held exactly while the VM runs and the service stops
     * itself once the VM reaches a terminal state.
     */
    private fun observeVmState() {
        if (observeJob?.isActive == true) return
        observeJob = serviceScope.launch {
            vmRepository.state.collect { state ->
                when (state) {
                    is VmState.Running -> {
                        acquireWakeLock()
                        updateNotification(running = true)
                    }
                    is VmState.Idle -> Unit
                    is VmState.Stopped, is VmState.Failed -> stopForegroundAndStopSelf()
                }
            }
        }
    }

    private fun stopVm() {
        vmRepository.stop()
        stopForegroundAndStopSelf()
    }

    private fun updateNotification(running: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(running))
    }

    private fun stopForegroundAndStopSelf() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(running: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, QemuForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(
                getString(
                    if (running) R.string.vm_notification_title
                    else R.string.vm_notification_title_stopped
                )
            )
            .setContentText(
                getString(
                    if (running) R.string.vm_notification_text
                    else R.string.vm_notification_text_stopped
                )
            )
            .setContentIntent(contentIntent)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                getString(R.string.vm_notification_action_stop),
                stopIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vm_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.vm_notification_channel_description)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Holds a partial wake lock (no timeout) while the VM runs so the CPU does
     * not sleep with the screen off. Released in [onDestroy] and on stop.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "QemuForegroundService"
        private const val CHANNEL_ID = "vm_runtime"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "android-cluster:vm-wake-lock"

        const val ACTION_START = "ch.stix.android_cluster.action.START_VM"
        const val ACTION_STOP = "ch.stix.android_cluster.action.STOP_VM"

        fun start(context: Context) {
            val intent = Intent(context, QemuForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, QemuForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
