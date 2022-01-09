package nl.comptex.webrtc;

import static org.webrtc.SessionDescription.Type.OFFER;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

class WebServer extends NanoHTTPD {
    private static final String TAG = "WEBSERVER";

    private final PeerConnection connection;

    public WebServer(PeerConnection connection) throws IOException {
        super(8080);
        this.connection = connection;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        Log.d(TAG, "Running! Point your browsers to http://<phone-ip>:8080/");
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
            Log.i(TAG + " offer", sdp);
            if (type.equals("offer")) {
                connection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(OFFER, sdp));
                return doAnswer();
            }
            return badRequest();
        } catch (JSONException e) {
            e.printStackTrace();
            return badRequest();
        }
    }

    private Response doAnswer() {
        synchronized (connection) {
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
                    Log.i(TAG + " initialAnswer", sessionDescription.description);
                }
            }, constraints);

            try {
                connection.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return badRequest(Status.INTERNAL_ERROR);
            }

            try {
                JSONObject message = new JSONObject();
                SessionDescription description = connection.getLocalDescription();
                message.put("type", description.type.canonicalForm());
                message.put("sdp", description.description);
                Log.i(TAG + " answer", description.description);
                return goodRequest(message.toString());
            } catch (JSONException e) {
                e.printStackTrace();
                return badRequest(Status.INTERNAL_ERROR);
            }
        }
    }

    private Response badRequest() {
        return badRequest(Status.BAD_REQUEST);
    }

    private Response badRequest(Status statusCode) {
        return addHeaders(newFixedLengthResponse(statusCode, MIME_PLAINTEXT, ""));
    }

    private Response goodRequest() {
        return goodRequest("{}");
    }

    private Response goodRequest(String json) {
        return addHeaders(newFixedLengthResponse(Status.OK, "application/json", json));
    }

    private Response addHeaders(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Max-Age", "3628800");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "*");

        return response;
    }
}
