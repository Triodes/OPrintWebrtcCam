package nl.comptex.oprintwebrtccam.helpers;

import android.content.Context;

import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;

public class CameraHelper {
    public static String[] getCameras(Context context) {
        if (Camera2Enumerator.isSupported(context)) {
            return new Camera2Enumerator(context).getDeviceNames();
        } else {
            return new Camera1Enumerator(true).getDeviceNames();
        }
    }
}
