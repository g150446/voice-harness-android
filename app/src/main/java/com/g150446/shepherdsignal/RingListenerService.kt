package com.g150446.shepherdsignal

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

class RingListenerService : Service() {

    companion object {
        const val ACTION_PLAY = "com.g150446.shepherdsignal.ACTION_PLAY"
        const val ACTION_PAUSE = "com.g150446.shepherdsignal.ACTION_PAUSE"
        const val ACTION_TOGGLE_PLAYBACK = "com.g150446.shepherdsignal.ACTION_TOGGLE_PLAYBACK"
        const val RING_TAP_COOLDOWN_MS = 1000L // 1 second cooldown between ring taps
    }

    private val TAG = "RingListenerService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "ring_listener_channel"

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var messageClient: MessageClient
    private lateinit var nodeClient: NodeClient
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var mediaPlayer: MediaPlayer? = null
    private var audioFiles = mutableListOf<File>()
    private var currentTrackIndex = 0
    private var isPlaying = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmManager: AlarmManager? = null
    private var alarmPendingIntent: PendingIntent? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    
    // Ring tap debouncing to prevent false positives from tilt-to-wake
    private var lastRingTapTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        messageClient = Wearable.getMessageClient(this)
        nodeClient = Wearable.getNodeClient(this)

        // Setup wake lock for Doze mode resistance
        setupWakeLock()
        
        // Setup AlarmManager for periodic wake-ups
        setupAlarmManager()
        
        // Setup audio focus management
        setupAudioFocus()
        
        // Check and request battery optimization exemption
        checkAndRequestBatteryOptimizationExemption()
        
        loadAudioFiles()
        createNotificationChannel()
        setupMediaPlayer()
        setupMediaSession()

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        DebugMessageManager.addMessage("Ring listener service started")
        DebugMessageManager.addMessage("Service will run in background")
        DebugMessageManager.addMessage("Doze mode resistance enabled")

        // Start Doze mode maintenance
        maintainServiceInDozeMode()

