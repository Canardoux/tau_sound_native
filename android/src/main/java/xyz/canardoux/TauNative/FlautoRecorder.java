package xyz.canardoux.TauNative;
/*
 * Copyright 2021 Canardoux.
 *
 * This file is part of the τ Sound project.
 *
 * τ Sound is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Public License version 3 (GPL3.0), 
 * as published by the Free Software Foundation.
 *
 * τ Sound is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * This Source Code Form is subject to the terms of the GNU Public
 * License, v. 3.0. If a copy of the GPL was not distributed with this
 * file, You can obtain one at https://www.gnu.org/licenses/.
 */

import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import xyz.canardoux.TauNative.Flauto.*;


//-----------------------------------------------------------------------------------------------------------------------------------------------

public class FlautoRecorder extends FlautoSession
{
	static boolean _isAndroidEncoderSupported[] = {
		true, // DEFAULT
		true, //Build.VERSION.SDK_INT >= 23,  // aacADTS
		false, // opusOGG // ( Build.VERSION.SDK_INT < 29 )
		false, // opusCAF
		false, // MP3
		false, // vorbisOGG // ( Build.VERSION.SDK_INT < 29 )
		Build.VERSION.SDK_INT >= 21, // pcm16
		Build.VERSION.SDK_INT >= 21, // pcm16WAV
		false, // pcm16AIFF
		false, // pcm16CAF
		false, // flac
		Build.VERSION.SDK_INT >= 23,  // aacMP4
		Build.VERSION.SDK_INT >= 23,  // amrNB
		Build.VERSION.SDK_INT >= 23,  // amrWB

		false, // pcm8
		false, // pcmFloat32
		false, // pcmWebM
		true, // opusWebM
		false, // vorbisWebM
	};


	static boolean _isAudioRecorder[] = {
		false, // DEFAULT
		false, // aacADTS
		false, // opusOGG
		false, // opusCAF
		false , // MP3
		false, // vorbisOGG
		true, // pcm16
		true, // pcm16WAV
		false, // pcm16AIFF
		false, // pcm16CAF
		false, // flac
		false, // aacMP4
		false, // amrNB
		false, // amrWB
		false, // pcm8
		false, // pcmFloat32
		false, // pcmWebM
		false, // opusWebM
		false, // vorbisWebM
	};

	int[] tabAudioSource =
	{
		MediaRecorder.AudioSource.DEFAULT,
		MediaRecorder.AudioSource.MIC,
		MediaRecorder.AudioSource.VOICE_DOWNLINK,
		MediaRecorder.AudioSource.CAMCORDER,
		MediaRecorder.AudioSource.REMOTE_SUBMIX,
		MediaRecorder.AudioSource.UNPROCESSED,
		MediaRecorder.AudioSource.VOICE_CALL,
		MediaRecorder.AudioSource.VOICE_COMMUNICATION,
		10, //MediaRecorder.AudioSource.VOICE_PERFORMANCE,,
		MediaRecorder.AudioSource.VOICE_RECOGNITION,
		MediaRecorder.AudioSource.VOICE_UPLINK,
		MediaRecorder.AudioSource.DEFAULT, // bluetoothHFP,
		MediaRecorder.AudioSource.DEFAULT, // headsetMic,
		MediaRecorder.AudioSource.DEFAULT, // lineIn

	};




	final static int CODEC_OPUS   = 2;
	final static int CODEC_VORBIS = 5;
	static final String ERR_UNKNOWN           = "ERR_UNKNOWN";


	static final String ERR_RECORDER_IS_NULL      = "ERR_RECORDER_IS_NULL";
	static final String ERR_RECORDER_IS_RECORDING = "ERR_RECORDER_IS_RECORDING";


	final static String             TAG                = "FlautoRecorder";
	FlautoRecorderInterface recorder;
	public Handler            recordHandler   ;
	FlautoRecorderCallback m_callBack ;
	private final ExecutorService taskScheduler = Executors.newSingleThreadExecutor ();
	long mPauseTime = 0;
	long mStartPauseTime = -1;
	final private Handler          mainHandler = new Handler (Looper.getMainLooper ());
	String m_path = null;

	long subsDurationMillis = 0;

	private      Runnable      recorderTicker;
	t_RECORDER_STATE status = t_RECORDER_STATE.RECORDER_IS_STOPPED;

	public FlautoRecorder ( FlautoRecorderCallback callback )
	{
		m_callBack = callback;
	}


	public boolean openRecorder (t_AUDIO_FOCUS focus, t_SESSION_CATEGORY category, t_SESSION_MODE sessionMode, int audioFlags, t_AUDIO_DEVICE audioDevice)
	{
		boolean r = setAudioFocus(focus, category, sessionMode, audioFlags, audioDevice);
		m_callBack.openRecorderCompleted(r);
		return r;
	}



	public void closeRecorder ( )
	{
		stop();
		if (hasFocus)
			abandonFocus();
		releaseSession();
		status = t_RECORDER_STATE.RECORDER_IS_STOPPED;
		m_callBack.closeRecorderCompleted(true);
	}



	public boolean isEncoderSupported ( t_CODEC codec)
	{
		boolean b      = _isAndroidEncoderSupported[ codec.ordinal() ];
		//if ( Build.VERSION.SDK_INT < 29 )
		{
			//if ( ( _codec == CODEC_OPUS ) || ( _codec == CODEC_VORBIS ) )
			{
				//b = false;
			}
		}
		return b;
	}


	public t_RECORDER_STATE getRecorderState()
	{
		return status;
	}



