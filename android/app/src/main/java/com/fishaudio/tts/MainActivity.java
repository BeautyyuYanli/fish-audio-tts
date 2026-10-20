package com.fishaudio.tts;

import com.getcapacitor.BridgeActivity;
import android.os.Bundle;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FishTtsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
