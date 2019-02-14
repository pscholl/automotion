package de.uni_freiburg.bgrecorder;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import de.uni_freiburg.ffmpeg.FFMpegProcess;

/** On start, and if not already running, this Service spawns an ffmpeg instance to
 * record all inertial motion sensor in the background.
 *
 * Created by phil on 07.08.18.
 */

public class RecorderService extends Service {
    private static final double RATE = 50.;
    private String VERSION = "1.0";
    private FFMpegProcess mFFmpeg;
    private int NOTIFICATION_ID = 0x007;

    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_STRT = "ACTION_STRT";
    private LinkedList<CopyListener> mSensorListeners = new LinkedList<>();

    /* for start synchronization */
    private Long mStartTimeNS = -1l;
    private CountDownLatch mSyncLatch = null;
    private MainReceiver mMainReceiver = null;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static String getCurrentDateAsIso() {
        // see https://stackoverflow.com/questions/3914404/how-to-get-current-moment-in-iso-8601-format
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(new Date());
    }

    public static String getDefaultOutputPath(Context context) {
        File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        return new File(path, getDefaultFileName(context)).toString();
    }

    public static String getDefaultFileName(Context context) {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ");
        df.setTimeZone(tz);
        String aid = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        return df.format(new Date()) + "_" + aid + ".mkv";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        /** We continiously start the service to receive Battery Events, which can not be done
         * differently under Android 8.0 since no Battery PLUG/UNPLUG events are sent to
         * broadcast receivers anymore. */
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setPriority(Notification.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        if (mMainReceiver == null) {
            mMainReceiver = new MainReceiver();

            IntentFilter power = new IntentFilter();
            power.addAction(Intent.ACTION_POWER_CONNECTED);
            power.addAction(Intent.ACTION_POWER_DISCONNECTED);

            registerReceiver(mMainReceiver, power);
        }

        /**
         * start the recording process if there is no ffmpeg instance yet, and no stop intent
         * was sent.
         */
        if (ACTION_STOP.equals(intent.getAction()))
            stopRecording();

        else if (mFFmpeg == null)
            try {
                startRecording();
            } catch (Exception e) {
                e.printStackTrace();
            }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRecording();
    }

    public void startRecording() throws Exception {
        String platform = Build.BOARD + " " + Build.DEVICE + " " + Build.VERSION.SDK_INT,
                output = getDefaultOutputPath(getApplicationContext()),
                android_id = Settings.Secure.getString(
                        getContentResolver(), Settings.Secure.ANDROID_ID),
                format = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "f32le" : "f32be";

        /**
         *  Get the sensors from the system and check if they are wakeups
         */
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor[] sensors = new Sensor[]{
                /*    sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true),
                    sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD, true),
                    sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true),
                */
                sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR, true)
        };
        CopyListener[] listeners = new CopyListener[sensors.length];

        for (Sensor s : sensors)
            if (s == null || !s.isWakeUpSensor())
                throw new Error("wakeup sensor not supported!");


        /**
         * build and start the ffmpeg process, which transcodes into a matroska file.
         */
        FFMpegProcess.Builder b = new FFMpegProcess.Builder(getApplicationContext())
                .setOutput(output, "matroska")
                .setCodec("a", "wavpack")
                .addOutputArgument("-shortest")
                .setTag("recorder", "automotion " + VERSION)
                .setTag("android_id", android_id)
                .setTag("platform", platform)
                .setTag("fingerprint", Build.FINGERPRINT)
                .setTag("beginning", getCurrentDateAsIso());

        for (Sensor s : sensors)
            b
            .addAudio(format, RATE, getNumChannels(s))
            .setStreamTag("name", s.getName());


        mFFmpeg = b.build();


        /**
         * for each sensor there is thread that copies data to the ffmpeg process. For startup
         * synchronization the threads are blocked until the starttime has been set at which
         * point the threadlock will be released.
         */
        int us = (int) (1e6 / RATE);

        for (int i = 0; i < sensors.length; i++) {
            Sensor s = sensors[i];
            HandlerThread t = new HandlerThread(s.getName());
            t.start();
            Handler h = new Handler(t.getLooper());
            CopyListener l = new CopyListener(i, RATE, s.getName());
            sm.registerListener(l, s, us, s.getFifoMaxEventCount() / 2 * us, h);
            mSensorListeners.add(l);
        }

        SyncLockListener lock = new SyncLockListener(sensors);
        for (int i = 0; i < sensors.length; i++)
            sm.registerListener(lock, sensors[i], us);
    }


