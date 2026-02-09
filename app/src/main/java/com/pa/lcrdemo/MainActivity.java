
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.concurrent.Executors;

import com.pa.lcr.lcp.*;

public class MainActivity extends AppCompatActivity implements DeliveryController.DeliveryEvents {

    private DeliveryController controller;
    private TextView txtLog;

    @Override
    protected void onCreate(Bundle saved){
        super.onCreate(saved);
        setContentView(R.layout.activity_main);

        txtLog = findViewById(R.id.txtLog);

        LcpLink link = new LcpLink("/dev/ttyS4", 9600);
        controller = new DeliveryController(link, this, Executors.newSingleThreadExecutor());

        Button btnPing = findViewById(R.id.btnPing);
        Button btnResync = findViewById(R.id.btnResync);
        Button btnStart = findViewById(R.id.btnStart);
        Button btnEnd = findViewById(R.id.btnEnd);
        Button btnPrint = findViewById(R.id.btnPrint);

        btnPing.setOnClickListener(v -> controller.pingStatus());
        btnResync.setOnClickListener(v -> controller.resyncGetProductId());
        btnStart.setOnClickListener(v -> controller.startOpenMode(1, 8000, 200));
        btnEnd.setOnClickListener(v -> controller.endGracefully(8000,200));
        btnPrint.setOnClickListener(v -> controller.printTicketText("HELLO\nWORLD\n", 24, 200));
    }

    /* DeliveryEvents callbacks */

    @Override
    public void onStateChanged(DeliveryController.State s){
        append("[STATE] " + s);
    }

    @Override
    public void onFlowStarted(){
        append("[FLOW] started");
    }

    @Override
    public void onFlowStopped(){
        append("[FLOW] stopped");
    }

    @Override
    public void onLiveSample(int ds, int dc, double grossL, double netL){
        append("[LIVE] gross=" + grossL + " net=" + netL);
    }

    @Override
    public void onProgress(DeliveryController.DeliveryProgress p){
        // optional
    }

    @Override
    public void onGuardReached(){
        append("[GUARD] reached");
    }

    @Override
    public void onError(String msg, Throwable t){
        append("[ERROR] " + msg + " " + t);
    }

    @Override
    public void onLog(String line){
        append("[LOG] " + line);
    }

    private void append(String s){
        runOnUiThread(() -> txtLog.append(s + "\n"));
    }
}
