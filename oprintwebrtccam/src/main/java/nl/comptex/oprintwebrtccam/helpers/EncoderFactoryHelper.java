package nl.comptex.oprintwebrtccam.helpers;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.HardwareVideoEncoderFactory;
import org.webrtc.Predicate;
import org.webrtc.VideoEncoderFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class EncoderFactoryHelper {


    private static final String TAG = "EncoderFactoryHelper";

    @NonNull
    public static VideoEncoderFactory getVideoEncoderFactory(int width, int height, boolean forceH264, EglBase eglBase) {
        String mime = forceH264 ? MediaFormat.MIMETYPE_VIDEO_AVC : MediaFormat.MIMETYPE_VIDEO_VP8;
        try {
            return getVideoEncoderFactoryForMime(width, height, new String[]{mime}, eglBase);
        } catch (IOException e) {
            Log.d(TAG, "getVideoEncoderFactory: creation of factory for " + mime + " failed, falling back on default encoder");
        }
        return new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
    }

    @NonNull
    private static VideoEncoderFactory getVideoEncoderFactoryForMime(int width, int height, String[] mimeTypes, EglBase eglBase) throws IOException {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        ArrayList<Predicate<MediaCodecInfo>> predicates = new ArrayList<>(mimeTypes.length);
        for (String mime : mimeTypes) {
            MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            MediaCodec codec = MediaCodec.createByCodecName(codecList.findEncoderForFormat(format));
            final MediaCodecInfo info = codec.getCodecInfo();
            predicates.add(mediaCodecInfo -> mediaCodecInfo.equals(info));
        }

        return new HardwareVideoEncoderFactory(
                eglBase.getEglBaseContext(),
                false,
                true,
                reducePredicates(predicates)
        );
    }

    @Nullable
    private static Predicate<MediaCodecInfo> reducePredicates(List<Predicate<MediaCodecInfo>> predicates) {
        if (predicates.isEmpty())
            return null;

        if (predicates.size() == 1)
            return predicates.get(0);

        Predicate<MediaCodecInfo> finalPredicate = null;
        for (Predicate<MediaCodecInfo> predicate : predicates) {
            if (finalPredicate == null) {
                finalPredicate = predicate;
            } else {
                finalPredicate = finalPredicate.or(predicate);
            }
        }
        return finalPredicate;
    }
}
