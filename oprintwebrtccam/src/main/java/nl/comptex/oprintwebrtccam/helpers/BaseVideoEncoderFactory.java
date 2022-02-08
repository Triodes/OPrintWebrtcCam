package nl.comptex.oprintwebrtccam.helpers;

import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.VideoCodecInfo;

import java.util.ArrayList;
import java.util.Collection;

/** Helper class that combines HW and SW encoders. */
public class BaseVideoEncoderFactory extends DefaultVideoEncoderFactory {

    private final Collection<String> enabledCodecs;

    /** Create encoder factory using default hardware encoder factory. */
    public BaseVideoEncoderFactory(EglBase.Context eglContext, Collection<String> enabledCodecs) {
        super(eglContext, true, true);
        this.enabledCodecs = enabledCodecs;
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
