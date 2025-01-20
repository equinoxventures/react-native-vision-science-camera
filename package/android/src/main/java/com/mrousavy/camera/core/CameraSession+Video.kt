package com.mrousavy.camera.core

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.camera.core.Camera
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.VideoRecordEvent
import com.mrousavy.camera.core.CameraSession.Companion.TAG
import com.mrousavy.camera.core.extensions.getCameraError
import com.mrousavy.camera.core.types.RecordVideoOptions
import com.mrousavy.camera.core.types.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalPersistentRecording::class)
@SuppressLint("MissingPermission", "RestrictedApi")
fun CameraSession.startRecording(
  enableAudio: Boolean,
  options: RecordVideoOptions,
  callback: (video: Video) -> Unit,
  onError: (error: CameraError) -> Unit
) {
  if (camera == null) throw CameraNotReadyError()
  if (recording != null) throw RecordingInProgressError()
  val videoOutput = videoOutput ?: throw VideoNotEnabledError()

  // Create output video file
  val outputOptions = FileOutputOptions.Builder(options.file.file).also { outputOptions ->
    metadataProvider.location?.let { location ->
      Log.i(CameraSession.TAG, "Setting Video Location to ${location.latitude}, ${location.longitude}...")
      outputOptions.setLocation(location)
    }
  }.build()

  // TODO: Move this to JS so users can prepare recordings earlier
  // Prepare recording
  var pendingRecording = videoOutput.output.prepareRecording(context, outputOptions)
  if (enableAudio) {
    checkMicrophonePermission()
    pendingRecording = pendingRecording.withAudioEnabled()
  }
  pendingRecording = pendingRecording.asPersistentRecording()

  isRecordingCanceled = false
  recording = pendingRecording.start(CameraQueues.cameraExecutor) { event ->
    when (event) {
//      is VideoRecordEvent.Start -> Log.i(CameraSession.TAG, "Recording started!")
      is VideoRecordEvent.Start -> {
        Log.i(TAG, "Recording started!")

        if (configuration?.backgroundDelay?.toInt()!! > 0) {
          setTorchMode("off", torchLevelVal = configuration?.torchLevel, camera!!)
        }

        val recordingStartTimestamp = Instant.now().epochSecond
        recordingTimestamps.actualRecordingStartedAt = Instant.now().epochSecond

        val torchDelay = configuration?.torchDelay?.toInt()?.milliseconds

        val torchDelayInt = configuration?.torchDelay?.toInt()?.milliseconds
        val torchDurationInt = configuration?.torchDuration?.toInt()?.milliseconds
        val torchEnd = torchDurationInt?.let { torchDelayInt?.plus(it) }

        if (configuration?.torchDuration?.toInt()!! > 0) {
          CoroutineScope(Dispatchers.Main).launch {
            if (torchDelay != null) {
              delay(torchDelay)
              recordingTimestamps.requestTorchOnAt = Instant.now().epochSecond
              setTorchMode("on", torchLevelVal = configuration?.torchLevel, camera!!)
              recordingTimestamps.actualTorchOnAt = Instant.now().epochSecond
            }
          }
          CoroutineScope(Dispatchers.Main).launch {
            if (torchEnd != null) {
              delay(torchEnd)
              recordingTimestamps.requestTorchOffAt = Instant.now().epochSecond
              setTorchMode("off", torchLevelVal = configuration?.torchLevel, camera!!)
              recordingTimestamps.actualTorchOffAt = Instant.now().epochSecond
            }
          }
        }
      }

      is VideoRecordEvent.Resume -> Log.i(CameraSession.TAG, "Recording resumed!")

      is VideoRecordEvent.Pause -> Log.i(CameraSession.TAG, "Recording paused!")

      is VideoRecordEvent.Status -> Log.i(CameraSession.TAG, "Status update! Recorded ${event.recordingStats.numBytesRecorded} bytes.")

      is VideoRecordEvent.Finalize -> {
        if (isRecordingCanceled) {
          Log.i(CameraSession.TAG, "Recording was canceled, deleting file..")
          onError(RecordingCanceledError())
          try {
            options.file.file.delete()
          } catch (e: Throwable) {
            this.callback.onError(FileIOError(e))
          }
          return@start
        }

        Log.i(CameraSession.TAG, "Recording stopped!")
        val error = event.getCameraError()
        if (error != null) {
          if (error.wasVideoRecorded) {
            Log.e(CameraSession.TAG, "Video Recorder encountered an error, but the video was recorded anyways.", error)
          } else {
            Log.e(CameraSession.TAG, "Video Recorder encountered a fatal error!", error)
            onError(error)
            return@start
          }
        }

        // Prepare output result
        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
        Log.i(CameraSession.TAG, "Successfully completed video recording! Captured ${durationMs.toDouble() / 1_000.0} seconds.")
        val path = event.outputResults.outputUri.path ?: throw UnknownRecorderError(false, null)
        val size = videoOutput.attachedSurfaceResolution ?: Size(0, 0)

        val metadata = mapOf(
          "actualRecordingStartedAt" to recordingTimestamps.actualRecordingStartedAt,
          "actualTorchOnAt" to recordingTimestamps.actualTorchOnAt,
          "actualTorchOffAt" to recordingTimestamps.actualTorchOffAt,
          "actualRecordingEndedAt" to recordingTimestamps.actualRecordingEndedAt,
          "requestTorchOnAt" to recordingTimestamps.requestTorchOnAt,
          "requestTorchOffAt" to recordingTimestamps.requestTorchOffAt,
          "requestBackgroundTorchOnAt" to recordingTimestamps.requestBackgroundTorchOnAt,
          "requestBackgroundTorchOffAt" to recordingTimestamps.requestBackgroundTorchOffAt,
          "actualBackgroundTorchOnAt" to recordingTimestamps.actualBackgroundTorchOnAt,
          "actualBackgroundTorchOffAt" to recordingTimestamps.actualBackgroundTorchOffAt
        )

        val video = Video(path, durationMs, size, metadata)
        callback(video)
      }
    }
  }
}

fun setTorchMode(torchMode: String, torchLevelVal: Double?, camera: Camera) {
  try {
    when (torchMode.lowercase()) {
      "on" -> {
        camera?.cameraControl?.enableTorch(true)
        Log.d("Torch", "Torch turned on. Level: ${torchLevelVal?.roundToInt()}")
      }
      "off" -> {
        camera?.cameraControl?.enableTorch(false)
      }
      else -> {
        // Handle any other modes like "auto"
        println("Invalid torch mode: $torchMode")
      }
    }
  } catch (e: Exception) {
    e.printStackTrace()
  }
}

fun CameraSession.stopRecording() {
  val recording = recording ?: throw NoRecordingInProgressError()

  recording.stop()
  this.recording = null
}

fun CameraSession.cancelRecording() {
  isRecordingCanceled = true
  stopRecording()
}

fun CameraSession.pauseRecording() {
  val recording = recording ?: throw NoRecordingInProgressError()
  recording.pause()
}

fun CameraSession.resumeRecording() {
  val recording = recording ?: throw NoRecordingInProgressError()
  recording.resume()
}
