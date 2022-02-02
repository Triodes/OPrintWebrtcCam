package nl.comptex.oprintwebrtccam.helpers;

import android.content.Context;

import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;

import java.util.List;

public class CameraHelper {
    private static CameraEnumerator enumerator;

    public static String[] getCameras(Context context) {
        return getEnumerator(context).getDeviceNames();
    }

    public static List<CaptureFormat> getSupportedFormats(Context context, String deviceName) {
        return getEnumerator(context).getSupportedFormats(deviceName);
    }

    public static boolean isFrontFacing(Context context, String deviceName) {
        return getEnumerator(context).isFrontFacing(deviceName);
    }

    public static CameraVideoCapturer createCapturer(Context context, String deviceName) {
        return getEnumerator(context).createCapturer(deviceName, null);
    }

    private static CameraEnumerator getEnumerator(Context context) {
        if (enumerator != null)
            return enumerator;

        if (Camera2Enumerator.isSupported(context)) {
            enumerator = new Camera2Enumerator(context);
        } else {
            enumerator = new Camera1Enumerator(true);
        }

        return enumerator;
    }
}
