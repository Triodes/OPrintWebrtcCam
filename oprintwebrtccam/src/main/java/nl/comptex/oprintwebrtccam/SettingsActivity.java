package nl.comptex.oprintwebrtccam;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.util.Arrays;
import java.util.stream.Stream;

import nl.comptex.oprintwebrtccam.databinding.SettingsActivityBinding;
import nl.comptex.oprintwebrtccam.helpers.CameraHelper;

public class SettingsActivity extends AppCompatActivity {

    SettingsActivityBinding binding;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (WebRTCService.isIsRunning()) {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            return;
        }

        binding = SettingsActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.supportActionBar);

        if (savedInstanceState == null) {
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
            ListPreference pref = this.findPreference(this.getString(R.string.camera_preference));
            String[] cameras = CameraHelper.getCameras(this.getContext());
            pref.setEntries(cameras);
            pref.setEntryValues(cameras);
            Log.d(TAG, "onCreatePreferences: break");
        }
    }
}