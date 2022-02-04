package nl.comptex.oprintwebrtccam;


import static org.webrtc.SessionDescription.Type.OFFER;

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
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Session;
import org.webrtc.Camera2Session;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpSender;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import nl.comptex.oprintwebrtccam.helpers.CameraHelper;
import nl.comptex.oprintwebrtccam.helpers.EglBaseSingleton;
import nl.comptex.oprintwebrtccam.helpers.PeerConnectionObserver;
import nl.comptex.oprintwebrtccam.helpers.SnapshotSink;
import nl.comptex.oprintwebrtccam.helpers.WebServer;

public class WebRTCService extends Service {
    private static final int ONGOING_NOTIFICATION_ID = 1337;
    private static final String WEBRTC_CHANNEL = "webrtcchannel";
    private static final String STREAM_ID = "OctoPrintStream";
    private static final String TAG = "WebRTCService";
    private static boolean isRunning = false;

    private boolean usingFrontFacingCamera;

    private WebServer server;

    private PeerConnectionFactory factory;
    private SurfaceTextureHelper helper;

    private final Object lock = new Object();
    private PeerConnection connection;

    private VideoCapturer capturer;

    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private String cameraDeviceName;
    private int orientation;
    private int width;
    private int height;
    private int framerate;
    private SnapshotSink sink;

    public WebRTCService() {
    }

    //region Lifecycle callbacks

    @Override
    public void onCreate() {
        getPreferences();

        createVideoStreamTrack();
        createAudioStreamTrack();

        server = new WebServer(new WebServer.RequestListener() {
            @Override
            public String onOffer(String sdp) {
                if (connection != null)
                    connection.close();
                return doAnswer(sdp);
            }

            @Override
            public byte[] onSnapshot() {
                return sink.getSnapshot();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotification();

        capturer.startCapture(width, height, framerate);

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
        server.stop();
        if (connection != null) {
            connection.dispose();
        } else {
            videoTrack.dispose();
            audioTrack.dispose();
        }
        capturer.dispose();
        videoSource.dispose();
        audioSource.dispose();
        helper.dispose();
        factory.dispose();
        EglBaseSingleton.release();
        isRunning = false;
        super.onDestroy();
    }

    private void getPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        cameraDeviceName = prefs.getString(getString(R.string.camera_preference), null);

        orientation = Integer.parseInt(prefs.getString(getString(R.string.orientation_preference), Integer.toString(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)));

        String[] resolution = prefs.getString(getString(R.string.resolution_preference), "1920x1080").split("x");
        width = Integer.parseInt(resolution[0]);
        height = Integer.parseInt(resolution[1]);

        framerate = prefs.getInt(getString(R.string.framerate_preference), 30);
    }

    //endregion

    //region MediaStream creation methods

    private void createVideoStreamTrack() {
        PeerConnectionFactory.InitializationOptions initOptions = PeerConnectionFactory.InitializationOptions.builder(this.getApplicationContext())
                .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        int angle = getAngle(orientation);
        Camera1Session.fixedRotation = angle;
        Camera2Session.fixedRotation = angle;

        EglBase eglBase = EglBaseSingleton.getEglBase();

        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        videoSource = factory.createVideoSource(false);
        videoTrack = factory.createVideoTrack("VIDEO", videoSource);
        videoTrack.setEnabled(true);

        sink = new SnapshotSink();
        videoTrack.addSink(sink);

        capturer = createVideoCapturer(cameraDeviceName);
        helper = SurfaceTextureHelper.create("THREAD", eglBase.getEglBaseContext());
        capturer.initialize(helper, this, videoSource.getCapturerObserver());

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
        usingFrontFacingCamera = CameraHelper.isFrontFacing(this, deviceName);
        return CameraHelper.createCapturer(this, deviceName);
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

    private void setMaxBitrate(String trackKind, int maxBitrateKbps) {
        RtpSender localSender = null;
        for (RtpSender sender : connection.getSenders()) {
            if (Objects.requireNonNull(sender.track()).kind().equals(trackKind)) {
                localSender = sender;
                break;
            }
        }

        Log.d(TAG, "Requested max "+trackKind+" bitrate: " + maxBitrateKbps);
        if (localSender == null) {
            Log.w(TAG, "Sender is not ready.");
            return;
        }
        RtpParameters parameters = localSender.getParameters();
        if (parameters.encodings.size() == 0) {
            Log.w(TAG, "RtpParameters are not ready.");
            return;
        }
        for (RtpParameters.Encoding encoding : parameters.encodings) {
            // Null value means no limit.
            encoding.maxBitrateBps = maxBitrateKbps * 1000;
        }
        if (!localSender.setParameters(parameters)) {
            Log.e(TAG, "RtpSender.setParameters failed.");
        }
        Log.d(TAG, "Configured max bitrate for " + trackKind + " to: " + maxBitrateKbps);
    }


    private String doAnswer(String offerSdp) {
        MediaConstraints constraints = new MediaConstraints();

        connection = createPeerConnection(factory);
        connection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(OFFER, offerSdp));

        connection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                connection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                Log.d(TAG, "Generated initial answer");
            }
        }, constraints);

