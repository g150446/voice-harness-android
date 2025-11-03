package com.g150446.shepherdsignal

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaAppNotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
// No custom MediaNotification provider; using NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuxioStyleService : MediaSessionService() {
    private val TAG = "AuxioStyleService"
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var messageClient: MessageClient? = null
    private var nodeClient: NodeClient? = null
    private var powerManager: PowerManager? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // AlarmManager removed
    
    // Ring tap debouncing to prevent rapid taps
    private var lastRingTapTime: Long = 0
    private companion object {
        const val RING_TAP_COOLDOWN_MS = 1000L // 1 second cooldown between ring taps
        var isServiceRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AuxioStyleService created")
        
        // Initialize Google Play Services
        messageClient = Wearable.getMessageClient(this)
        nodeClient = Wearable.getNodeClient(this)
        
        // Initialize system services
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Setup wake lock
        setupWakeLock()
        
        // Setup audio focus
        setupAudioFocus()
        
        // Setup ExoPlayer
        setupExoPlayer()
        
        // Persistent media-style foreground notification
        createNotificationChannel()
        val initial = buildNotificationInternal()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, initial)
        startForeground(1, initial)

        isServiceRunning = true
        
        // Register receiver for screen on/off to maintain service
        registerScreenStateReceiver()
        
        // Start periodic service maintenance to prevent Doze killing
        startPeriodicMaintenance()
        
        DebugMessageManager.addMessage("🎵 Auxio-style service started - Enhanced Doze resistance!")
    }
    
    private fun registerScreenStateReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT) // Screen unlocked
            }
            registerReceiver(screenStateReceiver, filter)
            Log.d(TAG, "Screen state receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen state receiver", e)
        }
    }
    
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Screen unlocked - ensuring service is active")
                    // Re-ensure service is in foreground
                    try {
                        val notification = buildNotificationInternal()
                        startForeground(1, notification)
                        // Re-acquire wake lock if needed
                        if (wakeLock?.isHeld != true) {
                            setupWakeLock()
                        }
                        // Ensure ExoPlayer is playing
                        if (exoPlayer?.isPlaying != true) {
                            exoPlayer?.play()
                        }
                        Log.d(TAG, "Service maintained after screen unlock")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error maintaining service on screen unlock", e)
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen locked - maintaining wake lock and service")
                    // Ensure wake lock is held when screen locks
                    if (wakeLock?.isHeld != true) {
                        setupWakeLock()
                    }
                }
            }
        }
    }
    
    private fun startPeriodicMaintenance() {
        scope.launch {
            while (isServiceRunning) {
                try {
                    kotlinx.coroutines.delay(3 * 60 * 1000L) // Every 3 minutes (before 5 min kill)
                    
                    Log.d(TAG, "Periodic maintenance check (every 3 min) - ensuring service stays alive")
                    
                    // Re-acquire wake lock if released
                    if (wakeLock?.isHeld != true) {
                        setupWakeLock()
                    }
                    
                    // Ensure we're still in foreground
                    val notification = buildNotificationInternal()
                    startForeground(1, notification)
                    
                    // Ensure ExoPlayer is playing (keeps MediaSession active)
                    if (exoPlayer?.isPlaying != true && exoPlayer?.playbackState == Player.STATE_READY) {
                        exoPlayer?.play()
                        Log.d(TAG, "ExoPlayer restarted to maintain MediaSession")
                    }
                    
                    // Re-request audio focus if needed
                    requestAudioFocus()
                    
                    Log.d(TAG, "Periodic maintenance completed - service kept alive")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic maintenance", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AuxioStyleService destroyed")
        
        // Unregister receiver
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering screen state receiver", e)
        }
        
        // Release resources
        releaseWakeLock()
        abandonAudioFocus()
        // AlarmManager removed
        exoPlayer?.release()
        mediaSession?.run {
            player.release()
            release()
        }
        
        val wasRunning = isServiceRunning
        isServiceRunning = false
        DebugMessageManager.addMessage("❌ Auxio-style service stopped")
        
        // Note: START_STICKY will automatically restart the service if killed by system
        // No manual restart needed - Android handles it
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotificationInternal())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "auxio_style_channel",
                "ShepherdSignal Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent playback for ring gesture detection"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotificationInternal(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val trackLabel = currentTrackLabel()

        return NotificationCompat.Builder(this, "auxio_style_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(trackLabel)
            .setContentText("ShepherdSignal Active • Listening for ring gestures")
            .setSubText("ShepherdSignal")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE) // Android 14+
            .setStyle(
                MediaAppNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionCompatToken)
            )
            .build()
    }

    private fun refreshNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotificationInternal())
            
            // Update PlaybackStateManager for UI
            val trackLabel = currentTrackLabel()
            val isPlaying = exoPlayer?.isPlaying == true
            PlaybackStateManager.updateState(isPlaying, trackLabel)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing notification", e)
        }
    }

    private fun currentTrackLabel(): String {
        val title = exoPlayer?.currentMediaItem?.mediaMetadata?.title?.toString()
        val index = exoPlayer?.currentMediaItemIndex ?: -1
        val label = title?.takeIf { it.isNotBlank() } ?: "Silent Track ${index + 1}"
        Log.d(TAG, "currentTrackLabel: title='$title', index=$index, label='$label'")
        return label
    }

    private fun setupWakeLock() {
        try {
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ShepherdSignal:AuxioStyleWakeLock"
            )
            // Acquire without timeout to keep it indefinitely
            wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours as safety limit
            Log.d(TAG, "Wake lock acquired - service will stay active")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating wake lock", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes
                Log.d(TAG, "Wake lock acquired for 10 minutes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            Log.d(TAG, "Wake lock released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    private fun setupAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AndroidAudioAttributes.Builder()
                            .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                            .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        handleAudioFocusChange(focusChange)
                    }
                    .build()
            }
            
            // Request audio focus
            requestAudioFocus()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up audio focus", e)
        }
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { request ->
                    val result = audioManager?.requestAudioFocus(request)
                    Log.d(TAG, "Audio focus requested: $result")
                }
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager?.requestAudioFocus(
                    { focusChange -> handleAudioFocusChange(focusChange) },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                Log.d(TAG, "Audio focus requested (legacy): $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting audio focus", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { request ->
                    audioManager?.abandonAudioFocusRequest(request)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus { }
            }
            Log.d(TAG, "Audio focus abandoned")
        } catch (e: Exception) {
            Log.e(TAG, "Error abandoning audio focus", e)
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                exoPlayer?.volume = 1.0f
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost")
                // Don't pause - keep playing silently for ring gesture detection
                exoPlayer?.volume = 0.0f
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost transient")
                // Don't pause - keep playing silently for ring gesture detection
                exoPlayer?.volume = 0.0f
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost transient can duck")
                exoPlayer?.volume = 0.3f
            }
        }
    }

    private fun setupExoPlayer() {
        try {
            exoPlayer = ExoPlayer.Builder(this)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                .build()

            exoPlayer?.repeatMode = Player.REPEAT_MODE_ALL

            // Load silent audio from assets
            loadSilentAudio()

            // Setup player listener
            exoPlayer?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d(TAG, "Playback state changed: $playbackState")
                    when (playbackState) {
                        Player.STATE_READY -> {
                            Log.d(TAG, "Player ready")
                            DebugMessageManager.addMessage("🎵 Auxio-style player ready")
                        }
                        Player.STATE_ENDED -> {
                            Log.d(TAG, "Playback ended - restarting for continuous playback")
                            // Restart playback for continuous silent music
                            exoPlayer?.seekTo(0)
                            exoPlayer?.play()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.d(TAG, "Is playing changed: $isPlaying")
                    if (isPlaying) {
                        DebugMessageManager.addMessage("▶️ Auxio-style playback started")
                    } else {
                        DebugMessageManager.addMessage("⏸️ Auxio-style playback paused")
                    }
                    refreshNotification()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    Log.d(TAG, "Media item transition: ${mediaItem?.mediaMetadata?.title}")
                    mediaItem?.mediaMetadata?.title?.toString()?.let { title ->
                        DebugMessageManager.addMessage("🔁 Now playing $title")
                    }
                    refreshNotification()
                }
            })

            // Create MediaSession with proper configuration
            mediaSession = MediaSession.Builder(this, exoPlayer!!)
                .setCallback(object : MediaSession.Callback {
                    override fun onMediaButtonEvent(
                        session: MediaSession,
                        controllerInfo: MediaSession.ControllerInfo,
                        intent: Intent
                    ): Boolean {
                        val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                            Log.d(TAG, "Media button pressed: ${keyEvent.keyCode}")
                            handleMediaButton(keyEvent.keyCode)
                            return true
                        }
                        return false
                    }
                })
                .build()

            // Start playback
            exoPlayer?.play()

            Log.d(TAG, "ExoPlayer and MediaSession setup complete")
            
            // Ensure MediaSessionService starts as foreground service
            // This is critical for Doze resistance
            if (mediaSession != null) {
                Log.d(TAG, "MediaSession is ready - service will run as foreground")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up ExoPlayer", e)
        }
    }

    private fun loadSilentAudio() {
        try {
            // Load silent audio from assets
            val mediaItems = listOf(
                MediaItem.Builder()
                    .setUri("file:///android_asset/silent.mp3")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Silent Track 1")
                            .setArtist("ShepherdSignal")
                            .build()
                    )
                    .build(),
                MediaItem.Builder()
                    .setUri("file:///android_asset/silent2.mp3")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Silent Track 2")
                            .setArtist("ShepherdSignal")
                            .build()
                    )
                    .build()
            )
            exoPlayer?.setMediaItems(mediaItems, /* resetPosition = */ true)
            exoPlayer?.prepare()
            Log.d(TAG, "Silent audio playlist loaded from assets (${mediaItems.size} tracks)")
            
            // Debug: Check if metadata is properly set
            mediaItems.forEachIndexed { index, item ->
                Log.d(TAG, "MediaItem[$index] title: ${item.mediaMetadata.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading silent audio", e)
            // Fallback: create a silent audio source
            createSilentAudioSource()
        }
    }

    private fun createSilentAudioSource() {
        try {
            // Create a silent audio source using ExoPlayer's built-in capabilities
            val silentUri = "data:audio/wav;base64,UklGRigAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA="
            val mediaItems = listOf(
                MediaItem.Builder()
                    .setUri(silentUri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Silent Track 1")
                            .setArtist("ShepherdSignal")
                            .build()
                    )
                    .build(),
                MediaItem.Builder()
                    .setUri(silentUri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Silent Track 2")
                            .setArtist("ShepherdSignal")
                            .build()
                    )
                    .build()
            )
            exoPlayer?.setMediaItems(mediaItems, /* resetPosition = */ true)
            exoPlayer?.prepare()
            Log.d(TAG, "Silent audio playlist created from fallback source")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating silent audio source", e)
        }
    }

    private fun handleMediaButton(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                Log.d(TAG, "Ring gesture detected via media button: $keyCode")
                
                // Check cooldown to prevent rapid taps
                val currentTime = System.currentTimeMillis()
                val timeSinceLastTap = currentTime - lastRingTapTime
                
                if (timeSinceLastTap < RING_TAP_COOLDOWN_MS) {
                    Log.d(TAG, "Ring tap ignored - cooldown active (${timeSinceLastTap}ms < ${RING_TAP_COOLDOWN_MS}ms)")
                    DebugMessageManager.addMessage("⏱️ Ring tap ignored - cooldown active")
                    return
                }
                
                // Cooldown passed - legitimate ring tap
                lastRingTapTime = currentTime
                Log.d(TAG, "Ring tap accepted - time since last tap: ${timeSinceLastTap}ms")
                DebugMessageManager.addMessage("💍 Ring gesture detected via Auxio-style service!")
                handleRingTap()
            }
        }
    }

    private fun handleRingTap() {
        scope.launch {
            try {
                advanceSilentTrack()
                sendMessageToWatch()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling ring tap", e)
                DebugMessageManager.addMessage("❌ Error handling ring tap: ${e.message}")
            }
        }
    }

    private fun advanceSilentTrack() {
        try {
            val player = exoPlayer ?: return
            val previousIndex = player.currentMediaItemIndex
            val targetIndex = when {
                player.hasNextMediaItem() -> player.nextMediaItemIndex
                player.mediaItemCount > 0 -> 0
                else -> null
            }

            if (targetIndex == null || targetIndex == C.INDEX_UNSET) {
                Log.w(TAG, "No target media item available to advance to")
                return
            }

            player.seekToDefaultPosition(targetIndex)
            if (!player.isPlaying) {
                player.play()
            }

            val label = runCatching {
                player.getMediaItemAt(targetIndex).mediaMetadata.title?.toString()
            }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "Silent Track"

            Log.d(TAG, "Advanced silent track from index $previousIndex to $targetIndex ($label)")
            DebugMessageManager.addMessage("⏭️ Switched to $label")
            refreshNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Error advancing silent track", e)
            DebugMessageManager.addMessage("⚠️ Failed to advance silent track: ${e.message}")
        }
    }

    private suspend fun sendMessageToWatch() {
        try {
            val nodes = nodeClient?.connectedNodes?.await()
            Log.d(TAG, "Found ${nodes?.size ?: 0} connected nodes")
            
            if (nodes.isNullOrEmpty()) {
                DebugMessageManager.addMessage("⚠ No watch connected")
                return
            }
            
            // Send message to all connected nodes
            nodes.forEach { node: Node ->
                val message = "ring_tap"
                messageClient?.sendMessage(node.id, "/ring_tap", message.toByteArray())
                Log.d(TAG, "Sent message to node: ${node.id}")
            }
            
            DebugMessageManager.addMessage("📱 Message sent to ${nodes.size} watch(es)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to watch", e)
            DebugMessageManager.addMessage("❌ Error sending message: ${e.message}")
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand called - ensuring service stays alive")
        
        // Handle action from UI button
        when (intent?.action) {
            "TOGGLE_PLAYBACK" -> {
                Log.d(TAG, "Toggle playback action received")
                togglePlayback()
            }
        }
        
        // Ensure we're running as foreground service
        if (!isServiceRunning) {
            val notification = buildNotificationInternal()
            startForeground(1, notification)
            isServiceRunning = true
            Log.d(TAG, "Service restarted and running in foreground")
        }
        
        // Keep running; notification already started
        // START_STICKY: Service will be recreated if killed by system
        // START_NOT_STICKY: Service won't restart if killed (less aggressive)
        // Using START_STICKY for maximum persistence
        return START_STICKY
    }
    
    private fun togglePlayback() {
        try {
            val player = exoPlayer ?: return
            if (player.isPlaying) {
                player.pause()
                Log.d(TAG, "Playback paused")
                DebugMessageManager.addMessage("⏸️ Playback paused")
            } else {
                player.play()
                Log.d(TAG, "Playback resumed")
                DebugMessageManager.addMessage("▶️ Playback resumed")
            }
            refreshNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling playback", e)
            DebugMessageManager.addMessage("❌ Error toggling playback: ${e.message}")
        }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved called - service will try to restart")
        // Service will restart due to START_STICKY
        // But we can also explicitly restart if needed
        try {
            val restartIntent = Intent(applicationContext, AuxioStyleService::class.java)
            restartIntent.putExtra("restart", true)
            startForegroundService(restartIntent)
            Log.d(TAG, "Service restart intent sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service on task removal", e)
        }
    }

    // AlarmManager removed to avoid OEM throttling; rely on legitimate media FGS

    // Custom MediaNotification provider removed; using NotificationCompat foreground
}
