package nl.comptex.oprintwebrtccam.helpers;

import static org.webrtc.SessionDescription.Type.OFFER;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class WebServer extends NanoHTTPD {
    private static final String TAG = "WEBSERVER";

    private final OfferListener listener;

    public WebServer(OfferListener listener) {
        super(8080);
        this.listener = listener;
    }

    public void start() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        Log.i(TAG, "Running! Point your browsers to http://<phone-ip>:8080/");
    }

    @Override
    public NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession session) {
        if (session.getMethod() != NanoHTTPD.Method.POST)
            return goodRequest();

        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (IOException | NanoHTTPD.ResponseException e) {
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
                String result = listener.onOffer(sdp);
                if (result == null)
                    return badRequest(NanoHTTPD.Response.Status.INTERNAL_ERROR);
                else
                    return goodRequest(result);
            }
            return badRequest();
        } catch (JSONException e) {
            e.printStackTrace();
            return badRequest();
        }
    }

    //region Response utility functions
    private NanoHTTPD.Response badRequest() {
        return badRequest(NanoHTTPD.Response.Status.BAD_REQUEST);
    }

    private NanoHTTPD.Response badRequest(NanoHTTPD.Response.Status statusCode) {
        return addHeaders(newFixedLengthResponse(statusCode, MIME_PLAINTEXT + "; charset=UTF-8", ""));
    }

    private NanoHTTPD.Response goodRequest() {
        return goodRequest("{}");
    }

    private NanoHTTPD.Response goodRequest(String json) {
        return addHeaders(newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=UTF-8", json));
    }

    private NanoHTTPD.Response addHeaders(NanoHTTPD.Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Max-Age", "3628800");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "*");

        return response;
    }

    public interface OfferListener {
        String onOffer(String sdp);
    }
}