        synchronized (lock) {
            try {
                if (connection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
                    Log.d(TAG, "Waiting for ICE to complete");
                    lock.wait();
                    Log.d(TAG, "ICE gathering completed, continuing");
                } else {
                    Log.d(TAG, "ICE gathering already complete, continuing");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                return null;
            }
        }

        try {
            JSONObject message = new JSONObject();
            SessionDescription description = connection.getLocalDescription();
            message.put("type", description.type.canonicalForm());
            message.put("sdp", description.description);
            Log.d(TAG, "Sending final answer");
            return message.toString();
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }

    }

    private PeerConnection createPeerConnection(PeerConnectionFactory factory) {
        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        String URL = "stun:stun.l.google.com:19302";
        iceServers.add(PeerConnection.IceServer.builder(URL).createIceServer());
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.enableCpuOveruseDetection = false;

        PeerConnection.Observer pcObserver = new PeerConnectionObserver() {
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                super.onIceConnectionChange(iceConnectionState);
                if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                    setMaxBitrate(MediaStreamTrack.VIDEO_TRACK_KIND, 4000);
                    setMaxBitrate(MediaStreamTrack.AUDIO_TRACK_KIND, 40);
                }
            }

            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                super.onIceGatheringChange(iceGatheringState);
                if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                    synchronized (lock) {
                        Log.d(TAG, "gathering complete, notifying...");
                        lock.notify();
                    }
                }
            }
        };

        PeerConnection peerConnection = factory.createPeerConnection(config, pcObserver);
        assert peerConnection != null;
        List<String> streamIds = Collections.singletonList(STREAM_ID);
        peerConnection.addTrack(videoTrack, streamIds);
        peerConnection.addTrack(audioTrack, streamIds);
        return peerConnection;
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
        Log.d(TAG, "addSink: " + surfaceView.hashCode());
        videoTrack.addSink(surfaceView);
    }

    public void removeSink(SurfaceViewRenderer surfaceView) {
        Log.d(TAG, "removeSink: " + surfaceView.hashCode());
        videoTrack.removeSink(surfaceView);
    }

    public boolean isUsingFrontFacingCamera() {
        return usingFrontFacingCamera;
    }

    public static boolean isIsRunning() {
        return isRunning;
    }

    //endregion

    public void createNotification() {
        int mutabilityFlag;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            mutabilityFlag = PendingIntent.FLAG_IMMUTABLE;
        } else {
            mutabilityFlag = 0;
        }

        PendingIntent onClickPendingIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                mutabilityFlag
        );

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(WEBRTC_CHANNEL, "WebRTC background", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }

        Notification notification =
                new NotificationCompat.Builder(this, WEBRTC_CHANNEL)
                        .setContentTitle("WebRTC camera")
                        .setContentText("Tap to return to app")
                        .setSmallIcon(R.drawable.videocamera)
                        .setContentIntent(onClickPendingIntent)
                        .setTicker("WebRTC camera background process")
                        .build();

        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }
}