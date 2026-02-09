
SerialPortController serial =
    new SerialPortController(port.getInputStream(), port.getOutputStream());

LcrService lcr = new LcrService(serial);

new Thread(() -> {
    try {
        byte[] st = lcr.poll();
        runOnUiThread(() -> statusView.setText("POLL=" + bytesToHex(st)));

    } catch (Exception ex) {
        runOnUiThread(() -> statusView.setText("Erreur LCR: " + ex.getMessage()));
    }
}).start();
