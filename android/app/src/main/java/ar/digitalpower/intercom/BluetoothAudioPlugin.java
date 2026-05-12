package ar.digitalpower.intercom;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.IOException;

import android.Manifest;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;

@CapacitorPlugin(
    name = "BluetoothAudio",
    permissions = {
        @Permission(
            alias = "audio",
            strings = { Manifest.permission.RECORD_AUDIO }
        ),
        @Permission(
            alias = "bluetooth",
            strings = { Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH }
        )
    }
)
public class BluetoothAudioPlugin extends Plugin {

    private MediaRecorder mediaRecorder;
    private AudioManager audioManager;
    private String currentAudioPath;

    @Override
    public void load() {
        audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        if (getPermissionState("audio") != PermissionState.GRANTED) {
            requestPermissionForAlias("audio", call, "audioPermsCallback");
            return;
        }
        doStartRecording(call);
    }

    @PermissionCallback
    private void audioPermsCallback(PluginCall call) {
        if (getPermissionState("audio") == PermissionState.GRANTED) {
            doStartRecording(call);
        } else {
            call.reject("Permission is required to record audio");
        }
    }

    private void doStartRecording(PluginCall call) {
        String outputPath = call.getString("outputPath");
        if (outputPath == null) {
            call.reject("Must provide outputPath");
            return;
        }

        try {
            // Activar Bluetooth SCO
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);

            currentAudioPath = outputPath;
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(currentAudioPath);
            mediaRecorder.prepare();
            mediaRecorder.start();

            call.resolve();
        } catch (Exception e) {
            Log.e("BluetoothAudio", "Error starting recording", e);
            call.reject("Error starting recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
            // Desactivar Bluetooth SCO
            if (audioManager != null) {
                audioManager.setBluetoothScoOn(false);
                audioManager.stopBluetoothSco();
            }
            
            JSObject ret = new JSObject();
            ret.put("audioPath", currentAudioPath);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e("BluetoothAudio", "Error stopping recording", e);
            call.reject("Error stopping recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getBatteryLevel(PluginCall call) {
        try {
            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                call.resolve(new JSObject().put("level", -1));
                return;
            }
            
            // Require permissions on Android 12+
            if (android.os.Build.VERSION.SDK_INT >= 31 && 
                androidx.core.content.ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                
                if (getPermissionState("bluetooth") != PermissionState.GRANTED) {
                    requestPermissionForAlias("bluetooth", call, "batteryPermsCallback");
                    return;
                }
            }
            
            doGetBatteryLevel(call, adapter);
        } catch (Exception e) {
            call.resolve(new JSObject().put("level", -1));
        }
    }

    @PermissionCallback
    private void batteryPermsCallback(PluginCall call) {
        if (getPermissionState("bluetooth") == PermissionState.GRANTED) {
            doGetBatteryLevel(call, android.bluetooth.BluetoothAdapter.getDefaultAdapter());
        } else {
            call.resolve(new JSObject().put("level", -1));
        }
    }

    private void doGetBatteryLevel(PluginCall call, android.bluetooth.BluetoothAdapter adapter) {
        try {
            java.util.Set<android.bluetooth.BluetoothDevice> pairedDevices = adapter.getBondedDevices();
            int maxBattery = -1;
            for (android.bluetooth.BluetoothDevice device : pairedDevices) {
                try {
                    java.lang.reflect.Method method = device.getClass().getMethod("getBatteryLevel");
                    int battery = (Integer) method.invoke(device);
                    if (battery > maxBattery && battery <= 100) {
                        maxBattery = battery;
                    }
                } catch (Exception e) {}
            }
            call.resolve(new JSObject().put("level", maxBattery));
        } catch (Exception e) {
            call.resolve(new JSObject().put("level", -1));
        }
    }

    @PluginMethod
    public void openOutputFolder(PluginCall call) {
        android.content.Intent intent = new android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not open Downloads folder: " + e.getMessage());
        }
    }
}
