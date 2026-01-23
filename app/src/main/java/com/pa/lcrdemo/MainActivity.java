package com.pa.lcrdemo;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.*;
import com.pa.lcr.LcrSimpleDeliverV2;

import java.util.*;

public class MainActivity extends AppCompatActivity {
  private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";
  private TextView log; private EditText edtTo, edtFrom, edtProduct, edtPreset;
  private UsbSerialPort serialPort;

  private final BroadcastReceiver usbReceiver = new BroadcastReceiver(){
    @Override public void onReceive(Context context, Intent intent){
      if (ACTION_USB_PERMISSION.equals(intent.getAction())){
        synchronized(this){ UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
          if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connectPort(device);
          else append("Permission USB refusée
");
        }
      }
    }
  };

  @Override protected void onCreate(Bundle b){
    super.onCreate(b); setContentView(R.layout.activity_main);
    log=findViewById(R.id.txtLog); edtTo=findViewById(R.id.edtTo); edtFrom=findViewById(R.id.edtFrom); edtProduct=findViewById(R.id.edtProduct); edtPreset=findViewById(R.id.edtPreset);
    registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));

    findViewById(R.id.btnStart).setOnClickListener(v -> startFlow());
  }

  @Override protected void onDestroy(){ super.onDestroy(); unregisterReceiver(usbReceiver); try{ if(serialPort!=null){ serialPort.close(); } }catch(Exception ignored){} }

  private void startFlow(){ try {
      if(serialPort==null){ requestAndOpenFirstPort(); return; }
      LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
      p.port = serialPort;
      p.toAddr = parseHex(edtTo.getText().toString().trim());
      p.fromAddr = parseHex(edtFrom.getText().toString().trim());
      p.product = Integer.parseInt(edtProduct.getText().toString().trim());
      try{ p.preset = Double.parseDouble(edtPreset.getText().toString().trim()); }catch(Exception e){ p.preset = 0.0; }
      p.verbose = true; p.startAcceptFlow = true; p.ticketPost = "if-pending";
      append("Connexion ouverte, lancement...
");
      new Thread(() -> {
        try {
          LcrSimpleDeliverV2 lcr = new LcrSimpleDeliverV2(p);
          lcr.unlock(); lcr.prestart(); lcr.start();
          Map<String,Object> live = lcr.liveLoop();
          Map<String,Object> fin = lcr.finish(live, null);
          append("
FINISH: " + com.pa.lcr.util.SimpleJson.stringify(fin) + "
");
        } catch (Exception ex) {
          append("ERREUR: "+ ex.getMessage()+"
");
        }
      }).start();
    } catch (Exception e){ append("ERREUR: "+e.getMessage()+"
"); }
  }

  private void requestAndOpenFirstPort(){ UsbManager mgr=(UsbManager)getSystemService(Context.USB_SERVICE);
    List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
    if(drivers.isEmpty()){ append("Aucun convertisseur USB-série détecté
"); return; }
    UsbDevice dev = drivers.get(0).getDevice();
    if(!mgr.hasPermission(dev)){
      PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
      mgr.requestPermission(dev, pi); append("Demande de permission USB...
");
      return;
    }
    connectPort(dev);
  }

  private void connectPort(UsbDevice dev){ try{
      UsbManager mgr=(UsbManager)getSystemService(Context.USB_SERVICE);
      UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
      if(driver==null){ append("Pas de driver pour ce device
"); return; }
      UsbDeviceConnection conn = mgr.openDevice(dev);
      if(conn==null){ append("openDevice=null
"); return; }
      serialPort = driver.getPorts().get(0);
      serialPort.open(conn); serialPort.setParameters(19200,8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
      append("Port ouvert 19200 8N1
");
    }catch(Exception e){ append("ERREUR ouverture: "+e.getMessage()+"
"); }
  }

  private int parseHex(String s){ try{ return Integer.decode(s); }catch(Exception e){ return 0; } }
  private void append(String s){ runOnUiThread(() -> log.append(s)); }
}
