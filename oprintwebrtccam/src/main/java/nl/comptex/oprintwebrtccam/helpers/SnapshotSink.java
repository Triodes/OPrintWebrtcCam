package nl.comptex.oprintwebrtccam.helpers;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class SnapshotSink implements VideoSink {
    private static final String TAG = "SnapshotListener";
    private final Object lock = new Object();
    private Boolean snapshotRequested = false;
    private byte[] image;

    @Override
    public void onFrame(VideoFrame videoFrame) {
        synchronized (lock) {
            if (!snapshotRequested) {
                return;
            }
            image = imageToByteArray(videoFrame.getBuffer().toI420(), videoFrame.getRotation());
            snapshotRequested = false;
            lock.notify();
        }
    }

    public byte[] getSnapshot() {
        synchronized (lock) {
            snapshotRequested = true;
            try {
                lock.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return image;
        }
    }

    public byte[] imageToByteArray(VideoFrame.I420Buffer buffer, int rotationDegrees) {

        int width = buffer.getWidth();
        int height = buffer.getHeight();
        int ySize = height * width;
        int uvSize = ySize / 2;
        ByteBuffer ib = ByteBuffer.allocate(ySize + ySize);

        ByteBuffer y = buffer.getDataY();
        ByteBuffer u = buffer.getDataU();
        ByteBuffer v = buffer.getDataV();

        ib.put(y);

        ByteBuffer uv = ByteBuffer.allocate(uvSize);
        for (int i = 0; i < height / 2; i++) {
            for (int j = 0; j < width / 2; j++) {
                uv.put(v.get(buffer.getStrideV() * i + j));
                uv.put(u.get(buffer.getStrideU() * i + j));
            }
        }

        uv.rewind();
        ib.put(uv);

        YuvImage yuvImage = new YuvImage(ib.array(), ImageFormat.NV21, width, height, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, out);
        byte[] imageBytes = out.toByteArray();

        return imageBytes;
    }
}
