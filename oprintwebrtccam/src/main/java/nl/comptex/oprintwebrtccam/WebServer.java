package nl.comptex.oprintwebrtccam;

import static org.webrtc.SessionDescription.Type.OFFER;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

class WebServer extends NanoHTTPD {
    private static final String TAG = "WEBSERVER";
    private final String STREAM_ID = "OctoPrintStream";
    private final PeerConnectionFactory factory;
    private final Object lock = new Object();
    private final VideoTrack track;
    private PeerConnection connection;

    public WebServer(PeerConnectionFactory factory, VideoTrack track) {
        super(8080);
        this.factory = factory;
        this.track = track;
    }

    public void start() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        Log.i(TAG, "Running! Point your browsers to http://<phone-ip>:8080/");
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (session.getMethod() != Method.POST)
            return goodRequest();

        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (IOException | ResponseException e) {
            e.printStackTrace();
            return badRequest();
        }

        String postData = files.get("postData");
        if (postData == null)
            return badRequest();

        JSONObject obj;
        try {
            obj = new JSONObject(postData);
        } catch (JSONException e) {
            e.printStackTrace();
            return badRequest();
        }

        try {
            String sdp = obj.getString("sdp");
            String type = obj.getString("type");
            if (type.equals(OFFER.canonicalForm())) {
                Log.d(TAG, "Received offer");
                if (connection != null)
                    connection.close();
                connection = createPeerConnection(factory);
                connection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(OFFER, sdp));
                return doAnswer();
            }
            return badRequest();
        } catch (JSONException e) {
            e.printStackTrace();
            return badRequest();
        }
    }

    public void dispose() {
        this.stop();
        if (connection != null)
            connection.dispose();
        else
            track.dispose();
    }

    private Response doAnswer() {
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
                return badRequest(Status.INTERNAL_ERROR);
            }
        }

        try {
            JSONObject message = new JSONObject();
            SessionDescription description = connection.getLocalDescription();
            message.put("type", description.type.canonicalForm());
            message.put("sdp", description.description);
            Log.d(TAG, "Sending final answer");
            return goodRequest(message.toString());
        } catch (JSONException e) {
            e.printStackTrace();
            return badRequest(Status.INTERNAL_ERROR);
        }

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
                if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                    setVideoMaxBitrate();
                }
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
            }
        };

        PeerConnection peerConnection = factory.createPeerConnection(iceServers, pcObserver);
        assert peerConnection != null;
        peerConnection.addTrack(track, Collections.singletonList(STREAM_ID));
        return peerConnection;
    }

    public void setVideoMaxBitrate() {
        RtpSender localVideoSender = connection.getSenders().get(0);
        int maxBitrateKbps = 4000;

        Log.d(TAG, "Requested max video bitrate: " + maxBitrateKbps);
        if (localVideoSender == null) {
            Log.w(TAG, "Sender is not ready.");
            return;
        }
        RtpParameters parameters = localVideoSender.getParameters();
        if (parameters.encodings.size() == 0) {
            Log.w(TAG, "RtpParameters are not ready.");
            return;
        }
        for (RtpParameters.Encoding encoding : parameters.encodings) {
            // Null value means no limit.
            encoding.maxBitrateBps = maxBitrateKbps * 1000;
        }
        if (!localVideoSender.setParameters(parameters)) {
            Log.e(TAG, "RtpSender.setParameters failed.");
        }
        Log.d(TAG, "Configured max video bitrate to: " + maxBitrateKbps);
    }

    //region Response utility functions
    private Response badRequest() {
        return badRequest(Status.BAD_REQUEST);
    }

    private Response badRequest(Status statusCode) {
        return addHeaders(newFixedLengthResponse(statusCode, MIME_PLAINTEXT + "; charset=UTF-8", ""));
    }

    private Response goodRequest() {
        return goodRequest("{}");
    }

    private Response goodRequest(String json) {
        return addHeaders(newFixedLengthResponse(Status.OK, "application/json; charset=UTF-8", json));
    }

    private Response addHeaders(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Max-Age", "3628800");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "*");

        return response;
    }
    //endregion
}
