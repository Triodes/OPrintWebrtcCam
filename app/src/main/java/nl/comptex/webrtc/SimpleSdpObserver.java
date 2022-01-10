package nl.comptex.webrtc;

import android.util.Log;

import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

class SimpleSdpObserver implements SdpObserver {
    private final String TAG = "SDPObserver";
    @Override
    public void onCreateSuccess(SessionDescription sessionDescription) {
    }

    @Override
    public void onSetSuccess() {
    }

    @Override
    public void onCreateFailure(String s) {
        Log.d(TAG, "Failed to create session: " + s);
    }

    @Override
    public void onSetFailure(String s) {
    }

}
