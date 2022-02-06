package nl.comptex.oprintwebrtccam;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.webrtc.EglBase;

import nl.comptex.oprintwebrtccam.databinding.ActivityMainBinding;
import nl.comptex.oprintwebrtccam.helpers.EglBaseSingleton;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = "MAINACT";
    private Intent intent;
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: " + hashCode());

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int preferredOrientation = Integer.parseInt(prefs.getString(this.getString(R.string.orientation_preference), Integer.toString(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)));
        setRequestedOrientation(preferredOrientation);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart: " + hashCode());

        startAndBindService();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.d(TAG, "onRestart: " + hashCode());
        initAndBindSurfaceView();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop: " + hashCode());
        releaseSurfaceView();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy: " + hashCode());
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

    private void startAndBindService() {
        intent = new Intent(this, WebRTCService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

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
            if (MainActivity.this.isDestroyed())
                return;
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
        Log.d(TAG, "initAndBindSurfaceView: ");
        if (!bound)
            return;
        
        EglBase eglBase = EglBaseSingleton.getEglBase();
        binding.surfaceView.init(eglBase.getEglBaseContext(), null);
        binding.surfaceView.setEnableHardwareScaler(true);
        binding.surfaceView.setMirror(service.isUsingFrontFacingCamera());
        service.addSink(binding.surfaceView);
    }

    private void releaseSurfaceView() {
        Log.d(TAG, "releaseSurfaceView: ");
        binding.surfaceView.release();
        if (bound) {
            service.removeSink(binding.surfaceView);
        }
    }
}