    public void stopRecording() {
        if (mFFmpeg != null) {
            try {
                SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);

                for (CopyListener l : mSensorListeners)
                    sm.flush(l);

                mFFmpeg.waitFor();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        mFFmpeg = null;
        super.onDestroy();
    }

    private int getNumChannels(Sensor s) throws Exception {
        switch (s.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_GYROSCOPE:
            case Sensor.TYPE_MAGNETIC_FIELD:
                return 3;

            case Sensor.TYPE_ROTATION_VECTOR:
                return 4;

            default:
                throw new Exception("unknown number of channels for " + s.getName());
        }
    }

    private class CopyListener implements SensorEventListener, SensorEventListener2 {
        private final int index;
        private final long mDelayUS;
        private long mSampleCount;
        private long mErrorUS;
        private final String mName;

        private OutputStream mOut;
        private ByteBuffer mBuf;
        private long mLastTimestamp = -1;
        private boolean mFlushCompleted = false;

        /**
         * @param i
         * @param rate
         * @param name
         */
        public CopyListener(int i, double rate, String name) {
            index = i;
            mOut = null;
            mName = name;
            mErrorUS = 0;
            mDelayUS = (long) (1e6 / rate);
            mSampleCount = 0;
        }

        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            try {
                /*
                 * wait until the mStartTimeNS is cleared. This will be done by the SyncLockListener
                 */
                mSyncLatch.await();

                /*
                 * if a flush was completed, the sensor process is done, and the recording
                 * will be stopped. Hence the output channel is closed to let ffmpeg know,
                 * that the recording is finished. This will then lead to an IOException,
                 * which cleanly exits the whole process.
                 */
                if (mFlushCompleted)
                    mOut.close();

                if (mLastTimestamp != -1)
                    mErrorUS += (sensorEvent.timestamp - mLastTimestamp) / 1000 - mDelayUS;

                /**
                 *  multiple stream synchronization, wait until a global timestamp was set,
                 *  and only start pushing events after this timestamp.
                 */
                if (sensorEvent.timestamp < mStartTimeNS) {
                    return;
                }

                /*
                 * create an output buffer, once created only delete the last sample. Insert
                 * values afterwards.
                 */
                if (mBuf == null) {
                    mBuf = ByteBuffer.allocate(4 * sensorEvent.values.length);
                    mBuf.order(ByteOrder.nativeOrder());
                    Log.e("bgrec", String.format("%s started at %d", mName, sensorEvent.timestamp));
                } else
                    mBuf.clear();

                for (float v : sensorEvent.values)
                    mBuf.putFloat(v);

                /**
                 * check whether or not interpolation is required
                 */
                if (Math.abs(mErrorUS) > 1.1 * mDelayUS)
                    Log.e("bgrec", String.format(
                            "sample delay too large %.4f %s", mErrorUS / 1e6, mName));

                if (mOut == null)
                    mOut = mFFmpeg.getOutputStream(index);

                if (mErrorUS < -mDelayUS) {   // too fast -> remove
                    mErrorUS += (sensorEvent.timestamp - mLastTimestamp) / 1000;
                } else if (mErrorUS > mDelayUS) {   // too slow -> copy'n'insert
                    while (mErrorUS > mDelayUS) {
                        mOut.write(mBuf.array());
                        mErrorUS -= mDelayUS;
                        mSampleCount++;
                    }
                } else {   // rate ok -> write
                    mOut.write(mBuf.array());
                    mSampleCount++;
                }

                mLastTimestamp = sensorEvent.timestamp;

            } catch (Exception e) {
                e.printStackTrace();
                SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
                sm.unregisterListener(this);
                Log.e("bgrec", String.format("%d samples written %s", mSampleCount, mName));
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {
        }

        @Override
        public void onFlushCompleted(Sensor sensor) {
            mFlushCompleted = true;
        }
    }

    private class SyncLockListener implements SensorEventListener {
        private final Sensor[] mSensors;
        private boolean started[];

        public SyncLockListener(Sensor[] sensors) {
            mSensors = sensors;
            started = new boolean[sensors.length];
            Arrays.fill(started, false);
            mSyncLatch = new CountDownLatch(1);
        }


        /*
         * wait for all sensor to deliver events, if all sensors are started, free the countdown
         * latch and unregister this listerner.
         */
        @Override
        public void onSensorChanged(SensorEvent event) {
            int i = Arrays.asList(mSensors).indexOf(event.sensor);

            if (!started[i])
                mStartTimeNS = Long.max(event.timestamp, mStartTimeNS);

            started[i]= true;

            boolean allstarted = true;
            for (i=0; i < started.length; i++)
                allstarted &= started[i];

            if (allstarted) {
                mSyncLatch.countDown();

                ((SensorManager) getSystemService(SENSOR_SERVICE))
                        .unregisterListener(this);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }
}
