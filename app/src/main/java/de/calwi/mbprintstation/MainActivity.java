package de.calwi.mbprintstation;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.*;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String BASE_URL = "https://pos.calwi.de";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TextView logView;
    private Spinner printerSpinner;
    private Button startButton, stopButton, testButton, refreshButton;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean pollingNow = false;
    private ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private String selectedMac = "";
    private SharedPreferences prefs;

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (!running) return;

            if (!pollingNow) {
                pollingNow = true;
                new Thread(() -> {
                    try {
                        pollPrintJobs();
                    } finally {
                        pollingNow = false;
                    }
                }).start();
            }

            handler.postDelayed(this, 4000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("mbps", MODE_PRIVATE);
        selectedMac = prefs.getString("printer_mac", "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("MB Print Station\nGOOJPRT 58mm Bluetooth");
        title.setTextSize(22);
        title.setPadding(0,0,0,20);
        root.addView(title);

        printerSpinner = new Spinner(this);
        root.addView(printerSpinner);

        refreshButton = new Button(this);
        refreshButton.setText("Bluetooth Drucker neu laden");
        root.addView(refreshButton);

        testButton = new Button(this);
        testButton.setText("Testbon drucken");
        root.addView(testButton);

        startButton = new Button(this);
        startButton.setText("Auto-Druck starten");
        root.addView(startButton);

        stopButton = new Button(this);
        stopButton.setText("Auto-Druck stoppen");
        root.addView(stopButton);

        logView = new TextView(this);
        logView.setTextSize(14);
        logView.setMovementMethod(new ScrollingMovementMethod());
        root.addView(logView, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        requestPerms();
        loadPrinters();

        refreshButton.setOnClickListener(v -> loadPrinters());
        testButton.setOnClickListener(v -> {
            log("Testbon Button gedrückt.");
            Toast.makeText(this, "Testbon wird gesendet...", Toast.LENGTH_SHORT).show();
            new Thread(() -> printText("        TESTBON\n------------------------------\nMB Print Station\nGOOJPRT 58mm\n------------------------------\n\n\n")).start();   
        });
        startButton.setOnClickListener(v -> startPolling());
        stopButton.setOnClickListener(v -> stopPolling());

        printerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < devices.size()) {
                    selectedMac = devices.get(pos).getAddress();
                    prefs.edit().putString("printer_mac", selectedMac).apply();
                    log("Drucker gewählt: " + devices.get(pos).getName() + " / " + selectedMac);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void requestPerms() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 10);
            }
        }
    }

    private void loadPrinters() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) { log("Kein Bluetooth Adapter gefunden."); return; }
            if (!adapter.isEnabled()) { log("Bluetooth ist ausgeschaltet."); return; }

            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            devices.clear();
            ArrayList<String> names = new ArrayList<>();
            int selectedIndex = 0;
            int idx = 0;

            for (BluetoothDevice d : bonded) {
                devices.add(d);
                String name = d.getName() == null ? "(ohne Name)" : d.getName();
                names.add(name + " - " + d.getAddress());
                if (d.getAddress().equals(selectedMac)) selectedIndex = idx;
                idx++;
            }

            ArrayAdapter<String> aa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
            printerSpinner.setAdapter(aa);
            if (!devices.isEmpty()) printerSpinner.setSelection(selectedIndex);
            log("Gekoppelte Geräte geladen: " + devices.size());
        } catch (Exception e) {
            log("Fehler Geräte laden: " + e.getMessage());
        }
    }

    private void startPolling() {
        if (running) {
            log("Auto-Druck läuft bereits.");
            return;
        }

        if (selectedMac == null || selectedMac.isEmpty()) {
            log("Bitte zuerst Drucker auswählen.");
            return;
        }

        running = true;
        pollingNow = false;
        log("Auto-Druck gestartet.");
        handler.post(poller);
    }

    private void stopPolling() {
        running = false;
        log("Auto-Druck gestoppt.");
    }

    private void pollPrintJobs() {
        try {
            String json = httpGet(BASE_URL + "/api/print_jobs_raw");
            JSONArray arr = new JSONArray(json);
            if (arr.length() == 0) return;

            log("Druckaufträge: " + arr.length());

            for (int i=0; i<arr.length(); i++) {
                JSONObject job = arr.getJSONObject(i);
                int id = job.getInt("id");
                String text = job.getString("text");
                boolean ok = printText(text + "\n\n\n");
              
                if (ok) {
                    httpPost(BASE_URL + "/api/print_job_done/" + id);
                    log("Gedruckt Job #" + id);
                } else {
                    log("Job NICHT als gedruckt markiert: #" + id);
                }
            }
        } catch (Exception e) {
            log("Polling Fehler: " + e.getMessage());
        }
    }

    private boolean printText(String text) {
        BluetoothSocket socket = null;
        OutputStream out = null;
        boolean dataSent = false;
            
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
                Thread.sleep(500);
            }
    
            BluetoothDevice device = adapter.getRemoteDevice(selectedMac);
    
            socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
    
            out = socket.getOutputStream();
    
            String safe = text
                    .replace("€", "EUR")
                    .replace("ä", "ae")
                    .replace("ö", "oe")
                    .replace("ü", "ue")
                    .replace("Ä", "Ae")
                    .replace("Ö", "Oe")
                    .replace("Ü", "Ue")
                    .replace("ß", "ss");
    
            out.write(new byte[]{0x1B, 0x40});
            out.write(new byte[]{0x1B, 0x74, 0x10});
            out.write(safe.getBytes(Charset.forName("US-ASCII")));
            out.write(new byte[]{0x0A, 0x0A, 0x0A, 0x0A});
            out.flush();
            dataSent = true;

            log("Daten an Drucker gesendet / Zeichen: " + text.length());

            try {
                Thread.sleep(1200);
            } catch (Exception ignored) {}

            return dataSent;
    
        } catch (Exception e) {
            log("Druckfehler: " + e.getMessage());
            return false;
    
        } finally {
            try {
                if (out != null) out.flush();
            } catch (Exception ignored) {}
    
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {}
    
            try {
                Thread.sleep(700);
            } catch (Exception ignored) {}
        }
    }

    private String httpGet(String u) throws Exception {
        URL url = new URL(u);
        HttpURLConnection con = (HttpURLConnection)url.openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestMethod("GET");
        java.io.InputStream is = con.getInputStream();
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private void httpPost(String u) throws Exception {
        URL url = new URL(u);
        HttpURLConnection con = (HttpURLConnection)url.openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.getOutputStream().write(new byte[0]);
        con.getInputStream().close();
    }

    private void log(String m) {
        runOnUiThread(() -> {
            logView.append(m + "\n");
            final int scrollAmount = logView.getLayout() == null ? 0 : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
            if (scrollAmount > 0) logView.scrollTo(0, scrollAmount);
        });
    }
}
