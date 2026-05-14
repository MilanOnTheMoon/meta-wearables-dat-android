package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.video.VideoFrameCapturer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.VideoFrame as WebRtcVideoFrame

class LiveKitPublisher(context: Context) {
  companion object {
    private const val TAG = "LiveKitPublisher"
    private const val TRACK_NAME = "oakley-meta-camera"
    private const val DEFAULT_WIDTH = 504
    private const val DEFAULT_HEIGHT = 896
    private const val DEFAULT_FPS = 24
    private const val LOG_FRAME_INTERVAL = 30
  }

  sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Error(val message: String) : ConnectionState
  }

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val frameScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val connectionMutex = Mutex()
  private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
  private val isPushingFrame = AtomicBoolean(false)

  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  private var room: Room? = null
  private var capturer: VideoFrameCapturer? = null
  private var localVideoTrack: LocalVideoTrack? = null
  private var isTrackPublished = false
  private var pushedFrameCount = 0

  fun connect(url: String, token: String) {
    scope.launch {
      connectionMutex.withLock {
        if (_connectionState.value == ConnectionState.Connected ||
            _connectionState.value == ConnectionState.Connecting) {
          Log.d(TAG, "connect() ignored; already connected or connecting")
          return@withLock
        }

        if (url.isBlank() || token.isBlank()) {
          _connectionState.value = ConnectionState.Error("LiveKit URL and token are required")
          return@withLock
        }

        _connectionState.value = ConnectionState.Connecting
        try {
          val nextRoom = LiveKit.create(appContext)
          val nextCapturer = VideoFrameCapturer()
          val nextTrack =
              nextRoom.localParticipant.createVideoTrack(
                  name = TRACK_NAME,
                  capturer = nextCapturer,
                  options =
                      LocalVideoTrackOptions(
                          captureParams =
                              VideoCaptureParameter(
                                  DEFAULT_WIDTH,
                                  DEFAULT_HEIGHT,
                                  DEFAULT_FPS,
                                  adaptOutputToDimensions = false,
                              ),
                      ),
              )

          nextRoom.connect(url.trim(), token.trim())
          nextTrack.startCapture()
          isTrackPublished =
              nextRoom.localParticipant.publishVideoTrack(
                  nextTrack,
                  VideoTrackPublishOptions(
                      name = TRACK_NAME,
                      simulcast = false,
                      source = Track.Source.CAMERA,
                  ),
              )

          if (!isTrackPublished) {
            nextTrack.stopCapture()
            nextRoom.release()
            _connectionState.value = ConnectionState.Error("Failed to publish LiveKit video track")
            return@withLock
          }

          room = nextRoom
          capturer = nextCapturer
          localVideoTrack = nextTrack
          pushedFrameCount = 0
          _connectionState.value = ConnectionState.Connected
          Log.d(TAG, "Connected to LiveKit and published track=$TRACK_NAME")
        } catch (t: Throwable) {
          Log.e(TAG, "Failed to connect to LiveKit", t)
          cleanup()
          _connectionState.value = ConnectionState.Error(t.message ?: "LiveKit connection failed")
        }
      }
    }
  }

  fun publishDatFrame(frame: VideoFrame) {
    val currentCapturer = capturer
    if (_connectionState.value != ConnectionState.Connected ||
        !isTrackPublished ||
        currentCapturer == null) {
      return
    }

    if (!isPushingFrame.compareAndSet(false, true)) {
      return
    }

    try {
      val webRtcFrame =
          frame.toWebRtcVideoFrame()
              ?: run {
                isPushingFrame.set(false)
                return
              }
      val width = frame.width
      val height = frame.height
      frameScope.launch {
        try {
          currentCapturer.pushVideoFrame(webRtcFrame)
          webRtcFrame.release()

          pushedFrameCount += 1
          if (pushedFrameCount % LOG_FRAME_INTERVAL == 0) {
            Log.d(TAG, "Published $pushedFrameCount LiveKit frames (${width}x${height})")
          }
        } catch (t: Throwable) {
          Log.e(TAG, "Failed to push DAT frame to LiveKit", t)
        } finally {
          isPushingFrame.set(false)
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to publish DAT frame to LiveKit", t)
      isPushingFrame.set(false)
    }
  }

  fun disconnect() {
    scope.launch {
      connectionMutex.withLock {
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
        Log.d(TAG, "Disconnected from LiveKit")
      }
    }
  }

  private fun cleanup() {
    isTrackPublished = false
    isPushingFrame.set(false)
    val currentRoom = room
    val currentTrack = localVideoTrack
    room = null
    localVideoTrack = null
    capturer = null

    try {
      currentTrack?.stopCapture()
    } catch (t: Throwable) {
      Log.w(TAG, "Error stopping LiveKit video track", t)
    }

    try {
      currentRoom?.release()
    } catch (t: Throwable) {
      Log.w(TAG, "Error releasing LiveKit room", t)
    }
  }

  private fun VideoFrame.toWebRtcVideoFrame(): WebRtcVideoFrame? {
    val width = width
    val height = height
    if (width <= 0 || height <= 0) {
      Log.w(TAG, "Skipping invalid DAT frame size: ${width}x$height")
      return null
    }

    val ySize = width * height
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val chromaSize = chromaWidth * chromaHeight
    val requiredBytes = ySize + chromaSize + chromaSize
    val source = buffer.duplicate()
    if (source.remaining() < requiredBytes) {
      Log.w(TAG, "Skipping short DAT frame buffer: ${source.remaining()} < $requiredBytes")
      return null
    }

    val copy = ByteBuffer.allocateDirect(requiredBytes)
    source.limit(source.position() + requiredBytes)
    copy.put(source)
    copy.flip()

    val yBuffer = copy.sliceWithRange(0, ySize)
    val uBuffer = copy.sliceWithRange(ySize, chromaSize)
    val vBuffer = copy.sliceWithRange(ySize + chromaSize, chromaSize)

    // DAT docs describe I420 as Y, then U, then V. TODO: if colors look wrong, test swapped U/V.
    val i420Buffer =
        JavaI420Buffer.wrap(
            width,
            height,
            yBuffer,
            width,
            uBuffer,
            chromaWidth,
            vBuffer,
            chromaWidth,
        ) {
          // The direct ByteBuffer copy is owned by this JavaI420Buffer and can be GC'd after release.
        }
    return WebRtcVideoFrame(i420Buffer, 0, presentationTimeUs * 1000L)
  }

  private fun ByteBuffer.sliceWithRange(offset: Int, length: Int): ByteBuffer {
    val duplicate = duplicate()
    duplicate.position(offset)
    duplicate.limit(offset + length)
    return duplicate.slice()
  }
}
