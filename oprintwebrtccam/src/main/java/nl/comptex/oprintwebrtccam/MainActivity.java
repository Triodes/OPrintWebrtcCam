package nl.comptex.oprintwebrtccam;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.webrtc.EglBase;

import nl.comptex.oprintwebrtccam.databinding.ActivityMainBinding;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = "MAINACT";
    private static final boolean USE_FRONT_CAMERA = true;
    private Intent intent;
    private ActivityMainBinding binding;
    ComponentName componentName;
    private final String[] perms = new String[]{Manifest.permission.CAMERA};
    private final int PERMISSION_REQUEST_CODE = 125478;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!EasyPermissions.hasPermissions(this, perms)) {
            EasyPermissions.requestPermissions(this, "Need some permissions", PERMISSION_REQUEST_CODE, perms);
            return;
        }

        startAndBindService();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        initAndBindSurfaceView();
    }

    @Override
    protected void onStop() {
        releaseSurfaceView();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        releaseSurfaceView();
        stopService(intent);
        finish();
        super.onBackPressed();
    }

    @AfterPermissionGranted(PERMISSION_REQUEST_CODE)
    private void startAndBindService() {
        intent = new Intent(this, WebRTCService.class);
        componentName = startForegroundService(intent);
        if (!bound)
            bindService(intent, connection, Context.BIND_ABOVE_CLIENT);
        super.onStart();
    }

    private WebRTCService service;
    private boolean bound = false;
    /**
     * Defines callbacks for service binding, passed to bindService()
     */
    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            Log.d(TAG, "got there");
            WebRTCService.LocalBinder localBinder = (WebRTCService.LocalBinder) binder;
            service = localBinder.getService();
            bound = true;
            initAndBindSurfaceView();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            bound = false;
        }
    };

    private void initAndBindSurfaceView() {
        if (!bound)
            return;
        
        EglBase eglBase = service.getEglBase();
        binding.surfaceView.init(eglBase.getEglBaseContext(), null);
        binding.surfaceView.setEnableHardwareScaler(true);
        binding.surfaceView.setMirror(USE_FRONT_CAMERA);
        service.addSink(binding.surfaceView);
    }

    private void releaseSurfaceView() {
        if (!bound)
            return;

        service.removeSink(binding.surfaceView);
        binding.surfaceView.release();
    }
}