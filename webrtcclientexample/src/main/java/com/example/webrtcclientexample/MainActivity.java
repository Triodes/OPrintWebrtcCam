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

        //I'm using layout binding for easy access to the surfaceView
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        //Initialization of the connectionFactory using basic settings, nothing special
        PeerConnectionFactory.InitializationOptions initOptions = PeerConnectionFactory.InitializationOptions.builder(this.getApplicationContext())
                .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        //This eglBase needs to be used everywhere you need it. Using a different one for the surfaceview and peerConnection(factory)
        //will result in nothing showing on the screen for some reason. They should really have turned this into a singleton
        eglBase = EglBase.create();

        //Basic video encoder and decoder. You can mess around with these to do things with codecs. But it's rather complicated.
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
        //This surfaceview is not the regular one, it's special and comes from the library
        //It needs to be initialised once, and only once. Otherwise it'll throw an exception
        binding.surfaceView.release();
        binding.surfaceView.init(eglBase.getEglBaseContext(), null);
        binding.surfaceView.setEnableHardwareScaler(true);

        //dispose of any previously made connections and create a new one
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
        //You could use these to limit the streams resolution and or framerate I think.
        //The RTC lib should automatically handle staying "within the constraints" if you set any
        MediaConstraints sdpMediaConstraints = new MediaConstraints();

        //Create an offer. Several things to note here. I'm using synchronised blocks and waiting to
        //make sure the ICE candidate gathering is completed before sending the offer. Also note I'm not
        //using the sessionDescription that is passed to the callback, but the connection.getLocalDescription()
        //as the payload of the actual request. That one has the gathered ICE candidates in it which is required
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
        //Create the peerconnection. Lots of callbacks. Only the OnIceGatheringChange and onTrack
        //are actually interesting. I have not tried without a stun server, but technically it's
        //only required if you are not on the same local network as the camera source
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
                        //this wakes up the thread that created the offer and will continue sending
                        //the request after on line 131
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

                //once the connection has been set-up a track will be added on the other side
                //which will show up here. Adding the surfaceView as a sink will cause the track
                //to be rendered on screen!
                if (mediaStreams.length < 1)
                    return;

                if (mediaStreams[0].videoTracks.size() < 1)
                    return;

                currentTrack = mediaStreams[0].videoTracks.get(0);
                currentTrack.addSink(binding.surfaceView);
            }
        };

        //Create the peerconnection and add a transceiver of ReceiveOnly
        //This will tell the other side what we want to be receiving
        //You can also add a transceiver for audio if you would like to receive an audio stream as well
        PeerConnection peerConnection = factory.createPeerConnection(iceServers, pcObserver);
        assert peerConnection != null;
        peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
        return peerConnection;
    }

    private void handleRequest(JSONObject message, PeerConnection connection) {
        // Send the offer and handle the incoming answer
        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                CAM_URL,
                message,
                response -> {
                    try {
                        //Here we check if we actually got an answer. Create a sessionDescription if
                        //we did and add this as the remoteDescription to he peerconnection.
                        //If that is successful any tracks added to the connection from the other side
                        //will start generating onAddTrack events here.
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

        queue.add(request);
    }
}