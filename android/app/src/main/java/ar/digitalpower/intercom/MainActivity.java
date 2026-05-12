package ar.digitalpower.intercom;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BluetoothAudioPlugin.class);
        registerPlugin(LocalWifiPlugin.class);
        registerPlugin(VideoPreviewPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
