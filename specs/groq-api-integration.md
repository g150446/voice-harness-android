# Groq API Integration Specification

## Overview

Harness Voice integrates with Groq's API for voice transcription and LLM-powered response generation. The watch app uses two Groq API endpoints: Whisper for audio-to-text transcription and Chat Completions for generating contextual responses.

## API Endpoints

### 1. Whisper Audio Transcription

**Endpoint**: `https://api.groq.com/openai/v1/audio/transcriptions`

**Method**: POST

**Content-Type**: `multipart/form-data`

**Model**: `whisper-large-v3-turbo`

### 2. Chat Completions

**Endpoint**: `https://api.groq.com/openai/v1/chat/completions`

**Method**: POST

**Content-Type**: `application/json`

**Model**: `openai/gpt-oss-120b`

## Authentication

### API Key

**Format**: `gsk_...` (Groq API key)

**Storage**:
- Phone: SharedPreferences (`groq_prefs`)
- Watch: SharedPreferences via WearGroqPrefs

**Header**:
```
Authorization: Bearer {GROQ_API_KEY}
```

**Configuration Flow**:
1. User opens Groq Settings in phone app
2. Enters API key
3. Saved to phone SharedPreferences
4. Synced to watch via Data Layer API
5. Watch stores in WearGroqPrefs

## Audio Transcription API

### Request Format

**Multipart Form Data**:
```kotlin
val requestBody = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart(
        "file",
        audioFile.name,
        audioFile.asRequestBody("audio/m4a".toMediaType())
    )
    .addFormDataPart("model", "whisper-large-v3-turbo")
    .addFormDataPart("response_format", "json")
    .build()

val request = Request.Builder()
    .url("https://api.groq.com/openai/v1/audio/transcriptions")
    .addHeader("Authorization", "Bearer $apiKey")
    .post(requestBody)
    .build()
```

### Request Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | File | Yes | Audio file (M4A format) |
| `model` | String | Yes | `whisper-large-v3-turbo` |
| `response_format` | String | No | `json` (default) |
| `language` | String | No | ISO-639-1 language code |
| `temperature` | Float | No | 0.0 - 1.0 (default: 0) |

### Response Format

**Success (200 OK)**:
```json
{
    "text": "This is the transcribed text from the audio file."
}
```

**Error (4xx/5xx)**:
```json
{
    "error": {
        "message": "Error description",
        "type": "invalid_request_error",
        "code": "invalid_api_key"
    }
}
```

### Response Processing

```kotlin
val response = client.newCall(request).execute()
if (response.isSuccessful) {
    val jsonResponse = JSONObject(response.body!!.string())
    val transcription = jsonResponse.getString("text")
    // Use transcription
} else {
    val errorBody = response.body?.string() ?: "Unknown error"
    // Handle error
}
```

## Chat Completions API

### Request Format

**JSON Body**:
```kotlin
val jsonBody = JSONObject().apply {
    put("model", "openai/gpt-oss-120b")
    put("messages", JSONArray().apply {
        put(JSONObject().apply {
            put("role", "user")
            put("content", transcribedText)
        })
    })
    put("temperature", 0.7)
    put("max_tokens", 500)
}

val requestBody = jsonBody.toString()
    .toRequestBody("application/json".toMediaType())

val request = Request.Builder()
    .url("https://api.groq.com/openai/v1/chat/completions")
    .addHeader("Authorization", "Bearer $apiKey")
    .post(requestBody)
    .build()
```

### Request Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `model` | String | Yes | `openai/gpt-oss-120b` |
| `messages` | Array | Yes | Conversation history |
| `temperature` | Float | No | 0.0 - 2.0 (default: 0.7) |
| `max_tokens` | Integer | No | Maximum response length (default: 500) |
| `top_p` | Float | No | Nucleus sampling parameter |
| `stream` | Boolean | No | Enable streaming (not used) |

### Message Format

```json
{
    "role": "user",     // or "system", "assistant"
    "content": "User message text"
}
```

**Roles**:
- `system`: System instructions (optional)
- `user`: User messages
- `assistant`: Model responses

