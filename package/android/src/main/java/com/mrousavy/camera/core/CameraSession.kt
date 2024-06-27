package com.mrousavy.camera.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPoint
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.facebook.react.bridge.UiThreadUtil
import com.google.mlkit.vision.barcode.common.Barcode
import com.mrousavy.camera.core.extensions.await
import com.mrousavy.camera.core.extensions.byId
import com.mrousavy.camera.core.extensions.forSize
import com.mrousavy.camera.core.extensions.getCameraError
import com.mrousavy.camera.core.extensions.id
import com.mrousavy.camera.core.extensions.isSDR
import com.mrousavy.camera.core.extensions.takePicture
import com.mrousavy.camera.core.extensions.toCameraError
import com.mrousavy.camera.core.extensions.withExtension
import com.mrousavy.camera.core.types.CameraDeviceFormat
import com.mrousavy.camera.core.types.Flash
import com.mrousavy.camera.core.types.Orientation
import com.mrousavy.camera.core.types.RecordVideoOptions
import com.mrousavy.camera.core.types.ShutterType
import com.mrousavy.camera.core.types.Torch
import com.mrousavy.camera.core.types.Video
import com.mrousavy.camera.core.types.VideoStabilizationMode
import com.mrousavy.camera.core.utils.FileUtils
import com.mrousavy.camera.core.utils.runOnUiThread
import com.mrousavy.camera.frameprocessors.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

data class RecordingTimestamps(
    var actualRecordingStartedAt: Long? = null,
    var actualTorchOnAt: Long? = null,
    var actualTorchOffAt: Long? = null,
    var actualRecordingEndedAt: Long? = null,
    var requestTorchOnAt: Long? = null,
    var requestTorchOffAt: Long? = null,
    var actualBackgroundTorchOnAt: Long? = null,
    var actualBackgroundTorchOffAt: Long? = null,
    var requestBackgroundTorchOnAt: Long? = null,
    var requestBackgroundTorchOffAt: Long? = null
)

