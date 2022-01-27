package nl.comptex.oprintwebrtccam;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.webrtc.EglBase;

import nl.comptex.oprintwebrtccam.databinding.ActivityMainBinding;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = "MAINACT";
    private Intent intent;
    private ActivityMainBinding binding;
    ComponentName componentName;
    private final String[] perms = new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    private final int PERMISSION_REQUEST_CODE = 125478;
    boolean changingOrientation = false;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int orientation = Integer.parseInt(prefs.getString(this.getString(R.string.orientation_preference), Integer.toString(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)));
        if (getRequestedOrientation() != orientation) {
            setRequestedOrientation(orientation);
            changingOrientation = true;
            return;
        } else {
            changingOrientation = false;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
        if (changingOrientation)
            return;

        if (!EasyPermissions.hasPermissions(this, perms)) {
            EasyPermissions.requestPermissions(this, "Need some permissions", PERMISSION_REQUEST_CODE, perms);
            return;
        }

        startAndBindService();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.d(TAG, "onRestart");
        if (changingOrientation)
            return;
        initAndBindSurfaceView();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        releaseSurfaceView();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
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
        binding.surfaceView.setMirror(service.isUsingFrontFacingCamera());
        service.addSink(binding.surfaceView);
    }

    private void releaseSurfaceView() {
        if (binding != null)
            binding.surfaceView.release();
        if (bound) {
            service.removeSink(binding.surfaceView);
        }
    }
}