### Response Format

**Success (200 OK)**:
```json
{
    "id": "chatcmpl-...",
    "object": "chat.completion",
    "created": 1234567890,
    "model": "openai/gpt-oss-120b",
    "choices": [
        {
            "index": 0,
            "message": {
                "role": "assistant",
                "content": "This is the LLM-generated response."
            },
            "finish_reason": "stop"
        }
    ],
    "usage": {
        "prompt_tokens": 10,
        "completion_tokens": 20,
        "total_tokens": 30
    }
}
```

**Error (4xx/5xx)**:
```json
{
    "error": {
        "message": "Error description",
        "type": "invalid_request_error",
        "code": "invalid_api_key"
    }
}
```

### Response Processing

```kotlin
val response = client.newCall(request).execute()
if (response.isSuccessful) {
    val jsonResponse = JSONObject(response.body!!.string())
    val choices = jsonResponse.getJSONArray("choices")
    val message = choices.getJSONObject(0).getJSONObject("message")
    val llmResponse = message.getString("content")
    // Use LLM response
} else {
    val errorBody = response.body?.string() ?: "Unknown error"
    // Handle error
}
```

## Audio Recording

### Recording Configuration

**Format**: AAC (Advanced Audio Coding)

**Container**: MPEG-4 Part 14 (.m4a)

**Sample Rate**: 44100 Hz

**Channels**: Mono (1 channel)

**Bitrate**: 128 kbps

**Encoder**: AAC

### MediaRecorder Setup

```kotlin
mediaRecorder = MediaRecorder().apply {
    setAudioSource(MediaRecorder.AudioSource.MIC)
    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    setAudioSamplingRate(44100)
    setAudioEncodingBitRate(128000)
    setAudioChannels(1)
    setOutputFile(outputFile.absolutePath)
    prepare()
    start()
}
```

### File Management

**Location**: Cache directory (`context.cacheDir`)

**Filename**: `voice_recording_${timestamp}.m4a`

**Cleanup**: Automatic cleanup after API upload

```kotlin
outputFile = File(cacheDir, "voice_recording_${System.currentTimeMillis()}.m4a")
```

## Network Configuration

### OkHttp Client

**Configuration**:
```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
```

**Timeouts**:
- Connect: 30 seconds
- Read: 30 seconds (for large audio files)
- Write: 30 seconds (for uploads)

### Request Execution

**Synchronous** (on background thread):
```kotlin
CoroutineScope(Dispatchers.IO).launch {
    try {
        val response = client.newCall(request).execute()
        // Process response
        withContext(Dispatchers.Main) {
            // Update UI
        }
    } catch (e: Exception) {
        // Handle error
    }
}
```

## Error Handling

### Network Errors

**Connection Failure**:
```kotlin
catch (e: IOException) {
    Log.e(TAG, "Network error: ${e.message}")
    statusMessage = "Network error - check connection"
}
```

**Timeout**:
```kotlin
catch (e: SocketTimeoutException) {
    Log.e(TAG, "Request timeout: ${e.message}")
    statusMessage = "Request timeout - try again"
}
```

### API Errors

**Invalid API Key (401)**:
```kotlin
if (response.code == 401) {
    statusMessage = "Invalid API key - check settings"
}
```

**Rate Limit (429)**:
```kotlin
if (response.code == 429) {
    statusMessage = "Rate limit exceeded - try later"
}
```

**Server Error (5xx)**:
```kotlin
if (response.code >= 500) {
    statusMessage = "Server error - try again later"
}
```

### Audio Recording Errors

**Permission Denied**:
```kotlin
if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
    != PackageManager.PERMISSION_GRANTED) {
    // Request permission
}
```

**Recording Failure**:
```kotlin
try {
    mediaRecorder?.prepare()
    mediaRecorder?.start()
} catch (e: IOException) {
    Log.e(TAG, "Recording failed: ${e.message}")
    statusMessage = "Recording failed"
}
```

## Workflow Integration

### Complete Flow