class CameraSession(internal val context: Context, internal val callback: Callback) :
  Closeable,
  LifecycleOwner,
  OrientationManager.Callback {
  companion object {
    internal const val TAG = "CameraSession"
  }

  // Camera Configuration
  internal var configuration: CameraConfiguration? = null
  internal val cameraProvider = ProcessCameraProvider.getInstance(context)
  internal var camera: Camera? = null

  // Camera Outputs
  internal var previewOutput: Preview? = null
  internal var photoOutput: ImageCapture? = null
  internal var videoOutput: VideoCapture<Recorder>? = null
  internal var frameProcessorOutput: ImageAnalysis? = null
  internal var codeScannerOutput: ImageAnalysis? = null
  internal var currentUseCases: List<UseCase> = emptyList()

  // Camera Outputs State
  internal val metadataProvider = MetadataProvider(context)
  internal val orientationManager = OrientationManager(context, this)
  internal var recorderOutput: Recorder? = null

  // Camera State
  internal val mutex = Mutex()
  internal var isDestroyed = false
  internal val lifecycleRegistry = LifecycleRegistry(this)
  internal var recording: Recording? = null
  internal var isRecordingCanceled = false
  internal val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  // Threading
  internal val mainExecutor = ContextCompat.getMainExecutor(context)

  // Orientation
  val outputOrientation: Orientation
    get() = orientationManager.outputOrientation

  private var recordingTimestamps = RecordingTimestamps()
  init {
    lifecycleRegistry.currentState = Lifecycle.State.CREATED
    lifecycle.addObserver(object : LifecycleEventObserver {
      override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        Log.i(TAG, "Camera Lifecycle changed to ${event.targetState}!")
      }
    })
  }

  override fun close() {
    Log.i(TAG, "Closing CameraSession...")
    isDestroyed = true
    orientationManager.stopOrientationUpdates()
    runOnUiThread {
      lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
  }

  override fun getLifecycle(): Lifecycle = lifecycleRegistry

  /**
   * Configures the [CameraSession] with new values in one batch.
   * This must be called from the Main UI Thread.
   */
  @MainThread
  suspend fun configure(lambda: (configuration: CameraConfiguration) -> Unit) {
    if (!UiThreadUtil.isOnUiThread()) {
      throw Error("configure { ... } must be called from the Main UI Thread!")
    }
    Log.i(TAG, "configure { ... }: Waiting for lock...")

    val provider = cameraProvider.await(mainExecutor)

    mutex.withLock {
      // Let caller configure a new configuration for the Camera.
      val config = CameraConfiguration.copyOf(this.configuration)
      try {
        lambda(config)
      } catch (e: CameraConfiguration.AbortThrow) {
        // config changes have been aborted.
        return
      }
      val diff = CameraConfiguration.difference(this.configuration, config)
      this.configuration = config

      if (!diff.hasChanges) {
        Log.i(TAG, "Nothing changed, aborting configure { ... }")
        return@withLock
      }

      if (isDestroyed) {
        Log.i(TAG, "CameraSession is already destroyed. Skipping configure { ... }")
        return@withLock
      }

      Log.i(TAG, "configure { ... }: Updating CameraSession Configuration... $diff")

      try {
        // Build up session or update any props
        if (diff.outputsChanged) {
          // 1. outputs changed, re-create them
          configureOutputs(config)
          // 1.1. whenever the outputs changed, we need to update their orientation as well
          configureOrientation()
        }
        if (diff.deviceChanged) {
          // 2. input or outputs changed, or the session was destroyed from outside, rebind the session
          configureCamera(provider, config)
        }
        if (diff.sidePropsChanged) {
          // 3. side props such as zoom, exposure or torch changed.
          configureSideProps(config)
        }
        if (diff.isActiveChanged) {
          // 4. start or stop the session
          configureIsActive(config)
        }
        if (diff.orientationChanged) {
          // 5. update the target orientation mode
          orientationManager.setTargetOutputOrientation(config.outputOrientation)
        }
        if (diff.locationChanged) {
          // 6. start or stop location update streaming
          metadataProvider.enableLocationUpdates(config.enableLocation)
        }

        Log.i(
          TAG,
          "configure { ... }: Completed CameraSession Configuration! (State: ${lifecycle.currentState})"
        )
      } catch (error: Throwable) {
        Log.e(TAG, "Failed to configure CameraSession! Error: ${error.message}, Config-Diff: $diff", error)
        callback.onError(error)
      }
    }
  }

  internal fun checkCameraPermission() {
    val status = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
    if (status != PackageManager.PERMISSION_GRANTED) throw CameraPermissionError()
  }
  internal fun checkMicrophonePermission() {
    val status = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
    if (status != PackageManager.PERMISSION_GRANTED) throw MicrophonePermissionError()
  }

  override fun onOutputOrientationChanged(outputOrientation: Orientation) {
    Log.i(TAG, "Output orientation changed! $outputOrientation")
    configureOrientation()
    callback.onOutputOrientationChanged(outputOrientation)
  }

  override fun onPreviewOrientationChanged(previewOrientation: Orientation) {
    Log.i(TAG, "Preview orientation changed! $previewOrientation")
    configureOrientation()
    callback.onPreviewOrientationChanged(previewOrientation)
  }

  private fun configureOrientation() {
    // Preview Orientation
    orientationManager.previewOrientation.toSurfaceRotation().let { previewRotation ->
      previewOutput?.targetRotation = previewRotation
      codeScannerOutput?.targetRotation = previewRotation
    }
    // Outputs Orientation
    orientationManager.outputOrientation.toSurfaceRotation().let { outputRotation ->
      photoOutput?.targetRotation = outputRotation
      videoOutput?.targetRotation = outputRotation
    }

     // Frame Processor output will not receive a target rotation, user is responsible for rotating himself

    if (configuration.enableLowLightBoost) {
      if (isStreamingHDR) {
        // extensions don't work if a camera stream is running at 10-bit HDR.
        throw LowLightBoostNotSupportedWithHdr()
      }
      if (enableHdrExtension) {
        // low-light boost does not work when another HDR extension is already applied
        throw LowLightBoostNotSupportedWithHdr()
      }
      // Load night mode Vendor extension (only applies to image capture)
      cameraSelector = cameraSelector.withExtension(context, provider, needsImageAnalysis, ExtensionMode.NIGHT, "NIGHT")
    }

    // Unbind all currently bound use-cases before rebinding
    if (currentUseCases.isNotEmpty()) {
      Log.i(TAG, "Unbinding ${currentUseCases.size} use-cases for Camera #${camera?.cameraInfo?.id}...")
      provider.unbind(*currentUseCases.toTypedArray())
    }

    // Bind it all together (must be on UI Thread)
    Log.i(TAG, "Binding ${useCases.size} use-cases...")
    camera = provider.bindToLifecycle(this, cameraSelector, *useCases.toTypedArray())

    // Update currentUseCases for next unbind
    currentUseCases = useCases

    // Listen to Camera events
    var lastState = CameraState.Type.OPENING
    camera!!.cameraInfo.cameraState.observe(this) { state ->
      Log.i(TAG, "Camera State: ${state.type} (has error: ${state.error != null})")

      if (state.type == CameraState.Type.OPEN && state.type != lastState) {
        // Camera has now been initialized!
        callback.onInitialized()
        lastState = state.type
      }

      val error = state.error
      if (error != null) {
        // A Camera error occurred!
        callback.onError(error.toCameraError())
      }
    }
    Log.i(TAG, "Successfully bound Camera #${configuration.cameraId}!")
  }

  private fun configureSideProps(config: CameraConfiguration) {
    val camera = camera ?: throw CameraNotReadyError()

    // Zoom
    val currentZoom = camera.cameraInfo.zoomState.value?.zoomRatio
    if (currentZoom != config.zoom) {
      camera.cameraControl.setZoomRatio(config.zoom)
    }

    // Torch
    val currentTorch = camera.cameraInfo.torchState.value == TorchState.ON
    val newTorch = config.torch == Torch.ON
    if (currentTorch != newTorch) {
      if (newTorch && !camera.cameraInfo.hasFlashUnit()) {
        throw FlashUnavailableError()
      }
      camera.cameraControl.enableTorch(newTorch)
    }

    // Exposure
    val currentExposureCompensation = camera.cameraInfo.exposureState.exposureCompensationIndex
    val exposureCompensation = config.exposure?.roundToInt() ?: 0
    if (currentExposureCompensation != exposureCompensation) {
      camera.cameraControl.setExposureCompensationIndex(exposureCompensation)
    }
  }

  private fun configureIsActive(config: CameraConfiguration) {
    if (config.isActive) {
      lifecycleRegistry.currentState = Lifecycle.State.STARTED
      lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    } else {
      lifecycleRegistry.currentState = Lifecycle.State.STARTED
      lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }
  }

  suspend fun takePhoto(flash: Flash, enableShutterSound: Boolean, outputOrientation: Orientation): Photo {
    val camera = camera ?: throw CameraNotReadyError()
    val photoOutput = photoOutput ?: throw PhotoNotEnabledError()

    if (flash != Flash.OFF && !camera.cameraInfo.hasFlashUnit()) {
      throw FlashUnavailableError()
    }

    photoOutput.flashMode = flash.toFlashMode()
    photoOutput.targetRotation = outputOrientation.toSurfaceRotation()
    val enableShutterSoundActual = getEnableShutterSoundActual(enableShutterSound)

    val photoFile = photoOutput.takePicture(context, enableShutterSoundActual, metadataProvider, callback, CameraQueues.cameraExecutor)
    val isMirrored = photoFile.metadata.isReversedHorizontal

    val bitmapOptions = BitmapFactory.Options().also {
      it.inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(photoFile.uri.path, bitmapOptions)
    val width = bitmapOptions.outWidth
    val height = bitmapOptions.outHeight

    return Photo(photoFile.uri.path, width, height, outputOrientation, isMirrored)
  }

  private fun getEnableShutterSoundActual(enable: Boolean): Boolean {
    if (enable && audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
      Log.i(TAG, "Ringer mode is silent (${audioManager.ringerMode}), disabling shutter sound...")
      return false
    }

    return enable
  }

  @RequiresApi(Build.VERSION_CODES.O)
  @OptIn(ExperimentalPersistentRecording::class)
  @SuppressLint("MissingPermission", "RestrictedApi")
  fun startRecording(
    enableAudio: Boolean,
    options: RecordVideoOptions,
    callback: (video: Video) -> Unit,
    onError: (error: CameraError) -> Unit
  ) {
    if (camera == null) throw CameraNotReadyError()
    if (recording != null) throw RecordingInProgressError()
    val videoOutput = videoOutput ?: throw VideoNotEnabledError()

    val file = FileUtils.createTempFile(context, options.fileType.toExtension())
    val outputOptions = FileOutputOptions.Builder(file).also { outputOptions ->
      metadataProvider.location?.let { location ->
        Log.i(TAG, "Setting Video Location to ${location.latitude}, ${location.longitude}...")
        outputOptions.setLocation(location)
      }
    }.build()
    var pendingRecording = videoOutput.output.prepareRecording(context, outputOptions)
    if (enableAudio) {
      checkMicrophonePermission()
      pendingRecording = pendingRecording.withAudioEnabled()
    }
    pendingRecording = pendingRecording.asPersistentRecording()

    val size = videoOutput.attachedSurfaceResolution ?: Size(0, 0)
    isRecordingCanceled = false
    recording = pendingRecording.start(CameraQueues.cameraExecutor) { event ->
      when (event) {
        is VideoRecordEvent.Start -> {
          Log.i(TAG, "Recording started!")

          if (configuration?.backgroundDelay?.toInt()!! > 0) {
            setTorchMode("off", torchLevelVal = configuration?.torchLevel)
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
                setTorchMode("on", torchLevelVal = configuration?.torchLevel)
                recordingTimestamps.actualTorchOnAt = Instant.now().epochSecond
              }
            }
            CoroutineScope(Dispatchers.Main).launch {
              if (torchEnd != null) {
                delay(torchEnd)
                recordingTimestamps.requestTorchOffAt = Instant.now().epochSecond
                setTorchMode("off", torchLevelVal = configuration?.torchLevel)
                recordingTimestamps.actualTorchOffAt = Instant.now().epochSecond
              }
            }
          }
        }

        is VideoRecordEvent.Resume -> Log.i(TAG, "Recording resumed!")

        is VideoRecordEvent.Pause -> Log.i(TAG, "Recording paused!")

        is VideoRecordEvent.Status -> Log.i(TAG, "Status update! Recorded ${event.recordingStats.numBytesRecorded} bytes.")

        is VideoRecordEvent.Finalize -> {
          if (isRecordingCanceled) {
            Log.i(TAG, "Recording was canceled, deleting file..")
            onError(RecordingCanceledError())
            try {
              file.delete()
            } catch (e: Throwable) {
              this.callback.onError(FileIOError(e))
            }
            return@start
          }

          Log.i(TAG, "Recording stopped!")
          val error = event.getCameraError()
          if (error != null) {
            if (error.wasVideoRecorded) {
              Log.e(TAG, "Video Recorder encountered an error, but the video was recorded anyways.", error)
            } else {
              Log.e(TAG, "Video Recorder encountered a fatal error!", error)
              onError(error)
              return@start
            }
          }
          val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
          Log.i(TAG, "Successfully completed video recording! Captured ${durationMs.toDouble() / 1_000.0} seconds.")
          val path = event.outputResults.outputUri.path ?: throw UnknownRecorderError(false, null)

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

  private fun setTorchMode(torchMode: String, torchLevelVal: Double?) {
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

  fun stopRecording() {
    val recording = recording ?: throw NoRecordingInProgressError()
    setTorchMode("off", 1.0)
    recording.stop()
    this.recording = null
  }

  fun cancelRecording() {
    isRecordingCanceled = true
    stopRecording()
  }

  fun pauseRecording() {
    val recording = recording ?: throw NoRecordingInProgressError()
    recording.pause()
  }

  fun resumeRecording() {
    val recording = recording ?: throw NoRecordingInProgressError()
    recording.resume()
  }

  @SuppressLint("RestrictedApi")
  suspend fun focus(meteringPoint: MeteringPoint) {
    val camera = camera ?: throw CameraNotReadyError()

    val action = FocusMeteringAction.Builder(meteringPoint).build()
    if (!camera.cameraInfo.isFocusMeteringSupported(action)) {
      throw FocusNotSupportedError()
    }

    try {
      Log.i(TAG, "Focusing to ${action.meteringPointsAf.joinToString { "(${it.x}, ${it.y})" }}...")
      val future = camera.cameraControl.startFocusAndMetering(action)
      val result = future.await(CameraQueues.cameraExecutor)
      if (result.isFocusSuccessful) {
        Log.i(TAG, "Focused successfully!")
      } else {
        Log.i(TAG, "Focus failed.")
      }
    } catch (e: CameraControl.OperationCanceledException) {
      throw FocusCanceledError()
    }
  }

  interface Callback {
    fun onError(error: Throwable)
    fun onFrame(frame: Frame)
    fun onInitialized()
    fun onStarted()
    fun onStopped()
    fun onShutter(type: ShutterType)
    fun onOutputOrientationChanged(outputOrientation: Orientation)
    fun onPreviewOrientationChanged(previewOrientation: Orientation)
    fun onCodeScanned(codes: List<Barcode>, scannerFrame: CodeScannerFrame)
  }
}
