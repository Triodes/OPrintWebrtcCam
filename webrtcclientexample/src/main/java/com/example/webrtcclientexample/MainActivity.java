package com.example.webrtcclientexample;

import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.example.webrtcclientexample.databinding.ActivityMainBinding;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoTrack;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private final String TAG = "MAINACTIVITY";
    private final String CAM_URL = "http://192.168.2.11:8080";
    ActivityMainBinding binding;
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private final Object lock = new Object();
    private PeerConnection connection;
    private VideoTrack currentTrack;
    private RequestQueue queue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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

        queue = Volley.newRequestQueue(this);
    }

    @Override
    protected void onStart() {
        binding.surfaceView.release();
        binding.surfaceView.init(eglBase.getEglBaseContext(), null);
        binding.surfaceView.setEnableHardwareScaler(true);
        if (connection != null) {
            connection.dispose();
        }
        connection = createPeerConnection(factory);
        doOffer(connection);
        super.onStart();
    }

    @Override
    protected void onStop() {
        if (currentTrack != null)
            currentTrack.removeSink(binding.surfaceView);
        binding.surfaceView.release();
        connection.dispose();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        eglBase.release();
        factory.dispose();
        super.onDestroy();
    }

    private void doOffer(PeerConnection connection) {
        MediaConstraints sdpMediaConstraints = new MediaConstraints();

        connection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "onCreateSuccess: ");
                connection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                new Thread(() -> {
                    synchronized (lock) {
                        try {
                            if (connection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE)
                                lock.wait();
                            JSONObject message = new JSONObject();
                            SessionDescription description = connection.getLocalDescription();
                            message.put("type", description.type.canonicalForm());
                            message.put("sdp", description.description);
                            handleRequest(message, connection);
                        } catch (JSONException | InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }).start();
            }
        }, sdpMediaConstraints);
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
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                Log.d(TAG, "onIceConnectionReceivingChange: " + b);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: " + iceGatheringState.name());

                if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                    synchronized (lock) {
                        Log.d(TAG, "gathering complete, notifying...");
                        lock.notify();
                    }
                }
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: " + iceCandidate.sdp);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                Log.d(TAG, "onIceCandidatesRemoved: ");
                for (IceCandidate candidate : iceCandidates) {
                    Log.d(TAG, candidate.sdp);
                }
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.d(TAG, "onAddStream: " + mediaStream.videoTracks.size());
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(TAG, "onRemoveStream: " + mediaStream.toString());
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "onDataChannel: " + dataChannel.label());
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded");
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "onAddStream: " + mediaStreams.length);
                for (MediaStream stream : mediaStreams) {
                    if (stream.videoTracks.size() > 0) {
                        currentTrack = stream.videoTracks.get(0);
                        currentTrack.addSink(binding.surfaceView);
                        return;
                    }
                }
            }
        };

        PeerConnection peerConnection = factory.createPeerConnection(iceServers, pcObserver);
        assert peerConnection != null;
        peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
        peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
        return peerConnection;
    }

    private void handleRequest(JSONObject message, PeerConnection connection) {
        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                CAM_URL,
                message,
                response -> {
                    try {
                        String type = response.getString("type");
                        if (!type.equals(SessionDescription.Type.ANSWER.canonicalForm()))
                            return;
                        String sdpStr = response.getString("sdp");
                        SessionDescription sdp = new SessionDescription(SessionDescription.Type.ANSWER, sdpStr);
                        connection.setRemoteDescription(new SimpleSdpObserver(), sdp);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                },
                error -> {
                    Log.d(TAG, error.toString());
                }
        );

        request.setRetryPolicy(new DefaultRetryPolicy(20000, 1, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        queue.add(request);
    }
}