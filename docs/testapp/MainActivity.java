package com.example.vuln;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.util.Random;
import javax.crypto.Cipher;

public class MainActivity extends Activity {
    private static final String API_KEY = "AIzaSyA-TestHardcodedSecretValue12345";
    private static final String PASSWORD = "hunter2";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Random random = new Random();
        Log.d("vuln", "token " + random.nextInt() + API_KEY + PASSWORD);
        try {
            Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
            Log.d("vuln", cipher.getAlgorithm());
        } catch (Exception e) {
            Log.e("vuln", "crypto", e);
        }
        getSharedPreferences("creds", MODE_WORLD_READABLE);
    }
}
