package com.extensionbox.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus, tvTier;
    private MaterialButton btnToggle;
    private MaterialSwitch swBattery, swNetwork, swUnlock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViews();
        requestPermissions();
        loadSwitches();
        wireListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void findViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvTier = findViewById(R.id.tvTier);
        btnToggle = findViewById(R.id.btnToggle);
        swBattery = findViewById(R.id.swBattery);
        swNetwork = findViewById(R.id.swNetwork);
        swUnlock = findViewById(R.id.swUnlock);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void loadSwitches() {
        swBattery.setChecked(Prefs.isModuleEnabled(this, "battery", true));
        swNetwork.setChecked(Prefs.isModuleEnabled(this, "network", true));
        swUnlock.setChecked(Prefs.isModuleEnabled(this, "unlock", true));
    }

    private void wireListeners() {
        swBattery.setOnCheckedChangeListener((b, c) ->
                Prefs.setModuleEnabled(this, "battery", c));

        swNetwork.setOnCheckedChangeListener((b, c) ->
                Prefs.setModuleEnabled(this, "network", c));

        swUnlock.setOnCheckedChangeListener((b, c) ->
                Prefs.setModuleEnabled(this, "unlock", c));

        btnToggle.setOnClickListener(v -> toggleService());
    }

    private void toggleService() {
        if (Prefs.isRunning(this)) {
            Intent i = new Intent(this, MonitorService.class);
            i.setAction(MonitorService.ACTION_STOP);
            startService(i);
        } else {
            ContextCompat.startForegroundService(this,
                    new Intent(this, MonitorService.class));
        }

        btnToggle.postDelayed(this::refreshStatus, 500);
    }

    private void refreshStatus() {
        boolean on = Prefs.isRunning(this);
        tvStatus.setText(on ? "● Running" : "○ Stopped");
        tvStatus.setTextColor(on ? 0xFF4CAF50 : 0xFFF44336);
        btnToggle.setText(on ? "⏹  Stop Monitoring" : "▶  Start Monitoring");

        SystemAccess sys = new SystemAccess(this);
        tvTier.setText("🔑 " + sys.getTierName());
    }
}