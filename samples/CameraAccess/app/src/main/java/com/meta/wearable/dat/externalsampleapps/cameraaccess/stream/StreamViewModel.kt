/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> ARGB conversion)

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.annotation.SuppressLint
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.session.Session
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val SESSION_TERMINAL_STATES = setOf(StreamSessionState.CLOSED)
    private const val LIVEKIT_PREFS_NAME = "camera_access_livekit"
    private const val LIVEKIT_URL_KEY = "livekit_url"
    private const val LIVEKIT_TOKEN_KEY = "livekit_token"
    private const val LOCATION_PUBLISH_INTERVAL_MS = 1000L
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: Session? = null
  private val liveKitPrefs: SharedPreferences =
      application.getSharedPreferences(LIVEKIT_PREFS_NAME, Application.MODE_PRIVATE)
  private val locationManager =
      application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var liveKitStateJob: Job? = null
  private var stream: Stream? = null
  private var liveKitPublisher: LiveKitPublisher? = LiveKitPublisher(application)
  private var lastLocationPublishMs = 0L
  private var isLocationPublishing = false
  private val locationListener = LocationListener { location -> publishPhoneLocation(location) }

  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null

  init {
    loadSavedLiveKitSettings()
    liveKitStateJob =
        viewModelScope.launch {
          liveKitPublisher?.connectionState?.collect { state ->
            _uiState.update {
              when (state) {
                LiveKitPublisher.ConnectionState.Disconnected ->
                    it.copy(
                        liveKitStatus = "LiveKit disconnected",
                        isLiveKitConnected = false,
                        isLiveKitConnecting = false,
                    )
                LiveKitPublisher.ConnectionState.Connecting ->
                    it.copy(
                        liveKitStatus = "LiveKit connecting...",
                        isLiveKitConnected = false,
                        isLiveKitConnecting = true,
                    )
                LiveKitPublisher.ConnectionState.Connected ->
                    it.copy(
                        liveKitStatus = "LiveKit connected",
                        isLiveKitConnected = true,
                        isLiveKitConnecting = false,
                    )
                is LiveKitPublisher.ConnectionState.Error ->
                    it.copy(
                        liveKitStatus = "LiveKit error: ${state.message}",
                        isLiveKitConnected = false,
                        isLiveKitConnecting = false,
                    )
              }
            }
            when (state) {
              LiveKitPublisher.ConnectionState.Connected -> startLocationPublishing()
              LiveKitPublisher.ConnectionState.Connecting -> Unit
              LiveKitPublisher.ConnectionState.Disconnected,
              is LiveKitPublisher.ConnectionState.Error -> stopLocationPublishing()
            }
          }
        }
  }

  fun startStream() {
    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionStateJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null

    // Initialize presentation queue - frames are presented based on timestamp, not arrival time
    // Uses IntArray pooling for efficiency - cheaper than Bitmap.copy()
    val queue =
        PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              // This is called from the presentation thread at regular intervals
              // when a frame's presentation time has arrived
              _uiState.update {
                it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
              }
            },
        )
    presentationQueue = queue
    queue.start()
    if (session == null) {
      Wearables.createSession(deviceSelector)
          .onSuccess { createdSession ->
            session = createdSession
            session?.start()
          }
          .onFailure { error, _ -> Log.e(TAG, "Failed to create session: ${error.description}") }
      if (session == null) return
    }
    startStreamInternal()
  }

  private fun startStreamInternal() {
    Log.d(TAG, "startStreamInternal() - collecting session state")
    sessionStateJob =
        viewModelScope.launch {
          session?.state?.collect { currentState ->
            if (currentState == DeviceSessionState.STARTED) {
              videoJob?.cancel()
              stateJob?.cancel()
              errorJob?.cancel()
              stream?.stop()
              stream = null
              session
                  ?.addStream(StreamConfiguration(videoQuality = VideoQuality.HIGH, 24))
                  ?.onSuccess { addedStream ->
                    stream = addedStream
                    videoJob =
                        viewModelScope.launch(Dispatchers.Default) {
                          Log.d(TAG, "Collecting video frames from stream")
                          stream?.videoStream?.collect { handleVideoFrame(it) }
                          Log.d(TAG, "Video stream collection ended")
                        }
                    stateJob =
                        viewModelScope.launch {
                          stream?.state?.collect { currentState ->
                            val prevState = _uiState.value.streamSessionState
                            Log.d(TAG, "Stream state changed: $prevState -> $currentState")
                            _uiState.update { it.copy(streamSessionState = currentState) }

                            val wasActive = prevState !in SESSION_TERMINAL_STATES
                            val isTerminated = currentState in SESSION_TERMINAL_STATES
                            if (wasActive && isTerminated) {
                              Log.d(TAG, "Terminal state reached, navigating back")
                              stopStream()
                              wearablesViewModel.navigateToDeviceSelection()
                            }
                          }
                        }
                    errorJob =
                        viewModelScope.launch {
                          stream?.errorStream?.collect { error ->
                            Log.d(
                                TAG,
                                "Stream error received: $error (description: ${error.description})",
                            )
                            if (error == StreamError.HINGE_CLOSED) {
                              Log.d(
                                  TAG,
                                  "HINGE_CLOSED detected, stopping stream and navigating back",
                              )
                              stopStream()
                              wearablesViewModel.navigateToDeviceSelection()
                            }
                          }
                        }
                    stream?.start()
                  }
                  ?.onFailure { error, _ ->
                    Log.e(TAG, "Failed to add stream to session: ${error.description}")
                  }
            }
          }
        }
  }

  fun stopStream() {
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    presentationQueue?.stop()
    presentationQueue = null
    stream?.stop()
    stream = null
    session?.stop()
    session = null
    stopLocationPublishing()
    liveKitPublisher?.disconnect()
    _uiState.update {
      INITIAL_STATE.copy(
          liveKitUrl = it.liveKitUrl,
          liveKitToken = it.liveKitToken,
      )
    }
  }

  fun updateLiveKitUrl(url: String) {
    _uiState.update { it.copy(liveKitUrl = url) }
    saveLiveKitSettings(url = url)
  }

  fun updateLiveKitToken(token: String) {
    _uiState.update { it.copy(liveKitToken = token) }
    saveLiveKitSettings(token = token)
  }

  fun connectLiveKit() {
    val state = _uiState.value
    saveLiveKitSettings(url = state.liveKitUrl, token = state.liveKitToken)
    liveKitPublisher?.connect(state.liveKitUrl, state.liveKitToken)
  }

  fun disconnectLiveKit() {
    stopLocationPublishing()
    liveKitPublisher?.disconnect()
  }

  fun fetchLiveKitToken() {
    val liveKitUrl = _uiState.value.liveKitUrl.trim()
    if (liveKitUrl.isBlank()) {
      _uiState.update { it.copy(liveKitStatus = "Enter LiveKit URL before fetching token") }
      return
    }

    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update {
        it.copy(
            isLiveKitTokenFetching = true,
            liveKitStatus = "Fetching LiveKit token...",
        )
      }
      try {
        val tokenEndpoint = buildDevTokenEndpoint(liveKitUrl)
        val connection = URL(tokenEndpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 3000
        connection.readTimeout = 3000

        val responseCode = connection.responseCode
        val body =
            if (responseCode in 200..299) {
              connection.inputStream.bufferedReader().use { it.readText() }
            } else {
              connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
        connection.disconnect()

        if (responseCode !in 200..299) {
          throw IOException("Token endpoint returned HTTP $responseCode: $body")
        }

        val token = JSONObject(body).getString("token")
        saveLiveKitSettings(url = liveKitUrl, token = token)
        _uiState.update {
          it.copy(
              liveKitToken = token,
              liveKitStatus = "LiveKit token fetched",
              isLiveKitTokenFetching = false,
          )
        }
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to fetch LiveKit token", t)
        _uiState.update {
          it.copy(
              liveKitStatus = "Token fetch failed: ${t.message ?: "unknown error"}",
              isLiveKitTokenFetching = false,
          )
        }
      }
    }
  }

  private fun buildDevTokenEndpoint(liveKitUrl: String): String {
    val httpUrl =
        liveKitUrl
            .replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")
    val uri = URI(httpUrl)
    val scheme = if (uri.scheme == "https") "https" else "http"
    val host = uri.host ?: throw IllegalArgumentException("Invalid LiveKit URL")
    val room = URLEncoder.encode("test-room", StandardCharsets.UTF_8.name())
    val identity = URLEncoder.encode("android-oakley", StandardCharsets.UTF_8.name())
    return "$scheme://$host:8080/token?room=$room&identity=$identity"
  }

  private fun loadSavedLiveKitSettings() {
    val savedUrl = liveKitPrefs.getString(LIVEKIT_URL_KEY, "").orEmpty()
    val savedToken = liveKitPrefs.getString(LIVEKIT_TOKEN_KEY, "").orEmpty()
    if (savedUrl.isNotBlank() || savedToken.isNotBlank()) {
      _uiState.update {
        it.copy(
            liveKitUrl = savedUrl,
            liveKitToken = savedToken,
        )
      }
    }
  }

  private fun saveLiveKitSettings(url: String? = null, token: String? = null) {
    liveKitPrefs
        .edit()
        .apply {
          url?.let { putString(LIVEKIT_URL_KEY, it) }
          token?.let { putString(LIVEKIT_TOKEN_KEY, it) }
        }
        .apply()
  }

  private fun startLocationPublishing() {
    if (isLocationPublishing) {
      return
    }

    if (!hasLocationPermission()) {
      Log.w(TAG, "Phone GPS metadata disabled; location permission is not granted")
      return
    }

    var started = false
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
      try {
        if (locationManager.isProviderEnabled(provider)) {
          locationManager.requestLocationUpdates(
              provider,
              LOCATION_PUBLISH_INTERVAL_MS,
              0f,
              locationListener,
              Looper.getMainLooper(),
          )
          locationManager.getLastKnownLocation(provider)?.let { publishPhoneLocation(it) }
          started = true
        }
      } catch (securityException: SecurityException) {
        Log.w(TAG, "Phone GPS metadata disabled; permission was denied", securityException)
      } catch (t: Throwable) {
        Log.w(TAG, "Unable to start phone GPS metadata from provider=$provider", t)
      }
    }

    isLocationPublishing = started
    if (started) {
      Log.d(TAG, "Started phone GPS metadata publishing")
    } else {
      Log.w(TAG, "Phone GPS metadata disabled; no location provider is enabled")
    }
  }

  private fun stopLocationPublishing() {
    if (!isLocationPublishing) {
      return
    }

    try {
      locationManager.removeUpdates(locationListener)
    } catch (t: Throwable) {
      Log.w(TAG, "Unable to stop phone GPS metadata publishing", t)
    }
    isLocationPublishing = false
  }

  private fun hasLocationPermission(): Boolean {
    val context = getApplication<Application>()
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
  }

  private fun publishPhoneLocation(location: Location) {
    val nowMs = System.currentTimeMillis()
    if (nowMs - lastLocationPublishMs < LOCATION_PUBLISH_INTERVAL_MS) {
      return
    }
    lastLocationPublishMs = nowMs

    val payload =
        JSONObject()
            .put("type", "phone_gps")
            .put("source", "phone")
            .put("provider", location.provider)
            .put("lat", location.latitude)
            .put("lon", location.longitude)
            .put("accuracy_m", location.accuracy.toDouble())
            .put("timestamp_ms", location.time)
    if (location.hasAltitude()) {
      payload.put("altitude_m", location.altitude)
    }
    if (location.hasBearing()) {
      payload.put("bearing_deg", location.bearing.toDouble())
    }
    if (location.hasSpeed()) {
      payload.put("speed_mps", location.speed.toDouble())
    }
    liveKitPublisher?.publishLocation(payload.toString())
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        stream
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              Log.d(TAG, "Photo capture successful")
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            ?.onFailure { error, _ ->
              Log.e(TAG, "Photo capture failed: ${error.description}")
              _uiState.update { it.copy(isCapturing = false) }
            }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    liveKitPublisher?.publishDatFrame(videoFrame)

    // VideoFrame contains raw I420 video data in a ByteBuffer
    // Use optimized YuvToBitmapConverter for direct I420 to ARGB conversion
    val bitmap =
        YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height,
        )
    if (bitmap != null) {
      presentationQueue?.enqueue(
          bitmap,
          videoFrame.presentationTimeUs,
      )
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    liveKitStateJob?.cancel()
    stopStream()
    session?.stop()
    session = null
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
