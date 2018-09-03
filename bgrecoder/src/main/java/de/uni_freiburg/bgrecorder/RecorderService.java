package de.uni_freiburg.bgrecorder;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

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

        String platform = Build.BOARD + " " + Build.DEVICE + " " + Build.VERSION.SDK_INT,
               output = getDefaultOutputPath(getApplicationContext()),
               android_id =  Settings.Secure.getString(
                               getContentResolver(), Settings.Secure.ANDROID_ID),
               format = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "f32le" : "f32be",
               action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            onDestroy();
            return START_NOT_STICKY;
        }

        if (mFFmpeg != null)
            return START_STICKY;

        /**
         *  Get the sensors from the system and check if they are wakeups
         */
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor[] sensors = new Sensor[] {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true),
            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD, true),
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true),
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR, true)
        };

        for (Sensor s : sensors)
            if (s==null || !s.isWakeUpSensor())
                throw new Error("wakeup sensor not supported!");

        /**
         * build an ffmpeg process
         */
        try {
            FFMpegProcess.Builder b = new FFMpegProcess.Builder(getApplicationContext())
                .setOutput(output, "matroska")
                .setCodec("a", "wavpack")
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
             * now hook the sensorlisteners to ffmpeg, in a thread for each sensor
             */
            for (int i = 0; i < sensors.length; i++) {
                int us = (int) (1e6/RATE);
                Sensor s = sensors[i];
                HandlerThread t = new HandlerThread(s.getName() + " thread"); t.start();
                Handler h = new Handler(t.getLooper());
                CopyListener c = new CopyListener(mFFmpeg, i, RATE, s.getName());
                sm.registerListener(c, s, us, s.getFifoMaxEventCount()/2 * us, h);
            }

        } catch (Exception e) {
            e.printStackTrace();
            return START_NOT_STICKY;
        }

        /** notify the system that a new recording was started, and make
         * sure that the service does not get called when an activity is
         * destroyed by using the startForeground method. If not doing so,
         * the service is also killed when an accompanying Activity is
         * destroyed (wtf). */
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setPriority(Notification.PRIORITY_LOW)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mFFmpeg != null) {
            try {
                mFFmpeg.terminate();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
p
        mFFmpeg = null;
        stopForeground(true);
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

    private class CopyListener implements SensorEventListener {
        private final int index;
        private final FFMpegProcess ffmpeg;
        private final long mDelayUS;
        private long mErrorUS;
        private final String mName;

        private OutputStream mOut;
        private ByteBuffer mBuf;
        private long mLastTimestamp = -1;

        /** delayed open of outputstream to not block the main stream
         * @param mFFmpeg
         * @param i
         * @param rate
         * @param name
         */
        public CopyListener(FFMpegProcess mFFmpeg, int i, double rate, String name) {
            ffmpeg = mFFmpeg;
            index = i;
            mOut = null;
            mName = name;
            mErrorUS = 0;
            mDelayUS = (long) (1e6 / rate);
        }

        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            try {
                if (mBuf == null) {
                    mBuf = ByteBuffer.allocate(4 * sensorEvent.values.length);
                    mBuf.order(ByteOrder.nativeOrder());
                } else
                    mBuf.clear();

                if (mOut == null)
                    mOut = ffmpeg.getOutputStream(index);

                for (float v : sensorEvent.values)
                    mBuf.putFloat(v);

                if (mLastTimestamp != -1)
                    mErrorUS += (sensorEvent.timestamp - mLastTimestamp) / 1000 - mDelayUS;

                if (Math.abs(mErrorUS) > 1.1*mDelayUS )
                    Log.e("bgrec", String.format(
                            "sample delay too large %.4f %s", mErrorUS/1e6, mName));

                if (mErrorUS < -mDelayUS) {         // one sample is missing
                    mOut.write(mBuf.array());
                    mErrorUS += mDelayUS;
                } else if (mErrorUS > mDelayUS) {   // a sample too much
                    ;
                    mErrorUS -= mDelayUS;
                } else                              // normal sample
                    mOut.write(mBuf.array());

                mLastTimestamp = sensorEvent.timestamp;
            } catch (IOException e) {
                e.printStackTrace();
                SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
                sm.unregisterListener(this);

            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {
        }
    }
}
