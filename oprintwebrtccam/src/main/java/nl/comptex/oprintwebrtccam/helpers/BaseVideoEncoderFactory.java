package nl.comptex.oprintwebrtccam.helpers;

import android.util.Log;

import androidx.annotation.Nullable;

import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoEncoder;

import java.util.ArrayList;
import java.util.Collection;

/** Helper class that combines HW and SW encoders. */
public class BaseVideoEncoderFactory extends DefaultVideoEncoderFactory {

    private static final String TAG = "BaseVideoEncoderFactory";
    private final Collection<String> enabledCodecs;

    /**
     * Create encoder factory using default hardware encoder factory.
     */
    public BaseVideoEncoderFactory(EglBase.Context eglContext, Collection<String> enabledCodecs) {
        super(eglContext, true, true);
        this.enabledCodecs = enabledCodecs;
    }

    @Nullable
    @Override
    public VideoEncoder createEncoder(VideoCodecInfo info) {
        VideoEncoder encoder = super.createEncoder(info);
        if (encoder == null)
            return null;

        Log.d(TAG, "Using hardware encoder: " + encoder.isHardwareEncoder());
        return encoder;
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        VideoCodecInfo[] infos = super.getSupportedCodecs();

        ArrayList<VideoCodecInfo> finalList = new ArrayList<>(infos.length);

        for (VideoCodecInfo info : infos) {
            if (enabledCodecs.contains(info.name))
                finalList.add(info);
        }

        return finalList.toArray(new VideoCodecInfo[0]);
    }
}