        // Auto-start playback to make MediaSession active
        if (audioFiles.isNotEmpty()) {
            startPlayback()
        }
    }

    private fun loadAudioFiles() {
        try {
            // First, try to load the silent MP3 from assets as default
            val silentFile = loadSilentAudioFromAssets()
            if (silentFile != null) {
                audioFiles.add(silentFile)
                Log.d(TAG, "Loaded silent MP3 from assets")
                DebugMessageManager.addMessage("♪ Using silent music (60 min loop)")
            }

            // Then, try to load additional files from Music directory
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            Log.d(TAG, "Looking for additional audio files in: ${musicDir.absolutePath}")

            if (musicDir.exists() && musicDir.isDirectory) {
                val files = musicDir.listFiles { file ->
                    file.extension.lowercase() in listOf("mp3", "wav", "m4a", "ogg")
                }

                if (files != null && files.isNotEmpty()) {
                    audioFiles.addAll(files)
                    Log.d(TAG, "Found ${files.size} additional audio files")
                    DebugMessageManager.addMessage("Found ${files.size} additional files")
                    files.forEach { file ->
                        Log.d(TAG, "  - ${file.name}")
                        DebugMessageManager.addMessage("  ${file.name}")
                    }
                }
            }

            if (audioFiles.isEmpty()) {
                Log.w(TAG, "No audio files available")
                DebugMessageManager.addMessage("⚠ No audio files available")
            } else {
                Log.d(TAG, "Total audio files loaded: ${audioFiles.size}")
                DebugMessageManager.addMessage("Total files: ${audioFiles.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading audio files", e)
            DebugMessageManager.addMessage("✗ Error loading files: ${e.message}")
        }
    }

    private fun loadSilentAudioFromAssets(): File? {
        return try {
            val assetManager = assets
            val inputStream = assetManager.open("silent.mp3")
            val tempFile = File.createTempFile("silent", ".mp3", cacheDir)
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            Log.d(TAG, "Silent MP3 loaded from assets to: ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Error loading silent MP3 from assets", e)
            null
        }
    }

    private fun setupWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            
            // Primary wake lock for CPU operation - NEVER RELEASE
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ShepherdSignal::DozeResistance"
            )
            wakeLock?.acquire()
            
            // DO NOT use SCREEN_DIM_WAKE_LOCK - it keeps screen on
            // PARTIAL_WAKE_LOCK is enough for Doze mode resistance
            // Following Auxio's approach - no screen wake locks
            
            Log.d(TAG, "PARTIAL_WAKE_LOCK acquired - CPU stays active, screen can turn off")
            DebugMessageManager.addMessage("🔒 CPU wake lock active (screen can turn off)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake locks", e)
        }
    }

    private fun setupAlarmManager() {
        try {
            alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // Create pending intent for alarm
            val alarmIntent = Intent(this, RingListenerService::class.java).apply {
                action = "ALARM_WAKE_UP"
            }
            alarmPendingIntent = PendingIntent.getService(
                this, 0, alarmIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            // Schedule exact alarm every 2 minutes for Doze mode resistance (Auxio-style)
            alarmPendingIntent?.let { pendingIntent ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager?.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 2 * 60 * 1000, // 2 minutes
                        pendingIntent
                    )
                } else {
                    alarmManager?.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 2 * 60 * 1000, // 2 minutes
                        pendingIntent
                    )
                }
            }
            
            Log.d(TAG, "AlarmManager scheduled for Doze mode resistance")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup AlarmManager", e)
        }
    }

    private fun setupAudioFocus() {
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        handleAudioFocusChange(focusChange)
                    }
                    .build()
            }
            
            Log.d(TAG, "Audio focus management setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup audio focus", e)
        }
    }

    private fun requestAudioFocus(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { request ->
                    audioManager?.requestAudioFocus(request) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
                } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    { focusChange -> handleAudioFocusChange(focusChange) },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request audio focus", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
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
                audioManager?.abandonAudioFocus { focusChange -> handleAudioFocusChange(focusChange) }
            }
            Log.d(TAG, "Audio focus abandoned")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to abandon audio focus", e)
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                if (!isPlaying && audioFiles.isNotEmpty()) {
                    startPlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost permanently - continuing playback for ring gesture")
                // For ring gesture detection, we need to keep playing even on focus loss
                // This is different from normal music players but necessary for our use case
                DebugMessageManager.addMessage("🛡️ Audio focus lost - continuing for ring detection")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost temporarily - continuing playback for ring gesture")
                // Keep playing for ring gesture detection
                DebugMessageManager.addMessage("🛡️ Audio focus lost temporarily - continuing for ring detection")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost temporarily, can duck - continuing playback")
                // Keep playing at same volume (silent music anyway)
            }
        }
    }

    private fun isDeviceInDozeMode(): Boolean {
        return false // Simplified for now - will implement proper Doze detection later
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        return try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isIgnoringBatteryOptimizations(packageName)
            } else {
                true // On older versions, assume it's disabled
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check battery optimization status", e)
            false
        }
    }

    private fun checkAndRequestBatteryOptimizationExemption() {
        try {
            if (!isBatteryOptimizationDisabled()) {
                Log.d(TAG, "Battery optimization is enabled - requesting exemption")
                DebugMessageManager.addMessage("🔋 Battery optimization detected - requesting exemption")
                
                // Show notification to guide user to disable battery optimization
                showBatteryOptimizationNotification()
                
                // Auto-request exemption (will open settings)
                requestBatteryOptimizationExemption()
            } else {
                Log.d(TAG, "Battery optimization is already disabled - app is exempt")
                DebugMessageManager.addMessage("✅ Battery optimization disabled - app is exempt")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check battery optimization", e)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                Log.d(TAG, "Opened battery optimization settings")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings", e)
            // Fallback to general battery settings
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                Log.d(TAG, "Opened general battery optimization settings")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open general battery settings", e2)
            }
        }
    }

    private fun showBatteryOptimizationNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Create battery optimization notification channel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "battery_optimization",
                    "Battery Optimization",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications about battery optimization settings"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            // Create notification intent to open battery settings
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            // Create battery optimization notification
            val notification = NotificationCompat.Builder(this, "battery_optimization")
                .setContentTitle("🔋 Battery Optimization Required")
                .setContentText("Tap to disable battery optimization for reliable ring gestures")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("Ring gesture detection requires background operation. " +
                            "Disable battery optimization to ensure ring gestures work reliably " +
                            "even when the phone is in sleep mode."))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .build()
            
            notificationManager.notify(999, notification)
            Log.d(TAG, "Battery optimization notification shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show battery optimization notification", e)
        }
    }

    private fun setupMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()
            )

            setOnCompletionListener {
                Log.d(TAG, "Track completed, playing next")
                skipToNext()
            }

            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                DebugMessageManager.addMessage("✗ Playback error: $what")
                false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ring Gesture Listener",
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "Listening for smart ring gestures - Doze mode resistant"
                setShowBadge(false)
                enableLights(true)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val trackName = if (audioFiles.isNotEmpty() && currentTrackIndex < audioFiles.size) {
            val file = audioFiles[currentTrackIndex]
            if (currentTrackIndex == 0 && file.name.contains("silent")) {
                "Silent Music (Loop)"
            } else {
                file.nameWithoutExtension
            }
        } else {
            "No tracks"
        }

        // Create media style notification like a real music player
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setMediaSession(mediaSession.sessionToken)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ring Gesture Music Player")
            .setContentText("Playing: $trackName")
            .setSubText("Ring Gesture Listener")
            .setSmallIcon(if (isPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setUsesChronometer(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setLocalOnly(false)
            .setGroupSummary(false)
            .setDeleteIntent(null)
            .setStyle(mediaStyle)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "ShepherdSignalSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            
            // Set proper metadata to behave like a real music player (Auxio-style)
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Silent Music (Ring Gesture Listener)")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "ShepherdSignal")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Ring Gesture Service")
                    .putString(MediaMetadataCompat.METADATA_KEY_GENRE, "Silent")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 3600000) // 60 minutes
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, null)
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "silent_music_ring_gesture")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, "ShepherdSignal")
                    .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, 1)
                    .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, 1)
                    .build()
            )
            
            // Set initial playback state (Auxio-style)
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                    )
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                    .build()
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)

                    DebugMessageManager.addMessage("MediaSession event: keyCode=${keyEvent?.keyCode}, action=${keyEvent?.action}")
                    Log.d(TAG, "Media button event - KeyCode: ${keyEvent?.keyCode}, Action: ${keyEvent?.action}")

                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                // Check cooldown to prevent rapid taps
                                val currentTime = System.currentTimeMillis()
                                val timeSinceLastTap = currentTime - lastRingTapTime
                                
                                if (timeSinceLastTap < RING_TAP_COOLDOWN_MS) {
                                    Log.d(TAG, "Ring tap ignored - cooldown active (${timeSinceLastTap}ms < ${RING_TAP_COOLDOWN_MS}ms)")
                                    DebugMessageManager.addMessage("⏱️ Ring tap ignored - cooldown active")
                                    return true // Consume the event even if we ignore it
                                }
                                
                                // Cooldown passed - legitimate ring tap
                                lastRingTapTime = currentTime
                                Log.d(TAG, "Ring tap accepted - time since last tap: ${timeSinceLastTap}ms")
                                DebugMessageManager.addMessage("✓ Ring tap intercepted via MediaSession!")
                                Log.d(TAG, "MEDIA_NEXT intercepted - launching watch app")
                                launchWatchApp()
                                return true // Consume the event
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                DebugMessageManager.addMessage("Play/Pause button pressed")
                                Log.d(TAG, "Play/Pause button - toggling playback")
                                if (this@RingListenerService.isPlaying) pausePlayback() else startPlayback()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                startPlayback()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                pausePlayback()
                                return true
                            }
                        }
                    }

                    return super.onMediaButtonEvent(mediaButtonEvent)
                }

                override fun onPlay() {
                    startPlayback()
                }

                override fun onPause() {
                    pausePlayback()
                }

                override fun onSkipToNext() {
                    // Check cooldown to prevent rapid taps
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastTap = currentTime - lastRingTapTime
                    
                    if (timeSinceLastTap < RING_TAP_COOLDOWN_MS) {
                        Log.d(TAG, "onSkipToNext ignored - cooldown active (${timeSinceLastTap}ms < ${RING_TAP_COOLDOWN_MS}ms)")
                        DebugMessageManager.addMessage("⏱️ Ring tap ignored - cooldown active")
                        return
                    }
                    
                    // Cooldown passed - legitimate ring tap
                    lastRingTapTime = currentTime
                    Log.d(TAG, "onSkipToNext accepted - time since last tap: ${timeSinceLastTap}ms")
                    DebugMessageManager.addMessage("✓ onSkipToNext() intercepted!")
                    Log.d(TAG, "onSkipToNext called - launching watch app")
                    launchWatchApp()
                }
            })

            // Set initial playback state directly
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
                    )
                    .build()
            )
            isActive = true
        }

        Log.d(TAG, "MediaSession created and activated")
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun startPlayback() {
        if (audioFiles.isEmpty()) {
            Log.w(TAG, "No audio files to play")
            DebugMessageManager.addMessage("⚠ No audio files to play")
            PlaybackStateManager.updateState(false, "No tracks")
            return
        }

        // Request audio focus like a real music player
        val audioFocusResult = requestAudioFocus()
        if (audioFocusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus not granted: $audioFocusResult")
            DebugMessageManager.addMessage("⚠ Audio focus not granted")
            return
        }

        try {
            mediaPlayer?.apply {
                if (!this.isPlaying) {
                    if (currentPosition == 0) {
                        // Load new track
                        reset()
                        val file = audioFiles[currentTrackIndex]
                        Log.d(TAG, "Loading track: ${file.name}")
                        val displayName = if (currentTrackIndex == 0 && file.name.contains("silent")) {
                            "Silent Music (60 min loop)"
                        } else {
                            file.nameWithoutExtension
                        }
                        DebugMessageManager.addMessage("♪ Playing: $displayName")
                        setDataSource(file.absolutePath)
                        prepare()
                    }
                    start()
                    this@RingListenerService.isPlaying = true

                    val currentFile = audioFiles[currentTrackIndex]
                    PlaybackStateManager.updateState(true, currentFile.nameWithoutExtension)

                    updatePlaybackState()
                    updateNotification()
                    Log.d(TAG, "Playback started")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback", e)
            DebugMessageManager.addMessage("✗ Playback error: ${e.message}")
            PlaybackStateManager.updatePlayingState(false)
        }
    }

    private fun pausePlayback() {
        // Abandon audio focus when pausing
        abandonAudioFocus()
        
        mediaPlayer?.apply {
            if (this.isPlaying) {
                pause()
                this@RingListenerService.isPlaying = false

                val currentFile = if (audioFiles.isNotEmpty() && currentTrackIndex < audioFiles.size) {
                    audioFiles[currentTrackIndex].nameWithoutExtension
                } else {
                    "No track"
                }
                PlaybackStateManager.updateState(false, currentFile)

                updatePlaybackState()
                updateNotification()
                Log.d(TAG, "Playback paused")
            }
        }
    }

    private fun skipToNext() {
        if (audioFiles.isEmpty()) return

        // If we're playing the silent track (index 0), just restart it for looping
        if (currentTrackIndex == 0) {
            Log.d(TAG, "60-minute silent track completed - restarting for loop")
            DebugMessageManager.addMessage("🔄 60-minute silent track looped")
            mediaPlayer?.reset()
            this.isPlaying = false
            startPlayback()
        } else {
            // For other tracks, go to next track
            currentTrackIndex = (currentTrackIndex + 1) % audioFiles.size
            mediaPlayer?.reset()
            this.isPlaying = false

            val nextFile = audioFiles[currentTrackIndex]
            PlaybackStateManager.updateTrack(nextFile.nameWithoutExtension)

            startPlayback()
            Log.d(TAG, "Skipped to next track (index: $currentTrackIndex)")
        }
    }

    private fun launchWatchApp() {
        DebugMessageManager.addMessage("Launching watch app...")

        serviceScope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                Log.d(TAG, "Found ${nodes.size} connected nodes")
                DebugMessageManager.addMessage("Found ${nodes.size} connected nodes")

                if (nodes.isEmpty()) {
                    DebugMessageManager.addMessage("⚠ No watch connected!")
                    Log.w(TAG, "No connected nodes")
                } else {
                    nodes.forEach { node ->
                        Log.d(TAG, "Sending launch message to: ${node.displayName}")
                        DebugMessageManager.addMessage("Sending to: ${node.displayName}")
                        messageClient.sendMessage(
                            node.id,
                            "/ring_tap",
                            ByteArray(0)
                        ).await()
                        Log.d(TAG, "Launch message sent successfully")
                        DebugMessageManager.addMessage("✓ Message sent successfully!")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error launching watch app", e)
                DebugMessageManager.addMessage("✗ Error: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        // Check Doze mode status
        val isDozeMode = isDeviceInDozeMode()
        if (isDozeMode) {
            Log.d(TAG, "Device is in Doze mode - maintaining service")
            DebugMessageManager.addMessage("⚠ Device in Doze mode - service maintained")
        }

        when (intent?.action) {
            ACTION_PLAY -> {
                Log.d(TAG, "Received ACTION_PLAY")
                startPlayback()
            }
            ACTION_PAUSE -> {
                Log.d(TAG, "Received ACTION_PAUSE")
                pausePlayback()
            }
            ACTION_TOGGLE_PLAYBACK -> {
                Log.d(TAG, "Received ACTION_TOGGLE_PLAYBACK")
                if (isPlaying) pausePlayback() else startPlayback()
            }
            "ALARM_WAKE_UP" -> {
                Log.d(TAG, "Received ALARM_WAKE_UP - Auxio-style maintenance")
                DebugMessageManager.addMessage("🔔 Alarm wake-up - Auxio-style maintenance")
                
                // Re-acquire wake lock if needed
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire()
                    Log.d(TAG, "Re-acquired wake lock")
                }
                
                // Ensure audio focus is held
                requestAudioFocus()
                
                // Ensure playback is running
                if (audioFiles.isNotEmpty()) {
                    if (!isPlaying) {
                        startPlayback()
                    } else {
                        // Refresh playback state even if playing
                        updatePlaybackState()
                        updateNotification()
                    }
                }
                
                // Ensure MediaSession is active
                if (::mediaSession.isInitialized && !mediaSession.isActive) {
                    mediaSession.isActive = true
                    Log.d(TAG, "MediaSession reactivated")
                }
                
                // Reschedule next alarm
                scheduleNextAlarm()
                
                Log.d(TAG, "Auxio-style maintenance completed")
            }
            null -> {
                // Service started without specific action - ensure it's running
                Log.d(TAG, "Service started without action - ensuring playback")
                if (audioFiles.isNotEmpty() && !isPlaying) {
                    startPlayback()
                }
            }
        }

        // Return START_STICKY to ensure service restarts if killed by system
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")

        mediaPlayer?.apply {
            if (this.isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null

        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }

        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
        
        // Cancel alarm
        alarmPendingIntent?.let { pendingIntent ->
            alarmManager?.cancel(pendingIntent)
        }
        alarmPendingIntent = null
        
        // Abandon audio focus
        abandonAudioFocus()

        serviceJob.cancel()
        DebugMessageManager.addMessage("Ring listener service stopped")
        
        // Schedule service restart if it was killed unexpectedly
        scheduleServiceRestart()
    }
    
    private fun scheduleServiceRestart() {
        try {
            val intent = Intent(this, RingListenerService::class.java)
            startForegroundService(intent)
            Log.d(TAG, "Scheduled service restart")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service restart", e)
        }
    }

    private fun scheduleNextAlarm() {
        try {
            alarmPendingIntent?.let { pendingIntent ->
                // Schedule next alarm in 2 minutes for Doze mode resistance (Auxio-style)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager?.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 2 * 60 * 1000, // 2 minutes
                        pendingIntent
                    )
                } else {
                    alarmManager?.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 2 * 60 * 1000, // 2 minutes
                        pendingIntent
                    )
                }
                Log.d(TAG, "Next alarm scheduled in 2 minutes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule next alarm", e)
        }
    }

    private fun maintainServiceInDozeMode() {
        serviceScope.launch {
            while (true) {
                try {
                    val isDozeMode = isDeviceInDozeMode()
                    if (isDozeMode) {
                        Log.d(TAG, "Maintaining service in Doze mode")
                        DebugMessageManager.addMessage("🔄 Maintaining service in Doze mode")
                        
                        // Ensure notification is active
                        updateNotification()
                        
                        // Ensure MediaSession is active
                        if (::mediaSession.isInitialized && !mediaSession.isActive) {
                            mediaSession.isActive = true
                            Log.d(TAG, "MediaSession reactivated in Doze mode")
                        }
                    }
                    
                    // Check every 30 seconds
                    kotlinx.coroutines.delay(30000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error maintaining service in Doze mode", e)
                    kotlinx.coroutines.delay(60000) // Wait longer on error
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
