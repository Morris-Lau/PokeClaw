// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.R
import io.agents.pokeclaw.ServiceBindingState
import io.agents.pokeclaw.i18n.AppLocaleManager
import io.agents.pokeclaw.utils.XLog

/**
 * Foreground service for active task / monitor notifications only.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        private const val MONITOR_HEALTH_POLL_MS = 5_000L
        const val CHANNEL_ID = "PokeClaw_foreground_channel"
        const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"

        private enum class ForegroundMode {
            IDLE,
            TASK,
            MONITOR,
        }

        @Volatile
        private var _isRunning = false

        @Volatile
        private var _mode = ForegroundMode.IDLE

        /**
         * Check whether the foreground service is running
         */
        fun isRunning(): Boolean = _isRunning

        /**
         * Update the foreground notification with task progress text.
         * Safe to call from any thread — posts to NotificationManager directly.
         */
        fun updateTaskStatus(context: Context, statusText: String) {
            _mode = ForegroundMode.TASK
            showNotification(context, taskTitle(context), statusText)
        }

        /**
         * Show monitor state if auto-reply is active, otherwise stop the foreground service.
         */
        fun resetToIdle(context: Context) {
            syncToBackgroundState(context)
        }

        fun showMonitorStatus(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            if (!manager.isEnabled || manager.monitoredContacts.isEmpty()) {
                _mode = ForegroundMode.IDLE
                stop(context)
                return false
            }
            _mode = ForegroundMode.MONITOR
            val capabilities = AppCapabilityCoordinator.snapshot(context)
            if (capabilities.notificationAccessState != ServiceBindingState.READY) {
                return showNotification(
                    context,
                    monitorPausedTitle(context),
                    if (capabilities.notificationAccessState == ServiceBindingState.CONNECTING) {
                        localized(context).getString(R.string.notification_access_reconnecting)
                    } else {
                        localized(context).getString(R.string.notification_access_disconnected)
                    }
                )
            }
            if (capabilities.accessibilityState != ServiceBindingState.READY) {
                return showNotification(
                    context,
                    monitorPausedTitle(context),
                    if (capabilities.accessibilityState == ServiceBindingState.CONNECTING) {
                        localized(context).getString(R.string.notification_accessibility_reconnecting)
                    } else {
                        localized(context).getString(R.string.notification_accessibility_disconnected)
                    }
                )
            }
            val contacts = manager.monitoredContacts.toList()
            val localized = localized(context)
            val text = when (contacts.size) {
                0 -> localized.getString(R.string.notification_monitor_background)
                1 -> localized.getString(R.string.notification_monitor_one, contacts.first())
                else -> localized.getString(R.string.notification_monitor_many, contacts.size)
            }
            return showNotification(context, monitorTitle(context), text)
        }

        fun syncToBackgroundState(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            return if (manager.isEnabled && manager.monitoredContacts.isNotEmpty()) {
                showMonitorStatus(context)
            } else {
                _mode = ForegroundMode.IDLE
                stop(context)
                false
            }
        }

        private fun showNotification(context: Context, title: String, text: String): Boolean {
            if (!hasNotificationPermission(context)) {
                return false
            }

            try {
                if (_isRunning) {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(context, title, text))
                    return true
                }
                start(context, title, text)
                return true
            } catch (e: Exception) {
                XLog.w(TAG, "Foreground notification update failed", e)
                return false
            }
        }

        /**
         * Start the foreground service
         * @param context Context
         * @return true if started successfully, false if notification permission is missing
         */
        fun start(
            context: Context,
            title: String = taskTitle(context),
            text: String = taskText(context)
        ): Boolean {
            // Android 13+ requires notification permission check
            if (!hasNotificationPermission(context)) {
                return false
            }

            return try {
                val intent = Intent(context, ForegroundService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                XLog.w(TAG, "Foreground service start blocked or failed", e)
                false
            }
        }

        private fun hasNotificationPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

        fun stop(context: Context) {
            _mode = ForegroundMode.IDLE
            val intent = Intent(context, ForegroundService::class.java)
            context.stopService(intent)
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
            }
        }

        private fun buildNotification(context: Context, title: String, text: String): Notification {
            val intent = Intent(context, io.agents.pokeclaw.ui.chat.ComposeChatActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .build()
        }

        private fun localized(context: Context): Context = AppLocaleManager.wrap(context)

        private fun taskTitle(context: Context): String =
            localized(context).getString(R.string.notification_content_title)

        private fun taskText(context: Context): String =
            localized(context).getString(R.string.notification_content_text)

        private fun monitorTitle(context: Context): String =
            localized(context).getString(R.string.notification_monitor_title)

        private fun monitorPausedTitle(context: Context): String =
            localized(context).getString(R.string.notification_monitor_paused_title)
    }

    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthRunnable = object : Runnable {
        override fun run() {
            if (!_isRunning) return
            if (_mode == ForegroundMode.MONITOR) {
                ForegroundService.syncToBackgroundState(applicationContext)
            }
            healthHandler.postDelayed(this, MONITOR_HEALTH_POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning = true
        createNotificationChannel()
        if (hasNotificationPermission(this)) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(this, taskTitle(this), taskText(this))
            )
        } else {
            stopSelf()
            return
        }
        healthHandler.post(healthRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        healthHandler.removeCallbacksAndMessages(null)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification(intent)
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(intent: Intent?): Notification {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: taskTitle(this)
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: taskText(this)
        return buildNotification(this, title, text)
    }
}
