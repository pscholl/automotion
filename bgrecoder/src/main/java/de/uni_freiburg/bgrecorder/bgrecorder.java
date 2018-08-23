package de.uni_freiburg.bgrecorder;

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

public class bgrecorder extends Service {
    private static final double RATE = 50.;
    private String VERSION = "1.0";
    private FFMpegProcess mFFmpeg;

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
               format = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "f32le" : "f32be";

        if (mFFmpeg != null)
            return START_NOT_STICKY;

        /**
         *  Get the sensors from the system and check if they are wakeups
         */
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor[] sensors = new Sensor[] {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true),
//            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD, true),
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true),
//            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR, true)
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
                HandlerThread t = new HandlerThread(s.getName() + " thread");
                Handler h = new Handler(t.getLooper());
                CopyListener c = new CopyListener(mFFmpeg.getOutputStream(i));
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
        //startForeground(status.NOTIFICATION_ID, status.mNotification.build());
        return START_NOT_STICKY;
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
        private final OutputStream mOut;
        private ByteBuffer mBuf;

        public CopyListener(OutputStream outputStream) {
            mOut = outputStream;
        }

        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            if (mBuf == null)
                mBuf = ByteBuffer.allocate(4 * sensorEvent.values.length);
            else
                mBuf.clear();

            for (float v : sensorEvent.values)
                mBuf.putFloat(v);

            try {
                mOut.write(mBuf.array());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {
        }
    }
}
