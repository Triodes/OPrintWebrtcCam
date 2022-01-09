package nl.comptex.webrtc;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PeerConnectionFactory.InitializationOptions;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.util.ArrayList;

import io.socket.client.Socket;
import nl.comptex.webrtc.databinding.ActivityMainBinding;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = "MAINACT";
    private static final boolean USE_FRONT_CAMERA = true;

    private ActivityMainBinding binding;
    private EglBase eglBase;
    private WebServer server;
    private PeerConnection connection;
    private Socket socket;


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String[] perms = {Manifest.permission.CAMERA};
        if (!EasyPermissions.hasPermissions(this, perms)) {
            EasyPermissions.requestPermissions(this, "Need some permissions", 1337, perms);
        }

        InitializationOptions initOptions = InitializationOptions.builder(this.getApplicationContext())
                .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        eglBase = EglBase.create();
        binding.surfaceView.init(eglBase.getEglBaseContext(), null);
        binding.surfaceView.setEnableHardwareScaler(true);
        binding.surfaceView.setMirror(true);

        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        PeerConnectionFactory factory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(decoderFactory)
                .setVideoEncoderFactory(encoderFactory)
                .createPeerConnectionFactory();

        VideoSource videoSource = factory.createVideoSource(false);
        VideoTrack track = factory.createVideoTrack("VIDEO", videoSource);

        VideoCapturer capturer = createVideoCapturer();
        SurfaceTextureHelper helper = SurfaceTextureHelper.create("THREAD", eglBase.getEglBaseContext());
        capturer.initialize(helper, this, videoSource.getCapturerObserver());
        capturer.startCapture(1920, 1080, 60);

        track.setEnabled(true);
        track.addSink(binding.surfaceView);

        connection = createPeerConnection(factory);
        MediaStream mediaStream = factory.createLocalMediaStream("STREAM");
        mediaStream.addTrack(track);
        connection.addStream(mediaStream);

//
//        try {
//            socket = IO.socket("http://192.168.2.22:3000");
//            socket.on(EVENT_CONNECT, args -> {
//                Log.d(TAG, "Connected");
//                socket.emit("ident","camera");
//            });
//            socket.on("message", args -> {
//                assert args[0] instanceof JSONObject;
//                try {
//                    JSONObject obj = (JSONObject) args[0];
//                    String type = obj.getString("type");
//                    if (type.equals("offer")) {
//                        connection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(OFFER, obj.getString("sdp")));
//                        doAnswer(socket);
//                    }
//                    if (type.equals("candidate")) {
//                        IceCandidate candidate = new IceCandidate(obj.getString("id"), obj.getInt("label"), obj.getString("candidate"));
//                        connection.addIceCandidate(candidate);
//                    }
//                } catch (JSONException e) {
//                    e.printStackTrace();
//                }
//
//            });
//            socket.connect();
//        } catch (URISyntaxException e) {
//            e.printStackTrace();
//        }

        try {
            server = new WebServer(connection);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void doAnswer(Socket socket) {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair("maxWidth", "1920"));
        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair("maxHeight", "1080"));
        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair("maxFrameRate", "60"));
        connection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                connection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                JSONObject message = new JSONObject();
                try {
                    message.put("type", sessionDescription.type.canonicalForm());
                    message.put("sdp", sessionDescription.description);
                    socket.emit("message", message);
                    Log.i(TAG + " answer", sessionDescription.description);
                    Log.i(TAG, sessionDescription.type.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, s);
            }
        }, constraints);
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

    private PeerConnection createPeerConnection(PeerConnectionFactory factory) {
        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        String URL = "stun:stun.l.google.com:19302";
        iceServers.add(PeerConnection.IceServer.builder(URL).createIceServer());

        PeerConnection.Observer pcObserver = new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "onSignalingChange: " + signalingState.name());
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: " + iceConnectionState.name());
//                Log.d(TAG, "setting bitrate");
//                connection.setBitrate(256000, null, 10000000);
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                Log.d(TAG, "onIceConnectionReceivingChange: ");
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: " + iceGatheringState.name());

                if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                    synchronized (connection) {
                        connection.notify();
                    }
                }
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: " + iceCandidate.toString());

//                JSONObject message = new JSONObject();
//
//                try {
//                    message.put("type", "candidate");
//                    message.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
//                    message.put("sdpMid", iceCandidate.sdpMid);
//                    message.put("candidate", iceCandidate.sdp);
//
//                    socket.emit("message", message);
//                } catch (JSONException e) {
//                    e.printStackTrace();
//                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                Log.d(TAG, "onIceCandidatesRemoved: ");
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.d(TAG, "onAddStream: " + mediaStream.videoTracks.size());
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(TAG, "onRemoveStream: ");
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "onDataChannel: ");
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded: ");
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "onAddStream: " + mediaStreams.length);
            }
        };

        return factory.createPeerConnection(iceServers, pcObserver);
    }
}