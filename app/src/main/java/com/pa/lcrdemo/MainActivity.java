
package com.pa.lcrdemo;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.*;
import com.pa.lcr.LcrSimpleDeliverV2;
import com.pa.lcr.lcp.LcpLink;

import java.util.*;

public class MainActivity extends AppCompatActivity {
  private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

  private TextView log; private EditText edtTo, edtFrom, edtProduct, edtPreset;
  private UsbSerialPort serialPort;

  // Buffer de log pour le bouton "Copier"
  private final StringBuilder logBuf = new StringBuilder(4096);

  private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver(){
    @Override public void onReceive(Context context, Intent intent){
      if (ACTION_USB_PERMISSION.equals(intent.getAction())){
        synchronized(this){
          UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
          if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            append("Permission USB accordée, ouverture...\n");
            connectPort(device);
          } else {
            append("Permission USB refusée\n");
          }
        }
      }
    }
  };

  private final BroadcastReceiver usbAttachDetach = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
      String a = i.getAction();
      if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
        append("USB ATTACHED (branchez puis cliquez Connexion USB)\n");
      } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
        append("USB DETACHED\n");
        try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
        serialPort = null;
      }
    }
  };

  @Override protected void onCreate(Bundle b){
    super.onCreate(b);
    setContentView(R.layout.activity_main);

    log=findViewById(R.id.txtLog);
    edtTo=findViewById(R.id.edtTo);
    edtFrom=findViewById(R.id.edtFrom);
    edtProduct=findViewById(R.id.edtProduct);
    edtPreset=findViewById(R.id.edtPreset);

    registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

    IntentFilter f = new IntentFilter();
    f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    registerReceiver(usbAttachDetach, f);

    // === Log I/O TX/RX ===
    CheckBox switchIoLog = findViewById(R.id.switchIoLog);
    LcpLink.setLogger(this::appendAndBuffer);
    switchIoLog.setOnCheckedChangeListener((buttonView, isChecked) -> {
      LcpLink.DUMP_TX = isChecked;
      LcpLink.DUMP_RX = isChecked;
      append("I/O log " + (isChecked ? "activé" : "désactivé") + "\n");
    });

    findViewById(R.id.btnCopyLog).setOnClickListener(v -> {
      ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
      if (cb != null) {
        cb.setPrimaryClip(ClipData.newPlainText("lcr_log", logBuf.toString()));
        append("Log copié dans le presse-papiers\n");
      }
    });
    findViewById(R.id.btnClearLog).setOnClickListener(v -> {
      logBuf.setLength(0);
      runOnUiThread(() -> log.setText(""));
    });

    findViewById(R.id.btnConnect).setOnClickListener(v -> requestAndOpenFirstPort());
    findViewById(R.id.btnDiag).setOnClickListener(v -> diagRx());
    findViewById(R.id.btnStart).setOnClickListener(v -> startFlow());

    append("Prêt. Branchez le LCR puis cliquez “Connexion USB”.\n");
  }

  @Override protected void onDestroy(){
    super.onDestroy();
    unregisterReceiver(usbPermissionReceiver);
    unregisterReceiver(usbAttachDetach);
    try{ if(serialPort!=null){ serialPort.close(); } }catch(Exception ignored){}
  }

  private void requestAndOpenFirstPort(){
    UsbManager mgr=(UsbManager)getSystemService(Context.USB_SERVICE);
    List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
    if(drivers.isEmpty()){ append("Aucun convertisseur USB-série détecté\n"); return; }
    UsbDevice dev = drivers.get(0).getDevice();
    if(!mgr.hasPermission(dev)){
      PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
      mgr.requestPermission(dev, pi);
      append("Demande de permission USB...\n");
      return;
    }
    connectPort(dev);
  }

  private void connectPort(UsbDevice dev){
    try{
      UsbManager mgr=(UsbManager)getSystemService(Context.USB_SERVICE);
      UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
      if(driver==null){ append("Pas de driver pour ce device\n"); return; }
      UsbDeviceConnection conn = mgr.openDevice(dev);
      if(conn==null){ append("openDevice=null\n"); return; }

      serialPort = driver.getPorts().get(0);
      serialPort.open(conn);
      serialPort.setParameters(19200,8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
      try { serialPort.setDTR(true); } catch(Exception ignored){}
      try { serialPort.setRTS(true); } catch(Exception ignored){}
      serialPort.purgeHwBuffers(true, true);
      try { Thread.sleep(100); } catch (InterruptedException ignored) {}

      append("Port ouvert 19200 8N1 (DTR/RTS=ON)\n");
    }catch(Exception e){
      append("ERREUR ouverture: "+e.getMessage()+"\n");
    }
  }

  private void diagRx(){
    if (serialPort == null){
      append("Diag: port non ouvert — clique d’abord “Connexion USB”.\n");
      return;
    }
    append("Diag: écoute RX 0,5 s...\n");
    new Thread(() -> {
      try {
        byte[] buf = new byte[64];
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 500) {
          int n = serialPort.read(buf, 50);
          if (n > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i=0;i<n;i++) sb.append(String.format("%02X ", buf[i]));
            appendAndBuffer("RX: " + sb.toString() + "\n");
          }
        }
      } catch(Exception e) {
        append("Diag RX: "+e.getMessage()+"\n");
      }
    }).start();
  }

  private void startFlow(){
    try {
      if(serialPort==null){
        append("Port non prêt — clique d’abord “Connexion USB”.\n");
        return;
      }
      LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
      p.port = serialPort;
      p.toAddr = parseHex(edtTo.getText().toString().trim());
      p.fromAddr = parseHex(edtFrom.getText().toString().trim());
      p.product = Integer.parseInt(edtProduct.getText().toString().trim());
      try{ p.preset = Double.parseDouble(edtPreset.getText().toString().trim()); }catch(Exception e){ p.preset = 0.0; }
      p.verbose = true; p.startAcceptFlow = true; p.ticketPost = "if-pending";

      append("Go → unlock/prestart/start...\n");
      new Thread(() -> {
        try {
          LcrSimpleDeliverV2 lcr = new LcrSimpleDeliverV2(p);
          lcr.unlock(); lcr.prestart(); lcr.start();
          Map<String,Object> live = lcr.liveLoop();
          Map<String,Object> fin = lcr.finish(live, null);
          append("FINISH: " + com.pa.lcr.util.SimpleJson.stringify(fin) + "\n");
        } catch (Exception ex) {
          append("ERREUR (thread): "+ ex.getMessage()+"\n");
        }
      }).start();
    } catch (Exception e){
      append("ERREUR (startFlow): "+e.getMessage()+"\n");
    }
  }

  private int parseHex(String s){
    try{ return Integer.decode(s); }catch(Exception e){ return 0; }
  }

  private void append(String s){ runOnUiThread(() -> log.append(s)); }

  private void appendAndBuffer(String s){
    if (s == null) return;
    logBuf.append(s);
    if (!s.endsWith("\n")) logBuf.append("\n");
    append(s.endsWith("\n") ? s : s + "\n");
  }
}
