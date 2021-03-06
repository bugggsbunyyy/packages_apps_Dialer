/*
 * Copyright (C) 2014 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.services.callrecorder;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.provider.Settings;
import android.util.Log;

import com.android.services.callrecorder.common.CallRecording;
import com.android.services.callrecorder.common.ICallRecorderService;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.android.dialer.R;

public class CallRecorderService extends Service {
    private static final String TAG = "CallRecorderService";
    private static final boolean DBG = false;

    private static enum RecorderState {
        IDLE,
        RECORDING
    };

    private MediaRecorder mMediaRecorder = null;
    private RecorderState mState = RecorderState.IDLE;
    private CallRecording mCurrentRecording = null;

    private static final String AUDIO_SOURCE_PROPERTY = "persist.call_recording.src";

    private SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyMMdd_HHmmssSSS");

    private int mDefaultEncoder;

    private final ICallRecorderService.Stub mBinder = new ICallRecorderService.Stub() {
        @Override
        public CallRecording stopRecording() {
            if (getState() == RecorderState.RECORDING) {
                stopRecordingInternal();
                return mCurrentRecording;
            }
            return null;
        }

        @Override
        public boolean startRecording(String phoneNumber, long creationTime)
                throws RemoteException {
            String fileName = generateFilename(phoneNumber);
            mCurrentRecording = new CallRecording(phoneNumber, creationTime,
                    fileName, System.currentTimeMillis());
            return startRecordingInternal(mCurrentRecording.getFile());

        }

        @Override
        public boolean isRecording() throws RemoteException {
            return getState() == RecorderState.RECORDING;
        }

        @Override
        public CallRecording getActiveRecording() throws RemoteException {
            return mCurrentRecording;
        }
    };

    @Override
    public void onCreate() {
        if (DBG) Log.d(TAG, "Creating CallRecorderService");
        mDefaultEncoder = getResources().getInteger(R.integer.call_recording_audio_encoder);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private int getAudioSource() {
        int defaultValue = getResources().getInteger(R.integer.call_recording_audio_source);
        return SystemProperties.getInt(AUDIO_SOURCE_PROPERTY, defaultValue);
    }

    private int getAudioFormat() {
        int formatValue =  Settings.System.getInt(
                getContentResolver(), Settings.System.CALL_RECORDING_FORMAT, mDefaultEncoder);
        if (formatValue == 0){
            return MediaRecorder.OutputFormat.AMR_WB;
        } else {
            return MediaRecorder.OutputFormat.MPEG_4;
        }
    }

    private int getAudioEncoder() {
        int formatValue =  Settings.System.getInt(
                getContentResolver(), Settings.System.CALL_RECORDING_FORMAT, mDefaultEncoder);
        if (formatValue == 0){
            return MediaRecorder.AudioEncoder.AMR_WB;
        } else {
            return MediaRecorder.AudioEncoder.HE_AAC;
        }
    }

    private synchronized boolean startRecordingInternal(File file) {
        if (mMediaRecorder != null) {
            if (DBG) {
                Log.d(TAG, "Start called with recording in progress, stopping  current recording");
            }
            stopRecordingInternal();
        }

        if (DBG) Log.d(TAG, "Starting recording");

        mMediaRecorder = new MediaRecorder();
        try {
            int audioSource = getAudioSource();
            if (DBG) Log.d(TAG, "Creating media recorder with audio source " + audioSource);
            mMediaRecorder.setAudioSource(audioSource);
            mMediaRecorder.setOutputFormat(getAudioFormat());
            mMediaRecorder.setAudioEncoder(getAudioEncoder());
        } catch (IllegalStateException e) {
            Log.w(TAG, "Error initializing media recorder", e);
            return false;
        }

        file.getParentFile().mkdirs();
        String outputPath = file.getAbsolutePath();
        if (DBG) Log.d(TAG, "Writing output to file " + outputPath);

        try {
            mMediaRecorder.setOutputFile(outputPath);
            mMediaRecorder.prepare();
            mMediaRecorder.start();
            mState = RecorderState.RECORDING;
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Could not start recording for file " + outputPath, e);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Could not start recording for file " + outputPath, e);
        } catch (RuntimeException e) {
            // only catch exceptions thrown by the MediaRecorder JNI code
            if (e.getMessage().indexOf("start failed") >= 0) {
                Log.w(TAG, "Could not start recording for file " + outputPath, e);
            } else {
                throw e;
            }
        }

        mMediaRecorder.reset();
        mMediaRecorder.release();
        mMediaRecorder = null;

        return false;
    }

    private synchronized void stopRecordingInternal() {
        if (DBG) Log.d(TAG, "Stopping current recording");
        if (mMediaRecorder != null) {
            try {
                if (getState() == RecorderState.RECORDING) {
                    mMediaRecorder.stop();
                    mMediaRecorder.reset();
                    mMediaRecorder.release();
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Exception closing media recorder", e);
            }
            mMediaRecorder = null;
            mState = RecorderState.IDLE;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DBG) Log.d(TAG, "Destroying CallRecorderService");
    }

    private synchronized RecorderState getState() {
        return mState;
    }

    private String generateFilename(String number) {
        String timestamp = DATE_FORMAT.format(new Date());
        if (TextUtils.isEmpty(number)) {
            number = "unknown";
        }

        int audioFormat = getAudioFormat();
        if (audioFormat == MediaRecorder.OutputFormat.AMR_WB){
            return number + "_" + timestamp + ".amr";
        } else {
            return number + "_" + timestamp + ".m4a ";
        }
    }

    public static boolean isEnabled(Context context) {
        return context.getResources().getBoolean(R.bool.call_recording_enabled);
    }
}
