package nl.comptex.oprintwebrtccam;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
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
import org.webrtc.voiceengine.WebRtcAudioRecord;
import org.webrtc.voiceengine.WebRtcAudioTrack;

import java.io.IOException;

public class WebRTCService extends Service {
    private static final boolean USE_FRONT_CAMERA = true;
    private static final int ONGOING_NOTIFICATION_ID = 1337;
    public static final String WEBRTC_CHANNEL = "webrtcchannel";

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
        super.onDestroy();
    }

    //endregion

    //region MediaStream creation methods

    private void createVideoStreamTrack() {
        PeerConnectionFactory.InitializationOptions initOptions = PeerConnectionFactory.InitializationOptions.builder(this.getApplicationContext())
                .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        eglBase = EglBase.create();

        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, false);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        videoSource = factory.createVideoSource(false);
        videoTrack = factory.createVideoTrack("VIDEO", videoSource);


        capturer = createVideoCapturer();
        helper = SurfaceTextureHelper.create("THREAD", eglBase.getEglBaseContext());
        capturer.initialize(helper, this, videoSource.getCapturerObserver());

        videoTrack.setEnabled(true);
    }

    private VideoCapturer createVideoCapturer() {
        VideoCapturer videoCapturer;
        if (Camera2Enumerator.isSupported(this)) {
            videoCapturer = createCameraCapturer(new Camera2Enumerator(this));
        } else {
            videoCapturer = createCameraCapturer(new Camera1Enumerator(true));
        }
        return videoCapturer;
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName) == USE_FRONT_CAMERA) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName) != USE_FRONT_CAMERA) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
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

        NotificationChannel chan = new NotificationChannel(WEBRTC_CHANNEL, "WebRTC background", NotificationManager.IMPORTANCE_LOW);
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

}