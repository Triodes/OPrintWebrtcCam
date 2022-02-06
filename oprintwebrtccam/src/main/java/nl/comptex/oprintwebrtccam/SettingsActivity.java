package nl.comptex.oprintwebrtccam;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;

import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nl.comptex.oprintwebrtccam.databinding.SettingsActivityBinding;
import nl.comptex.oprintwebrtccam.helpers.CameraHelper;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class SettingsActivity extends AppCompatActivity {

    private final String[] perms = new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    private final int PERMISSION_REQUEST_CODE = 125478;
    private Bundle savedInstanceState;
    SettingsActivityBinding binding;


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        this.savedInstanceState = savedInstanceState;
        super.onCreate(this.savedInstanceState);
        if (WebRTCService.isIsRunning()) {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            return;
        }

        if (!EasyPermissions.hasPermissions(this, perms)) {
            EasyPermissions.requestPermissions(this, "Need some permissions", PERMISSION_REQUEST_CODE, perms);
            return;
        }

        initializeView();
    }

    @AfterPermissionGranted(PERMISSION_REQUEST_CODE)
    private void initializeView() {
        binding = SettingsActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.supportActionBar);

        if (this.savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
    }

    public boolean onPrepareOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.start_stream_menu, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_start_stream) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String camera = prefs.getString(getString(R.string.camera_preference), null);
            if (camera == null) {
                CharSequence text = getString(R.string.must_select_camera);
                int duration = Toast.LENGTH_LONG;

                Toast toast = Toast.makeText(this, text, duration);
                toast.show();
                return false;
            }
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private static final String TAG = "SettingsFragment";

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            ListPreference cameraPref = this.findPreference(this.getString(R.string.camera_preference));
            String[] cameras = CameraHelper.getCameras(this.getContext());
            String[] cameraEntries = new String[cameras.length];

            for (int i = 0; i < cameras.length; i++) {
                cameraEntries[i] = "Camera " + (i+1) + ", " + (CameraHelper.isFrontFacing(this.getContext(), cameras[i]) ? "Front facing" : "Rear facing");
            }

            cameraPref.setEntries(cameraEntries);
            cameraPref.setEntryValues(cameras);

            String selectedCamera = cameraPref.getValue();
            if (cameras.length > 0 && !Arrays.asList(cameras).contains(selectedCamera))
                cameraPref.setValue(cameras[0]);
            updateResolutionPreference(cameraPref.getValue());

            cameraPref.setOnPreferenceChangeListener((preference, newValue) -> {
                updateResolutionPreference((String) newValue);
                return true;
            });

            findPreference(getString(R.string.resolution_preference)).setOnPreferenceChangeListener((preference, newValue) -> {
                updateFrameRatePreference(cameraPref.getValue(), (String) newValue);
                return true;
            });
        }

        private void updateResolutionPreference(String selectedCamera) {
            ListPreference resolutionPref = findPreference(getString(R.string.resolution_preference));
            List<CaptureFormat> captureFormats = CameraHelper.getSupportedFormats(this.getContext(), selectedCamera);

            ArrayList<String> resolutions = new ArrayList<>(captureFormats.size());
            for (CaptureFormat format : captureFormats) {
                String resolution = format.width + "x" + format.height;
                if (!resolutions.contains(resolution))
                    resolutions.add(resolution);
            }

            CharSequence[] resolutionCharSeqs = new CharSequence[resolutions.size()];
            resolutionCharSeqs = resolutions.toArray(resolutionCharSeqs);
            resolutionPref.setEntries(resolutionCharSeqs);
            resolutionPref.setEntryValues(resolutionCharSeqs);

            if (!resolutions.contains(resolutionPref.getValue())) {
                resolutionPref.setValue(resolutions.get(resolutions.size() / 2));
            }
            updateFrameRatePreference(selectedCamera, resolutionPref.getValue());
        }

        private void updateFrameRatePreference(String selectedCamera, String resolution) {
            String[] wh = resolution.split("x");
            int width = Integer.parseInt(wh[0]);
            int height = Integer.parseInt(wh[1]);

            List<CaptureFormat> captureFormats = CameraHelper.getSupportedFormats(this.getContext(), selectedCamera);

            SeekBarPreference frameratePreference = findPreference(getString(R.string.framerate_preference));
            for (CaptureFormat format : captureFormats) {
                if (format.width == width && format.height == height) {
                    frameratePreference.setMin(format.framerate.min/1000);
                    frameratePreference.setMax(format.framerate.max/1000);
                    frameratePreference.setEnabled(frameratePreference.getMin() != frameratePreference.getMax());
                }
            }

            frameratePreference.setValue(Math.min(Math.max(frameratePreference.getMin(), frameratePreference.getValue()), frameratePreference.getMax()));
        }
    }
}