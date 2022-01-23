package nl.comptex.oprintwebrtccam;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;

import androidx.preference.PreferenceManager;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera1Session;
import org.webrtc.Camera2Enumerator;
import org.webrtc.Camera2Session;
import org.webrtc.CameraEnumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.IOException;

public class WebRTCService extends Service {
    private boolean usingFrontFacingCamera;
    private static final int ONGOING_NOTIFICATION_ID = 1337;
    private static final String WEBRTC_CHANNEL = "webrtcchannel";
    private static boolean isRunning = false;

    private WebServer server;

    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private SurfaceTextureHelper helper;

    private VideoCapturer capturer;

    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private AudioSource audioSource;
    private AudioTrack audioTrack;

    public WebRTCService() {
    }

    //region Lifecycle callbacks

    @Override
    public void onCreate() {
        createVideoStreamTrack();
        createAudioStreamTrack();

        server = new WebServer(factory, videoTrack, audioTrack);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotification();

        capturer.startCapture(1920,1080, 30);

        try {
            if (!server.wasStarted())
                server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

        isRunning = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        server.dispose();
        capturer.dispose();
        videoSource.dispose();
        audioSource.dispose();
        helper.dispose();
        factory.dispose();
        eglBase.release();
        isRunning = false;
        super.onDestroy();
    }

    //endregion

    //region MediaStream creation methods

    private void createVideoStreamTrack() {
        PeerConnectionFactory.InitializationOptions initOptions = PeerConnectionFactory.InitializationOptions.builder(this.getApplicationContext())
                .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int orientation = Integer.parseInt(prefs.getString(this.getString(R.string.orientation_preference), Integer.toString(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)));
        int angle = getAngle(orientation);
        Camera1Session.fixedRotation = angle;
        Camera2Session.fixedRotation = angle;

        eglBase = EglBase.create();

        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        videoSource = factory.createVideoSource(false);
        videoTrack = factory.createVideoTrack("VIDEO", videoSource);

        capturer = createVideoCapturer(prefs.getString(this.getString(R.string.camera_preference), null));
        helper = SurfaceTextureHelper.create("THREAD", eglBase.getEglBaseContext());
        capturer.initialize(helper, this, videoSource.getCapturerObserver());

        videoTrack.setEnabled(true);
    }

    private int getAngle(int orientation) {
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                return 270;
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                return 180;
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                return 90;
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
            default:
                return 0;
        }
    }

    private VideoCapturer createVideoCapturer(String deviceName) {
        CameraEnumerator enumerator;
        if (Camera2Enumerator.isSupported(this)) {
            enumerator = new Camera2Enumerator(this);
        } else {
            enumerator = new Camera1Enumerator(true);
        }
        usingFrontFacingCamera = enumerator.isFrontFacing(deviceName);
        return enumerator.createCapturer(deviceName, null);
    }

    private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
    private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
    private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
    private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";

    private void createAudioStreamTrack() {
        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "true"));

        audioSource = factory.createAudioSource(audioConstraints);
        audioTrack = factory.createAudioTrack("AUDIO", audioSource);
        audioTrack.setEnabled(true);
    }

    //endregion

    //region Binding logic and methods

    private final IBinder binder = new LocalBinder();

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        WebRTCService getService() {
            // Return this instance of LocalService so clients can call public methods
            return WebRTCService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void addSink(SurfaceViewRenderer surfaceView) {
        videoTrack.addSink(surfaceView);
    }

    public void removeSink(SurfaceViewRenderer surfaceView) {
        videoTrack.removeSink(surfaceView);
    }

    public EglBase getEglBase() {
        return eglBase;
    }

    //endregion

    public void createNotification() {
        PendingIntent onClickPendingIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        NotificationChannel chan = new NotificationChannel(WEBRTC_CHANNEL, "WebRTC background", NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(chan);

        Notification notification =
                new Notification.Builder(this, WEBRTC_CHANNEL)
                        .setContentTitle("WebRTC camera")
                        .setContentText("Tap to return to app")
                        .setSmallIcon(R.drawable.videocamera)
                        .setContentIntent(onClickPendingIntent)
                        .setTicker("WebRTC camera background process")
                        .build();

        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    public boolean isUsingFrontFacingCamera() {
        return usingFrontFacingCamera;
    }

    public static boolean isIsRunning() { return isRunning; }
}