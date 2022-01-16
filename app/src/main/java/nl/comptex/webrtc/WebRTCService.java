package nl.comptex.webrtc;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.MediaStream;
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
    private static final boolean USE_FRONT_CAMERA = true;
    private static final int ONGOING_NOTIFICATION_ID = 1337;
    public static final String WEBRTCCHANNEL = "webrtcchannel";
    private VideoTrack track;
    private EglBase eglBase;
    private WebServer server;
    private VideoCapturer capturer;
    private PeerConnectionFactory factory;
    private SurfaceTextureHelper helper;
    private VideoSource videoSource;
    private MediaStream mediaStream;

    public WebRTCService() {
    }

    @Override
    public void onCreate() {
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
        track = factory.createVideoTrack("VIDEO", videoSource);


        capturer = createVideoCapturer();
        helper = SurfaceTextureHelper.create("THREAD", eglBase.getEglBaseContext());
        capturer.initialize(helper, this, videoSource.getCapturerObserver());

        track.setEnabled(true);

        mediaStream = factory.createLocalMediaStream("STREAM");
        mediaStream.addTrack(track);

        server = new WebServer(factory, mediaStream);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startNotification();

        capturer.startCapture(1920, 1080, 30);

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
        helper.dispose();
        factory.dispose();
        eglBase.release();
        super.onDestroy();
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
        track.addSink(surfaceView);
    }

    public void removeSink(SurfaceViewRenderer surfaceView) {
        track.removeSink(surfaceView);
    }

    public EglBase getEglBase() {
        return eglBase;
    }

    public void startNotification() {
        PendingIntent onClickPendingIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        NotificationChannel chan = new NotificationChannel(WEBRTCCHANNEL, "WebRTC background", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(chan);

        Notification notification =
                new Notification.Builder(this, WEBRTCCHANNEL)
                        .setContentTitle("WebRTC camera")
                        .setContentText("Tap to return to app")
                        .setSmallIcon(R.drawable.videocamera)
                        .setContentIntent(onClickPendingIntent)
                        .setTicker("WebRTC camera background process")
                        .build();

        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

}