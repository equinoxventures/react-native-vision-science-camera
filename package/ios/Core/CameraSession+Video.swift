//
//  CameraSession+Video.swift
//  VisionCamera
//
//  Created by Marc Rousavy on 11.10.23.
//  Copyright © 2023 mrousavy. All rights reserved.
//

// MARK: - RecordingTimestamps

struct RecordingTimestamps {
  var actualRecordingStartedAt: Double?
  var actualTorchOnAt: Double?
  var actualTorchOffAt: Double?
  var actualRecordingEndedAt: Double?
  var requestTorchOnAt: Double?
  var requestTorchOffAt: Double?
  var actualBackgroundTorchOnAt: Double?
  var actualBackgroundTorchOffAt: Double?
  var requestBackgroundTorchOnAt: Double?
  var requestBackgroundTorchOffAt: Double?
}

import AVFoundation
import Foundation
import UIKit

private let INSUFFICIENT_STORAGE_ERROR_CODE = -11807

extension CameraSession {
  /**
   Starts a video + audio recording with a custom Asset Writer.
   */
  func startRecording(options: RecordVideoOptions,
                      onVideoRecorded: @escaping (_ video: Video) -> Void,
                      onError: @escaping (_ error: CameraError) -> Void) {
    // Run on Camera Queue
    CameraQueues.cameraQueue.async {
      let start = DispatchTime.now()
      VisionLogger.log(level: .info, message: "Starting Video recording...")

      // Get Video Output
      guard let videoOutput = self.videoOutput else {
        if self.configuration?.video == .disabled {
          onError(.capture(.videoNotEnabled))
        } else {
          onError(.session(.cameraNotReady))
        }
        return
      }

      let enableAudio = self.configuration?.audio != .disabled

      // Callback for when the recording ends
      let onFinish = { (recordingSession: RecordingSession, status: AVAssetWriter.Status, error: Error?) in
        defer {
          // Disable Audio Session again
          if enableAudio {
            CameraQueues.audioQueue.async {
              self.deactivateAudioSession()
            }
          }
        }

        self.isRecording = false
        self.recordingSession = nil

        if self.didCancelRecording {
          VisionLogger.log(level: .info, message: "RecordingSession finished because the recording was canceled.")
          onError(.capture(.recordingCanceled))
          do {
            VisionLogger.log(level: .info, message: "Deleting temporary video file...")
            try FileManager.default.removeItem(at: recordingSession.url)
          } catch {
            self.delegate?.onError(.capture(.fileError(cause: error)))
          }
          return
        }

        VisionLogger.log(level: .info, message: "RecordingSession finished with status \(status.descriptor).")

        if let error = error as NSError? {
          VisionLogger.log(level: .error, message: "RecordingSession Error \(error.code): \(error.description)")
          // Something went wrong, we have an error
          if error.code == INSUFFICIENT_STORAGE_ERROR_CODE {
            onError(.capture(.insufficientStorage))
          } else {
            onError(.capture(.unknown(message: "An unknown recording error occured! \(error.code) \(error.description)")))
          }
        } else {
          if status == .completed {
            let metadata = [
              "actualRecordingStartedAt": self.recordingTimestamps.actualRecordingStartedAt,
              "actualTorchOnAt": self.recordingTimestamps.actualTorchOnAt,
              "actualTorchOffAt": self.recordingTimestamps.actualTorchOffAt,
              "actualRecordingEndedAt": self.recordingTimestamps.actualRecordingEndedAt,
              "requestTorchOnAt": self.recordingTimestamps.requestTorchOnAt,
              "requestTorchOffAt": self.recordingTimestamps.requestTorchOffAt,
              "requestBackgroundTorchOnAt": self.recordingTimestamps.requestBackgroundTorchOnAt,
              "requestBackgroundTorchOffAt": self.recordingTimestamps.requestBackgroundTorchOffAt,
              "actualBackgroundTorchOnAt": self.recordingTimestamps.actualBackgroundTorchOnAt,
              "actualBackgroundTorchOffAt": self.recordingTimestamps.actualBackgroundTorchOffAt,
            ]
            // Recording was successfully saved
            let video = Video(path: recordingSession.url.absoluteString,
                              duration: recordingSession.duration,
                              size: recordingSession.size ?? CGSize.zero,
                              metadata: metadata)
            onVideoRecorded(video)
          } else {
            // Recording wasn't saved and we don't have an error either.
            onError(.unknown(message: "AVAssetWriter completed with status: \(status.descriptor)"))
          }
        }
      }

      // Create temporary file
      let errorPointer = ErrorPointer(nilLiteral: ())
      let fileExtension = options.fileType.descriptor ?? "mov"
      guard let tempFilePath = RCTTempFilePath(fileExtension, errorPointer) else {
        let message = errorPointer?.pointee?.description
        onError(.capture(.createTempFileError(message: message)))
        return
      }

      VisionLogger.log(level: .info, message: "Will record to temporary file: \(tempFilePath)")
      let tempURL = URL(string: "file://\(tempFilePath)")!

      do {
        // Create RecordingSession for the temp file
        let recordingSession = try RecordingSession(url: tempURL,
                                                    fileType: options.fileType,
                                                    metadataProvider: self.metadataProvider,
                                                    completion: onFinish)

        // Init Audio + Activate Audio Session (optional)
        if enableAudio,
           let audioOutput = self.audioOutput,
           let audioInput = self.audioDeviceInput {
          VisionLogger.log(level: .info, message: "Enabling Audio for Recording...")
          // Activate Audio Session asynchronously
          CameraQueues.audioQueue.async {
            do {
              try self.activateAudioSession()
            } catch {
              self.onConfigureError(error)
            }
          }

          // Initialize audio asset writer
          let audioSettings = audioOutput.recommendedAudioSettingsForAssetWriter(writingTo: options.fileType)
          recordingSession.initializeAudioWriter(withSettings: audioSettings,
                                                 format: audioInput.device.activeFormat.formatDescription)
        }

        // Init Video
        let videoSettings = try videoOutput.recommendedVideoSettings(forOptions: options)
        recordingSession.initializeVideoWriter(withSettings: videoSettings)

        // start recording session with or without audio.
        // Use Video [AVCaptureSession] clock as a timebase - all other sessions (here; audio) have to be synced to that Clock.
        try recordingSession.start(clock: self.captureSession.clock)
        self.didCancelRecording = false
        self.recordingSession = recordingSession
        self.isRecording = true

        var backgroundDelay = DispatchTimeInterval.milliseconds(Int(self.configuration!.backgroundDelay))
        var backgroundTorchEnd = DispatchTimeInterval.milliseconds(Int(self.configuration!.backgroundDelay) + Int(self.configuration!.backgroundDuration))

        if Int(self.configuration!.backgroundDelay) > 0 {
          self.setTorchMode("off", torchLevelVal: self.configuration!.torchLevel)
        }

        let recordingStartTimestamp = NSDate().timeIntervalSince1970
        ReactLogger.log(level: .info, message: "recordingStartTimestamp:  \(recordingStartTimestamp)")
        self.recordingTimestamps.actualRecordingStartedAt = NSDate().timeIntervalSince1970

        var torchDelay = DispatchTimeInterval.milliseconds(Int(self.configuration!.torchDelay))
        var torchEnd = DispatchTimeInterval.milliseconds(Int(self.configuration!.torchDelay) + Int(self.configuration!.torchDuration))

        if let backgroundLevelValue = self.configuration!.backgroundLevel as? Double {
          if backgroundLevelValue > 0.0 && Int(self.configuration!.backgroundDelay) > 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + backgroundDelay) {
              self.recordingTimestamps.requestBackgroundTorchOnAt = NSDate().timeIntervalSince1970
              self.setBackgroundLight(self.configuration!.backgroundLevel, torchMode: "on")
              self.recordingTimestamps.actualBackgroundTorchOnAt = NSDate().timeIntervalSince1970
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + backgroundTorchEnd) {
              self.recordingTimestamps.requestBackgroundTorchOffAt = NSDate().timeIntervalSince1970
              self.setTorchMode("off", torchLevelVal: self.configuration!.torchLevel)
              self.recordingTimestamps.actualBackgroundTorchOffAt = NSDate().timeIntervalSince1970
            }
          }
        }

        if Int(self.configuration!.torchDuration) > 0 {
          DispatchQueue.main.asyncAfter(deadline: .now() + torchDelay) {
            self.recordingTimestamps.requestTorchOnAt = NSDate().timeIntervalSince1970
            self.setTorchMode("off", torchLevelVal: self.configuration!.torchLevel)
            self.setTorchMode("on", torchLevelVal: self.configuration!.torchLevel)
            self.recordingTimestamps.actualTorchOnAt = NSDate().timeIntervalSince1970
          }

          DispatchQueue.main.asyncAfter(deadline: .now() + torchEnd) {
            self.recordingTimestamps.requestTorchOffAt = NSDate().timeIntervalSince1970
            self.setTorchMode("off", torchLevelVal: self.configuration!.torchLevel)
            if let backgroundLevelValue = self.configuration!.backgroundLevel as? Double {
              if backgroundLevelValue > 0.0 && Int(self.configuration!.backgroundDelay) > 0 {
                self.setBackgroundLight(self.configuration!.backgroundLevel, torchMode: "on")
              }
            }
            self.recordingTimestamps.actualTorchOffAt = NSDate().timeIntervalSince1970
          }
        }

        let end = DispatchTime.now()
        VisionLogger.log(level: .info, message: "RecordingSesssion started in \(Double(end.uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)ms!")
      } catch let error as NSError {
        if let error = error as? CameraError {
          onError(error)
        } else {
          onError(.capture(.createRecorderError(message: "RecordingSession failed with unknown error: \(error.description)")))
        }
        return
      }
    }
  }

  /**
   Stops an active recording.
   */
  func stopRecording(promise: Promise) {
    CameraQueues.cameraQueue.async {
      withPromise(promise) {
        guard let recordingSession = self.recordingSession else {
          throw CameraError.capture(.noRecordingInProgress)
        }
        // Use Video [AVCaptureSession] clock as a timebase - all other sessions (here; audio) have to be synced to that Clock.
        recordingSession.stop(clock: self.captureSession.clock)
        let recordingStopTimestamp = NSDate().timeIntervalSince1970
        ReactLogger.log(level: .info, message: "recordingStopTimestamp:  \(recordingStopTimestamp)")
        self.recordingTimestamps.actualRecordingEndedAt = NSDate().timeIntervalSince1970
        // There might be late frames, so maybe we need to still provide more Frames to the RecordingSession. Let's keep isRecording true for now.
        return nil
      }
    }
  }

  /**
   Cancels an active recording.
   */
  func cancelRecording(promise: Promise) {
    didCancelRecording = true
    stopRecording(promise: promise)
  }

  /**
   Pauses an active recording.
   */
  func pauseRecording(promise: Promise) {
    CameraQueues.cameraQueue.async {
      withPromise(promise) {
        guard self.recordingSession != nil else {
          // there's no active recording!
          throw CameraError.capture(.noRecordingInProgress)
        }
        self.isRecording = false
        return nil
      }
    }
  }

  /**
   Resumes an active, but paused recording.
   */
  func resumeRecording(promise: Promise) {
    CameraQueues.cameraQueue.async {
      withPromise(promise) {
        guard self.recordingSession != nil else {
          // there's no active recording!
          throw CameraError.capture(.noRecordingInProgress)
        }
        self.isRecording = true
        return nil
      }
    }
  }
}
