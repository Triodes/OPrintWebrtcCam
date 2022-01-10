package nl.comptex.webrtc;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PeerConnectionFactory.InitializationOptions;
import org.webrtc.RtpReceiver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.util.ArrayList;

import nl.comptex.webrtc.databinding.ActivityMainBinding;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = "MAINACT";
    private static final boolean USE_FRONT_CAMERA = true;

    private ActivityMainBinding binding;
    private EglBase eglBase;
    private WebServer server;
    private PeerConnection connection;
    private Object lockObject = new Object();


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String[] perms = {Manifest.permission.CAMERA};
        if (!EasyPermissions.hasPermissions(this, perms)) {
            EasyPermissions.requestPermissions(this, "Need some permissions", 1337, perms);
        }

        InitializationOptions initOptions = InitializationOptions.builder(this.getApplicationContext())
                .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        eglBase = EglBase.create();
        binding.surfaceView.init(eglBase.getEglBaseContext(), null);
        binding.surfaceView.setEnableHardwareScaler(true);
        binding.surfaceView.setMirror(true);

        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, false);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        PeerConnectionFactory factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .createPeerConnectionFactory();

        VideoSource videoSource = factory.createVideoSource(false);
        VideoTrack track = factory.createVideoTrack("VIDEO", videoSource);

        VideoCapturer capturer = createVideoCapturer();
        SurfaceTextureHelper helper = SurfaceTextureHelper.create("THREAD", eglBase.getEglBaseContext());
        capturer.initialize(helper, this, videoSource.getCapturerObserver());
        capturer.startCapture(1920, 1080, 60);

        track.setEnabled(true);
        track.addSink(binding.surfaceView);

        MediaStream mediaStream = factory.createLocalMediaStream("STREAM");
        mediaStream.addTrack(track);

        try {
            server = new WebServer(factory, mediaStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private VideoCapturer createVideoCapturer() {
        VideoCapturer videoCapturer;
        if (Camera2Enumerator.isSupported(this)) {
            videoCapturer = createCameraCapturer(new Camera2Enumerator(this));
        } else {
            videoCapturer = createCameraCapturer(new Camera1Enumerator(true));
        }
        return videoCapturer;
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName) == USE_FRONT_CAMERA) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName) != USE_FRONT_CAMERA) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }
}