```
1. Wrist Flexion Gesture
    ↓
2. Start MediaRecorder
    ↓
3. Record Audio to File
    ↓
4. Wrist Flexion Gesture
    ↓
5. Stop MediaRecorder
    ↓
6. Upload to Whisper API
    ↓
7. Display Transcription
    ↓
8. Send to Chat Completions API
    ↓
9. Display LLM Response
    ↓
10. Enable Gesture Scrolling
```

### Status Updates

**Recording**:
- Status: "Recording..."
- UI: Pulsing microphone icon
- Screen: Stays on (ambient mode disabled)

**Transcribing**:
- Status: "Transcribing..."
- UI: Loading indicator
- Network: POST to Whisper API

**Getting Response**:
- Status: "Getting response..."
- UI: Loading indicator
- Network: POST to Chat Completions API

**Display Results**:
- Status: Transcription + LLM response
- UI: Scrollable text
- Timeout: 15 seconds (extends with gestures)

## Performance Characteristics

### API Response Times

**Whisper Transcription**:
- 5-second audio: ~2-3 seconds
- 10-second audio: ~3-5 seconds
- 30-second audio: ~5-10 seconds

**Chat Completions**:
- Short prompt: ~1-2 seconds
- Medium prompt: ~2-4 seconds
- Long prompt: ~4-6 seconds

### File Sizes

**Audio Recording**:
- 5 seconds: ~80 KB
- 10 seconds: ~160 KB
- 30 seconds: ~480 KB
- 60 seconds: ~960 KB

**Network Usage**:
- Upload: Audio file size
- Download: Typically < 5 KB for text response

## Security and Privacy

### Data Transmission

**Encryption**:
- HTTPS for all API requests
- TLS 1.2+ required
- Certificate pinning not implemented (uses system certificates)

**Data Retention**:
- Audio files deleted after upload
- Transcriptions displayed but not permanently stored
- LLM responses displayed but not permanently stored

### API Key Security

**Storage**: SharedPreferences (private to app)

**Transmission**: Only via encrypted Bluetooth (Data Layer API)

**Exposure**: Never logged or displayed in plain text

**Access**: Only accessible by this app on phone and watch

## Rate Limits and Quotas

### Groq API Limits

**Free Tier** (typical):
- Requests per minute: 30
- Requests per day: 14,400
- Token limits: Varies by model

**Handling**:
- No automatic retry on rate limit
- User must wait and try again
- Status message displayed: "Rate limit exceeded"

### Best Practices

- Wait for previous request to complete before starting new one
- Delete old audio files to save storage
- Monitor API usage in Groq dashboard
- Consider upgrade for heavy usage

## Testing

### Manual Testing

**Transcription Test**:
1. Start voice recording
2. Speak clearly for 5-10 seconds
3. Stop recording
4. Verify transcription accuracy
5. Check for API errors

**Chat Completions Test**:
1. Record voice input
2. Wait for transcription
3. Verify LLM response is relevant
4. Check response quality

**Error Testing**:
1. Test with invalid API key (401 error)
2. Test with no network (timeout)
3. Test with very long audio (> 60s)

### Debugging

**Network Logging**:
```kotlin
val loggingInterceptor = HttpLoggingInterceptor().apply {
    level = HttpLoggingInterceptor.Level.BODY
}
val client = OkHttpClient.Builder()
    .addInterceptor(loggingInterceptor)
    .build()
```

**API Response Logging**:
```kotlin
Log.d(TAG, "Transcription: $transcription")
Log.d(TAG, "LLM Response: $llmResponse")
Log.d(TAG, "Response code: ${response.code}")
```

## Future Enhancements

### Planned Features
- Streaming response support (real-time LLM output)
- Multi-language support (language detection and selection)
- Conversation history (multi-turn chat)
- Custom system prompts (user-configurable behavior)
- Response caching (reduce API calls for common queries)

### Alternative Models
- Whisper: `whisper-large-v3` (more accurate, slower)
- Chat: `llama-3.1-70b-versatile` (different capabilities)
- Chat: `mixtral-8x7b-32768` (longer context window)