	public boolean deleteRecord(String radical)
	{
		String path = Flauto.temporayFile(radical);
		File fdelete = new File(path);
		if (fdelete.exists())
		{
			if (fdelete.delete())
			{
				return true;
			} else
			{
				return false;
			}
		} else
			return false;

	}
	void cancelTimer()
	{
		if (recordHandler != null)
			recordHandler.removeCallbacksAndMessages(null);
		recordHandler = null;

	}

	void setTimer(long duration)
	{
		cancelTimer();
		subsDurationMillis = duration;
		if (recorder == null || duration == 0)
			return;
		final long systemTime = SystemClock.elapsedRealtime();
		recordHandler      = new Handler ();
		recorderTicker = ( () ->
		{
			mainHandler.post(new Runnable()
			{
				@Override
				public void run() {

					long time = SystemClock.elapsedRealtime() - systemTime - mPauseTime;
					// logDebug (  "elapsedTime: " + SystemClock.elapsedRealtime());
					// logDebug( "time: " + time);

					// DateFormat format = new SimpleDateFormat("mm:ss:SS", Locale.US);
					// String displayTime = format.format(time);
					// model.setRecordTime(time);
					try {
						double db = 0.0;
						if (recorder != null) {
							double maxAmplitude = recorder.getMaxAmplitude();

							// Calculate db based on the following article.
							// https://stackoverflow.com/questions/10655703/what-does-androids-getmaxamplitude-function-for-the-mediarecorder-actually-gi
							//
							double ref_pressure = 51805.5336;
							double p = maxAmplitude / ref_pressure;
							double p0 = 0.0002;

							db = 20.0 * Math.log10(p / p0);

							// if the microphone is off we get 0 for the amplitude which causes
							// db to be infinite.
							if (Double.isInfinite(db)) {
								db = 0.0;
							}

						}


						m_callBack.updateRecorderProgressDbPeakLevel(db, time);
						if (recordHandler != null)
							recordHandler.postDelayed(recorderTicker, subsDurationMillis);
					} catch (Exception e) {
						logDebug (  " Exception: " + e.toString());
					}
				}
			});


		} );
		recordHandler.post ( recorderTicker );

	}

	public boolean startRecorder
	(
		t_CODEC 			codec		    ,
		Integer                         sampleRate          ,
		Integer                         numChannels         ,
		Integer                         bitRate             ,
		String                     	path                ,
		t_AUDIO_SOURCE                  _audioSource        ,
		boolean 			toStream
	)
	{
		int  audioSource         = tabAudioSource[_audioSource.ordinal()];
		//audioSource =MediaRecorder.AudioSource.MIC; // Just for test
		mPauseTime = 0;
		mStartPauseTime = -1;
		stop(); // To start a new clean record
		m_path = null;
		if (_isAudioRecorder[codec.ordinal()])
		{
			//assert(toStream);
			//assert(path == null);
			if (numChannels != 1)
			{
				logError (  "The number of channels supported is actually only 1" );
				return false;
			}
			recorder = new FlautoRecorderEngine();
		} else
		{
			//assert(!toStream);
			path = Flauto.getPath(path);
			m_path = path;
			recorder = new FlautoRecorderMedia(m_callBack);
		}
		try
		{
			recorder._startRecorder( numChannels, sampleRate, bitRate, codec, path, audioSource, this );
			if (subsDurationMillis > 0)
				setTimer(subsDurationMillis);

		} catch ( Exception e )
		{
			logError (  "Error starting recorder" + e.getMessage() );
			return false;
		}
		// Remove all pending runnables, this is just for safety (should never happen)
		//recordHandler.removeCallbacksAndMessages ( null );
		status = t_RECORDER_STATE.RECORDER_IS_RECORDING;
		m_callBack.startRecorderCompleted(true);
		return true;
	}

	public void recordingData(byte[] data)
	{
		m_callBack.recordingData(data);
	}

	void stop()
	{
		try {
				cancelTimer();
				if (recorder != null)
				recorder._stopRecorder();
		} catch (Exception e)
		{

		}
		recorder = null;
		status = t_RECORDER_STATE.RECORDER_IS_STOPPED;

	}

	public void stopRecorder ( )
	{
		stop();
		m_callBack.stopRecorderCompleted(true, m_path);
	}

	public void pauseRecorder()
	{
		cancelTimer();
		recorder.pauseRecorder( );
		mStartPauseTime = SystemClock.elapsedRealtime ();
		status = t_RECORDER_STATE.RECORDER_IS_PAUSED;
		m_callBack.pauseRecorderCompleted(true);
	}

	public void resumeRecorder( )
	{
		setTimer(subsDurationMillis);
		recorder.resumeRecorder();
		if (mStartPauseTime >= 0)
			mPauseTime += SystemClock.elapsedRealtime () - mStartPauseTime;
		mStartPauseTime = -1;
		status = t_RECORDER_STATE.RECORDER_IS_RECORDING;
		m_callBack.resumeRecorderCompleted(true);
	}

	public void setSubscriptionDuration (int duration)
	{
		this.subsDurationMillis = duration;
		if (recorder != null)
			setTimer(subsDurationMillis);
	}

	public String temporayFile(String radical)
	{
		return Flauto.temporayFile(radical);
	}



	void logDebug (String msg)
	{
		m_callBack.log ( t_LOG_LEVEL.DBG , msg);
	}


	void logError (String msg)
	{
		m_callBack.log ( t_LOG_LEVEL.ERROR , msg);
	}

}
