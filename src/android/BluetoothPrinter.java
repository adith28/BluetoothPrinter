package com.ru.cordova.printer.bluetooth;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Set;
import java.util.UUID;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Bitmap.Config;
import android.util.Xml.Encoding;
import android.util.Base64;
import java.util.ArrayList;
import java.util.List;

public class BluetoothPrinter extends CordovaPlugin {

    private static final String LOG_TAG = "BluetoothPrinter";
    BluetoothAdapter mBluetoothAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    Thread workerThread;
    byte[] readBuffer;
    int readBufferPosition;
    int counter;
    volatile boolean stopWorker;
    Bitmap bitmap;

    public BluetoothPrinter() {
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("list")) {
            listBT(callbackContext);
            return true;
        } else if (action.equals("connect")) {
            String name = args.getString(0);
            if (findBT(callbackContext, name)) {
                try {
                    connectBT(callbackContext);
                } catch (IOException e) {
                    Log.e(LOG_TAG, e.getMessage());
                    e.printStackTrace();
                }
            } else {
                callbackContext.error("Bluetooth Device Not Found: " + name);
            }
            return true;
        } else if (action.equals("disconnect")) {
            try {
                disconnectBT(callbackContext);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        } else if (action.equals("print") || action.equals("printImage")) {
            try {
                String msg = args.getString(0);
                printImage(callbackContext, msg);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        } else if (action.equals("printText")) {
            try {
                String msg = args.getString(0);
                printText(callbackContext, msg);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        } else if (action.equals("printPOSCommand")) {
            try {
                String msg = args.getString(0);
                printPOSCommand(callbackContext, hexStringToBytes(msg));
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        }
        return false;
    }

    //This will return the array list of paired bluetooth printers
    void listBT(CallbackContext callbackContext) {
        BluetoothAdapter mBluetoothAdapter = null;
        String errMsg = null;
        try {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter == null) {
                errMsg = "No bluetooth adapter available";
                Log.e(LOG_TAG, errMsg);
                callbackContext.error(errMsg);
                return;
            }
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                this.cordova.getActivity().startActivityForResult(enableBluetooth, 0);
            }
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                JSONArray json = new JSONArray();
                for (BluetoothDevice device : pairedDevices) {
                    /*
                     Hashtable map = new Hashtable();
                     map.put("type", device.getType());
                     map.put("address", device.getAddress());
                     map.put("name", device.getName());
                     JSONObject jObj = new JSONObject(map);
                     */
                    json.put(device.getName());
                }
                callbackContext.success(json);
            } else {
                callbackContext.error("No Bluetooth Device Found");
            }
            //Log.d(LOG_TAG, "Bluetooth Device Found: " + mmDevice.getName());
        } catch (Exception e) {
            errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
    }

    // This will find a bluetooth printer device
    boolean findBT(CallbackContext callbackContext, String name) {
        try {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter == null) {
                Log.e(LOG_TAG, "No bluetooth adapter available");
            }
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                this.cordova.getActivity().startActivityForResult(enableBluetooth, 0);
            }
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getName().equalsIgnoreCase(name)) {
                        mmDevice = device;
                        return true;
                    }
                }
            }
            Log.d(LOG_TAG, "Bluetooth Device Found: " + mmDevice.getName());
        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    // Tries to open a connection to the bluetooth printer device
    boolean connectBT(CallbackContext callbackContext) throws IOException {
        try {
            // Standard SerialPortService ID
            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
            mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
            mmSocket.connect();
            mmOutputStream = mmSocket.getOutputStream();
            mmInputStream = mmSocket.getInputStream();
            beginListenForData();
            //Log.d(LOG_TAG, "Bluetooth Opened: " + mmDevice.getName());
            callbackContext.success("Bluetooth Opened: " + mmDevice.getName());
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    // After opening a connection to bluetooth printer device,
    // we have to listen and check if a data were sent to be printed.
    void beginListenForData() {
        try {
            final Handler handler = new Handler();
            // This is the ASCII code for a newline character
            final byte delimiter = 10;
            stopWorker = false;
            readBufferPosition = 0;
            readBuffer = new byte[1024];
            workerThread = new Thread(new Runnable() {
                public void run() {
                    while (!Thread.currentThread().isInterrupted() && !stopWorker) {
                        try {
                            int bytesAvailable = mmInputStream.available();
                            if (bytesAvailable > 0) {
                                byte[] packetBytes = new byte[bytesAvailable];
                                mmInputStream.read(packetBytes);
                                for (int i = 0; i < bytesAvailable; i++) {
                                    byte b = packetBytes[i];
                                    if (b == delimiter) {
                                        byte[] encodedBytes = new byte[readBufferPosition];
                                        System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                        /*
                                         final String data = new String(encodedBytes, "US-ASCII");
                                         readBufferPosition = 0;
                                         handler.post(new Runnable() {
                                         public void run() {
                                         myLabel.setText(data);
                                         }
                                         });
                                         */
                                    } else {
                                        readBuffer[readBufferPosition++] = b;
                                    }
                                }
                            }
                        } catch (IOException ex) {
                            stopWorker = true;
                        }
                    }
                }
            });
            workerThread.start();
        } catch (NullPointerException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //This will send data to bluetooth printer
    boolean printText(CallbackContext callbackContext, String msg) throws IOException {
        try {
            mmOutputStream.write(msg.getBytes());
            // tell the user data were sent
            //Log.d(LOG_TAG, "Data Sent");
            callbackContext.success("Data Sent");
            return true;

        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    //This will send data to bluetooth printer
    boolean printImage(CallbackContext callbackContext, String msg) throws IOException {
        try {

            final String encodedString = msg;
            final String pureBase64Encoded = encodedString.substring(encodedString.indexOf(",") + 1);
            final byte[] decodedBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT);

            Bitmap decodedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

            bitmap = decodedBitmap;
            int mWidth = bitmap.getWidth();
            int mHeight = bitmap.getHeight();

            bitmap = resizeImage(bitmap, 48 * 8, mHeight);
	    byte[] formats	= {(byte) 0x1B, (byte) 0x58, (byte) 0x31, (byte) 0x35, (byte) 0x118};		
	    byte[] image	= StringUtil.hexStringToBytes("89 50 4e 47 0d 0a 1a 0a 00 00 00 0d 49 48 44 52 00 00 00 fa 00 00 00 c8 08 06 00 00 00 c1 5e fc 9b 00 00 1d a0 49 44 41 54 78 5e ed dd 07 b4 e5 49 51 06 f0 c5 04 26 14 31 c7 31 61 ce 59 01 07 10 15 c5 88 98 15 8c a8 98 11 15 e3 60 c2 9c 30 a3 b8 08 2a 2a 66 cc 69 51 82 39 61 56 74 4c 88 39 03 66 eb 37 be e6 f4 36 ff 7b df 7d 6f 7a dd de bd 5f 9f 53 67 ee bb a1 6f f7 d7 fd 55 55 57 55 df b9 c5 35 69 41 20 08 dc ec 11 b8 c5 cd 7e 86 99 60 10 08 02 d7 84 e8 d9 04 41 e0 08 10 08 d1 8f 60 91 33 c5 20 10 a2 67 0f 04 81 23 40 20 44 3f 82 45 ce 14 83 40 88 9e 3d 10 04 8e 00 81 10 fd 08 16 39 53 0c 02 21 7a f6 40 10 38 02 04 42 f4 23 58 e4 4c 31 08 84 e8 d9 03 41 e0 08 10 08 d1 8f 60 91 33 c5 20 10 a2 67 0f 04 81 23 40 20 44 3f 82 45 ce 14 83 40 88 9e 3d 10 04 8e 00 81 10 fd 08 16 39 53 0c 02 21 7a f6 40 10 38 02 04 42 f4 23 58 e4 4c 31 08 84 e8 d9 03 41 e0 08 10 08 d1 8f 60 91 33 c5 20 10 a2 67 0f 04 81 23 40 20 44 3f 82 45 ce 14 83 40 88 9e 3d 10 04 8e 00 81 10 fd 08 16 39 53 0c 02 21 7a f6 40 10 38 02 04 42 f4 23 58 e4 4c 31 08 84 e8 d9 03 41 e0 08 10 08 d1 8f 60 91 33 c5 20 10 a2 67 0f 04 81 23 40 20 44 3f 82 45 ce 14 83 40 88 9e 3d 10 04 8e 00 81 10 fd 08 16 39 53 0c 02 21 7a f6 40 10 38 02 04 42 f4 23 58 e4 4c 31 08 84 e8 d9 03 41 e0 08 10 08 d1 8f 60 91 33 c5 20 10 a2 67 0f 04 81 23 40 20 44 3f 82 45 ce 14 83 40 88 9e 3d 10 04 8e 00 81 b3 12 fd 5b 0a 93 3f 2d f9 84 1d d8 3c b2 9e 7f 74 c9 23 26 62 f7 82 d5 d7 c7 94 bc 4b c9 f3 95 3c a6 e4 8b 4b 7e ee 1c df f1 b5 f5 99 bf 29 f9 e4 73 7c 36 1f 09 02 37 59 04 66 13 fd ae 85 c4 1f 95 fc c1 81 88 fc eb 09 81 7f 68 c7 fb 6f 53 cf ff 58 c9 ef 94 7c 45 c9 3f 97 bc 45 c9 e7 94 bc 6d c9 75 07 7e 4f 7b 5b 88 7e 46 c0 f2 f6 9b 07 02 b3 89 7e 56 54 4e 23 fa a7 54 87 6f 5f 72 fb 92 7f ef 3a 7f 40 3d 7e 8f 92 d7 2d f9 cf 8d 2f 35 af ff d9 78 fe 6a 88 fe 2c d5 df 7f 9f 75 82 79 7f 10 58 01 81 d9 44 ff e9 9a d4 c3 4b 1e 72 32 39 44 44 d6 37 2e f9 e3 92 af 2b b9 b6 e4 5d 4b b8 f9 ad fd 64 3d b8 cb 00 88 b1 fd 5e c9 83 4a 1e 3a bc 76 a1 fe e6 39 e8 ff 57 4a de b7 e4 c3 4a be af e4 03 4b 3e a4 84 27 e0 31 b7 5f f3 7d af 54 72 b9 a4 b9 ee 8e 02 9f 58 f2 9e 25 ff 55 f2 ad 25 bc 05 0a e8 55 4b 7e b3 e4 be 25 ef 5f f2 bd 25 9f 79 d2 57 fb 87 77 f1 b9 25 17 4b fe 65 78 2d 7f 06 81 65 10 b8 21 89 ce ed fe ed 92 4b 25 df 5f 72 bb 92 6f 2b f9 a0 93 bf 9f ad fe fd fb 12 96 f9 07 4b 46 cb 8c 84 ff 50 72 e7 92 9f 1a 10 7b 8e fa fb df 4a de f1 84 80 88 fe b0 92 8f 2d f9 9e 92 bf 2e b9 53 09 a5 f3 01 25 94 c1 9b 94 7c 7d 89 f3 3d a2 fb fe ef 2a b9 5c f2 a5 25 cf 59 f2 45 25 8f 2f f9 8c 92 46 f4 6f a8 c7 5f 58 e2 6c 4f fa f6 d2 f5 c7 5b 95 78 4f ac fd 00 4e fe 5c 07 81 1b 92 e8 ac a7 b3 b5 7f 59 66 8d 0b fe d4 92 5f 3e f9 7b 9f eb fe 22 f5 9e a7 94 bc 7e c9 2f 6d 40 f6 67 f5 dc fd 4b 58 61 44 f7 f8 35 ba f7 fd 70 3d e6 61 b0 d0 ad 7d 63 3d 78 72 09 a2 df b1 e4 db 4b 5e b6 e4 69 dd f8 04 13 05 00 29 26 16 fd 42 09 6f 24 2d 08 dc 64 11 b8 21 89 ee 4c fb d9 25 f7 2a e1 36 73 cf af 2b e9 5d dc 7d 44 6f 16 9d 4b ef b3 7d 7b f6 fa c3 99 fd 9d 4a 58 70 44 ff c8 12 4a 41 33 2f de c2 bb 95 fc 48 f7 c1 fe 8c 7e 9f 7a fe c1 25 7f d5 bd ce ca 53 30 2f 56 f2 02 25 88 fe 12 25 94 43 5a 10 b8 c9 22 70 43 12 bd 81 c2 bd 7d d3 92 bb 95 dc a1 e4 9d 4b 7e f5 e4 c5 7d 44 6f 67 74 67 60 ae 71 df 2e d4 1f ce e8 cd da 8f 44 a7 64 b8 fd f7 28 71 56 6f ad 27 ba 73 bc b3 37 f7 7f 6c c8 df 2c 7a 88 7e 93 dd de 19 78 43 e0 86 24 fa eb d4 97 70 a5 bf a9 83 fb 6b ea f1 d3 4b 3e fa e4 b9 7f aa 7f 59 dd 5d e9 b5 4f ab d7 de ae e4 cd 4a fa a8 3b 37 fd fd 4a 5e fb e4 f9 91 e8 ba 67 c9 7f a2 e4 f3 bb ef ef 5d 77 9e 02 37 fd e5 4b 7a 8b fd 3c f5 37 af a3 9d d1 43 f4 f0 e5 26 8f c0 79 88 ce 25 e6 92 f7 0d 79 ff ae a4 8f ba 23 ca 2f 94 08 86 09 a6 bd 70 09 cb 8c f8 72 e2 1a cb fe dd 25 02 69 97 37 d0 e4 3e ff 78 09 17 fa cb 4b 28 06 c1 39 c1 31 96 b8 59 eb 2d a2 4b cb 89 d6 fb fe 5f 2f 11 1f f0 fd 5f 50 d2 82 71 3f 50 8f 8d 5d 66 c0 f8 45 df 29 15 7d 1f 42 74 de ca 5b 9e f4 bb 95 ce db 98 52 9e 0a 02 ff ff 08 9c 87 e8 a2 e4 63 fb aa 7a 42 1a 6a 4c af bd 79 3d f7 71 25 6f 50 c2 6a aa ac fb b2 92 ff 38 e9 00 19 45 bc 11 99 e5 de 6a 14 44 ab 8c 13 c9 57 19 f7 25 25 8f ed de bc 45 74 73 fb e0 93 cf 4a 9d 89 13 dc b6 c4 71 a1 a5 d7 5e a8 1e 4b af a9 ba e3 ee b3 f0 52 68 c6 7a 08 d1 15 08 09 f6 99 a7 20 63 5a 10 58 12 81 b3 12 7d c9 49 64 50 41 20 08 ec 47 20 44 cf 0e 09 02 47 80 40 88 7e 04 8b 9c 29 06 81 10 3d 7b 20 08 1c 01 02 67 25 ba 60 5a 0b c6 09 a8 09 a2 49 8d 29 2b 1d cb 43 c1 27 07 2e 82 fd ea 25 63 89 ab 3c f6 57 77 18 ab 9e 7b 42 89 88 fa 6f 74 cf 2b 4d fd d0 12 11 f1 97 2c 11 a9 17 4d ff 8e 92 3e d2 ad 2c 56 6d bb c0 9c 1c b8 b2 57 35 f7 ca 6e db fb 5e af 1e ff e2 f0 9d ca 6f 15 ce fc 61 f7 fc 38 b6 ee a5 6b 5e b1 fe d8 ba 9d f7 0a f5 bc 12 dc 8b 25 0a 7a 1e 57 f2 79 25 ca 80 fb 26 f0 27 48 a8 4e 5f e5 a0 fa fc af 2c 11 24 1c db 3e fc ac 85 80 a1 6c c2 58 67 af 6a f0 7e 25 e6 fe ac 25 b0 77 cf 40 91 50 df 5a f5 62 9b d3 88 4f ff de 77 3f e9 6f dc 03 32 1a b2 1f 02 a4 7d f1 51 ff 59 7b c3 77 7f e7 f0 fd f0 51 9c 64 cd da 77 cb 64 b8 0a dd 9a f5 f2 da ae a6 04 f9 47 4b 8c c3 3a 5a f3 36 67 38 d8 0b ad f2 b1 ef 43 8d c5 a3 4a 04 66 fb ea 49 ef c1 0b 19 a3 9f 2d f9 f0 33 8e 45 4a 77 eb a2 95 6e 64 8e 3e 6a 98 88 35 b0 a7 ed dd 6f ee 5e 7b fe 7a 6c ee ca b8 1f 58 32 ae cd 5f d6 73 2a 46 65 b0 76 a5 a7 75 a7 86 e5 fe e7 21 ba 14 97 0d a8 a9 22 bb 54 22 45 a5 4a ad 6f cf 5d 7f 28 6a 79 de 12 29 a8 9f 19 5e 47 26 d5 6c 2e 86 68 26 86 cc 2e a7 c8 8f ff 49 89 05 73 b7 5d 74 5c 4e fd 2f 4a 5e a5 44 e4 de c6 12 ed d7 cc 43 31 8c cf 49 95 d9 28 48 20 95 46 99 f8 57 6b 60 bd 61 3d fe f3 12 51 78 79 7c 20 1b a3 cd a2 8d 63 3b 79 fa ca 3f 36 f3 b8 90 2f 5e cf 21 b6 d4 a1 92 5c 8a 45 81 d0 a5 12 19 87 df 3f e9 e0 23 ea 5f ca 40 1d c1 af 95 bc 68 c9 a7 97 48 ed bd f7 c9 e7 da 77 9d 86 5f 23 1c 5c c6 cb 36 5b 44 d7 af 39 f6 05 44 bb 88 de f0 e9 e7 fd 8f f5 07 65 e4 7b 5b 8a 15 ee 88 8a 2c d6 0f e9 fa 7a 87 f6 f9 ab 21 ba 3d 66 1f 68 94 24 0c 65 39 5a 33 16 44 de 22 ba f7 b4 ea c9 7e 2e 1e 23 c7 5b 9f 8c 7d 24 ba 35 93 41 62 cc 28 1e c5 57 da 21 63 31 7f fb 43 5a 57 19 76 df 8c d3 78 c7 f6 f1 f5 84 79 a9 3d 69 06 d3 9a 52 46 2e 84 e1 5c db bb d2 bf b2 42 b7 2c 51 80 46 b1 21 33 23 39 b6 d7 aa 27 28 ab 7b 9d 87 e8 e3 0f 4f b8 2c e2 22 88 fa f0 bf ed be 49 ea ec b3 4a 94 a8 2a 67 1d 35 19 32 21 f5 6b 76 9f 91 e2 92 36 93 5b 47 4e 7d 20 0e 8b d3 17 b5 58 3c 0a 00 f0 ac 99 34 97 9c 38 ed 7d b9 eb 4f ce 9d 86 65 6d 9f 54 b2 cb 6a f0 22 de a8 c4 06 72 39 65 6b 6c 5d b7 cf f4 90 45 fa a4 12 4a a8 f7 32 68 68 a4 f3 43 1d bc 11 5e 03 0f 87 17 d1 9a 8d a4 96 7e 5c ac d3 f0 43 38 f3 51 ab af 42 b0 29 29 fd 6e 11 9d c7 64 03 5a 2f 1b 47 db 45 f4 d1 aa f6 13 f6 bd e3 1e a0 e8 28 4e f8 21 c8 d8 ae 86 e8 7d 5f 0d e7 57 de f8 8e 2d a2 ab df b0 3f 60 d9 37 1e e6 13 4f c6 aa b0 6a 24 ba bf 79 88 14 1e 6f 8b 67 34 b6 5d 63 69 de c4 2e 05 b3 d1 d5 35 cf 55 4f 32 14 4a bd 79 62 38 c1 10 f8 cd 85 b6 57 76 ed 5d 5c 61 d9 71 6d 6c f6 35 8f f8 be 33 88 8e 20 b4 86 8d 6c b1 5b b3 c9 b9 24 06 8f 6c 2f 53 c2 2a b4 b6 8b 4c dc 2f 60 51 02 ac b1 fa 73 37 de fa c6 7a 70 7f 7d 87 ef 94 8b a7 4c 54 cb f5 cd fc da 06 e0 ba ee 02 8b 22 60 75 69 40 ef 3f 2b d1 b9 b5 c6 f0 6a 25 bd b2 43 00 40 53 32 2c 36 4f e8 42 c9 e8 11 38 9a 38 6a c0 b1 b5 d3 f0 6b 84 83 85 f1 b3 4e ad 3e 61 8b e8 ee 1c a8 69 a0 78 79 01 da 2c a2 eb 8b b2 a2 ec 7a f7 b3 cd e5 c6 22 3a ab aa 48 ca ba fc 56 87 2d 52 30 1e ac 2b cf b1 27 7a 5b 2f 6b ca 2b 7c 9b 92 51 51 e8 6a 26 d1 f5 77 a7 12 5c a1 f0 ad 8f 7d f4 3e 25 e3 b1 73 54 c2 8e b0 f6 2e ec c7 c6 43 a0 d4 9f 74 b5 44 67 51 11 d3 c6 e5 76 b7 c6 9d 63 81 01 ec 1c 8a f0 5c 11 83 6a 6d 17 99 b8 3b 7e 26 8a 4b 4b 41 38 7f 39 cb ed 6b b4 32 ed dd 8e 14 fd 7b 69 63 47 08 85 31 bb 88 4e 7b d3 fc f7 2c 71 ef fc ac 44 a7 64 8c 9b bb cd 13 31 9e f1 bc 6a 21 58 3c ee ed 69 ed 10 fc 1a d1 1d 61 dc 06 bc 54 42 99 69 5b 44 67 61 2c 3a 4c db 3d fe 59 44 a7 6c cc f7 1d 4a b6 ce 8b 37 16 d1 e1 cd 42 b2 8e 4d b9 dd ba 1e 8b b1 30 0a 7e 17 e1 77 4b 7a a2 b3 a2 ce bd b0 a1 a8 ed 1d 65 d2 7d 0c 07 c6 b3 89 ae 4f 25 e2 8e 27 8c 9b 63 e8 e5 2b ab f9 7f 6d dc bb de 63 2f 89 33 dc b1 44 4c 61 67 3b 0f d1 6d 98 16 38 72 c6 05 9a 73 36 8d de 1a 0b 0c 48 da 49 73 06 f1 b8 bf 40 32 92 89 26 65 f5 04 2c 9c 91 04 e4 9c 3b 58 88 56 32 bb 6b 22 8e 0e ce c7 80 1a 1b d7 8b 56 14 54 d9 45 74 9f b1 90 ce 9a 8e 0a 2d 18 d7 5b 67 ef a1 2c dc 69 df 6a 6a e4 9d f5 7d f6 e5 4a 10 b1 0f c6 b9 e3 ce e5 b4 b9 4e 6b 87 e0 d7 bb d0 0d 37 1a dc 3a ec 22 3a 6b ce 4b 62 a9 1c 77 b8 fd 14 f1 18 8c 63 e9 1c 61 5a f3 7b 02 cd 5b 1a 5d 77 65 ca e6 86 54 bc bb ad 0a c1 1b 8b e8 62 12 62 44 62 39 bc 1e ca 9c a5 b6 2e 88 ac f4 9a 12 e8 89 6e 1f 5d 2e 69 8a 81 02 27 3c b6 be 9d 46 74 3f 7b 36 c6 2b 54 79 f6 b8 0e 5d 5e 39 2a 30 72 e2 4d f6 51 df da de 6d 6b 83 2f 5c 7e 06 e4 41 63 47 e3 df e7 21 ba c0 44 eb d8 e6 e6 12 d2 2c 17 4b b8 e6 fa 74 4e 43 98 16 2c 6b 67 22 1b eb f2 c9 20 46 32 d1 b4 82 6d dc 2d f5 ed 1a eb c3 3a f6 17 53 b6 e6 e4 3d d7 ed 98 30 8b ee 3b 9d 93 cf 62 d1 05 0a 47 eb cb 22 f6 c7 8f ad b1 d0 b4 ce cc 48 ff 5e 25 4d db 5a 10 8f b9 d8 fb da a1 f8 f5 84 73 d4 71 56 b3 91 7d a7 33 f4 18 75 6f 67 46 5e 98 33 9d 35 84 f3 16 d1 05 80 fa 98 88 1f f9 68 1e 8a ef 1d 95 3d 6f ca 51 6a 2b 1b 61 ae 37 16 d1 59 e7 c7 94 70 6d 95 51 db 0b f6 14 17 d9 7d 0d de 1b 62 35 a2 c3 06 76 02 5e cd 42 de bb 1e 0b a0 52 a2 4a a9 5b 3b 8d e8 94 75 7f 45 da e7 fa 6c 42 d7 d5 95 87 d6 1d b6 0c 22 85 60 af f6 c7 8d b6 77 db da 58 73 0a 9a 11 b4 96 d7 8e 1d f6 7f 9f 87 e8 63 20 46 2a 49 a8 df c4 a4 4f 5a 20 01 21 fa b3 28 eb 2f 20 c7 62 6b 2d b2 dd c8 04 5c 5a 93 cb f2 94 93 f7 b0 d0 88 83 34 7d e3 2a df bb c4 51 c0 86 f4 39 ee a3 e7 fa d6 ce e8 ac ba be 76 11 9d a5 95 06 3b ef 19 9d 75 a0 00 e1 d0 5a 5b 38 9a 1d 09 9c b7 2c c8 85 92 f1 8c de 2e de d8 5c 87 e2 37 5a 56 63 70 44 72 84 72 9c da 45 74 e3 63 d5 b8 f9 fe 15 c4 1c 2d fa 69 c1 b8 5e d9 bb e3 60 cd 90 aa 27 42 bf 0e 3c 8c f6 23 21 fd f3 b2 27 8e 3b d6 77 9f b7 d5 3e 73 d6 60 9c b3 35 6f 84 c7 71 25 cd 54 e2 98 63 bd b9 ec 5e e3 35 36 a2 bb b6 ec 4c df 7b 72 f6 9f fd 26 88 d9 c7 50 4e 23 fa 59 82 71 e6 67 ac dc 70 46 e2 53 4b 58 6b cf 35 4c 77 e1 83 77 94 18 2f 6d 57 bb fb 0c a2 3b df 22 26 90 5a a0 c7 a0 fa fc a3 01 38 7b 00 c7 6b 5b 91 6d 63 a1 71 fd 0c 94 cd a3 71 f5 b9 ee 5c e1 9e 44 ce 82 f2 8b ce 50 ac 18 17 cd 85 14 9b dd 99 aa 35 16 94 46 f7 bc a8 f3 2e b0 6c 38 1a fb e2 8e b1 75 5d 6e 3e f4 4b 35 82 82 00 ef 1b 4f 84 cb e8 0e 7e 8b ae 53 6c e2 0e ad bd 54 3d 10 10 6a 51 77 2e e3 21 f8 6d 45 bf 05 db 04 31 b9 d3 f7 29 e9 f3 e8 fd c6 93 dd f0 33 5a b7 2b 91 29 38 2b d1 7b 65 7f 9b fa 3c f2 48 6b 6e 05 e2 cc 13 a1 58 27 5e 55 6b 08 24 e5 ca aa 8a ad dc 90 44 77 5d 5a 90 75 8c c2 8f 44 b7 57 58 61 7b ab 6f 5c 7d eb 4b 51 b4 36 93 e8 30 fc f9 13 fc 2e d5 bf d6 83 d2 6e 01 67 df b9 8f e8 52 73 7d f6 aa 1f bb e3 c0 a3 cf 43 f4 fe 9a 2a 4d 47 1b 23 b1 0e 69 7a ae a0 b3 ec 98 92 70 af 9b 66 e7 7a 70 97 b6 02 5e 26 68 41 10 d7 26 b0 19 90 e8 96 25 00 e0 02 da 98 02 50 a4 9d c9 cd c3 d9 59 f0 cf 86 73 4e f5 3e 81 31 8b d6 52 0f 0d 2c 63 e5 09 48 09 2a 00 b2 68 08 f8 c4 12 6d 5f 1e 9d b6 e7 ca f6 8d 37 82 bc 0f 2c a1 70 bc ee 3b 8c 89 02 6c 58 20 22 0d cc b3 81 93 3c 3a 62 cb a3 b3 f8 94 e6 a1 f8 6d 11 dd b9 cd 66 15 e3 68 05 2e bb d2 3d 6d 33 b1 1c 23 d1 b7 f2 e8 3c 13 b2 f5 bd 6a 11 a4 72 fa 3c 70 8f 4f 53 c4 36 24 b7 f9 56 27 b8 d8 37 14 1c c5 d1 af 4d 9f bd d1 0f e5 2f a3 70 5e 8b ae 0f c7 94 bb 94 dc a3 84 92 d3 7a a2 db 3b e2 42 0d 8b 7e fc ed ca f3 85 7a b2 15 27 9d 46 f4 ad 3c ba 33 fb 56 61 19 8f c2 b8 e0 de 8e 86 e2 5a ea 2d 60 ca c8 8d f8 58 57 7b 1c 07 fa 5a 91 7e dc 8c 0c e5 76 e9 3c 44 ef af a9 3a 93 5d 57 a2 32 8e eb 2b 28 47 43 4b b5 6d 15 06 70 ed 7d 86 66 df 15 d9 f6 1a eb 73 b1 e4 e9 25 e2 00 bc 03 67 4f d6 4f f0 84 7b 85 c0 7d ce 9a 32 40 22 0b c0 52 79 1f a2 3d a2 7b 5f 03 ab 9e ba b2 71 8c 85 eb ca b5 bf ec c9 93 d6 e2 07 dd 53 cf 78 c8 b5 da fa 0d 3b ee ab 34 99 4d 4d 41 79 8f a0 4a 1f 85 86 37 fc 58 5b c7 04 df 09 13 47 0f 24 3a 0b 7e 5b 84 33 48 c4 79 5c c9 69 44 f7 5e 1b 89 c2 1c 89 be 35 6f 73 b3 a9 b6 be 97 87 80 34 dc 61 e7 d9 ad 26 50 27 ee 61 7c e6 ca 72 3a c6 b5 73 6b bf 36 e3 e7 9b ab 7d 35 44 6f 29 50 11 f4 16 4c ee 89 2e 10 cb 03 1c e3 32 c6 62 0f 5e 2e 61 d4 78 ad da 69 44 df c2 40 dc aa cf 4e 79 4f 9b 37 2f 55 95 64 6b 8e 34 d6 f1 b1 25 7d 20 b9 bd 0e 43 56 df fe f6 83 2a 2d b5 da 7f af 23 80 63 cb 95 00 40 5a 10 08 02 37 73 04 42 f4 9b f9 02 67 7a 41 20 16 3d 7b 20 08 1c 09 02 b1 e8 47 b2 d0 99 e6 71 23 70 56 a2 0b c4 9c 76 4d 55 4a 4c f1 c6 56 df 02 0e 82 19 2d dd 05 7d d1 43 91 58 81 31 91 ca be b5 b4 88 00 57 bb 75 a5 df ad 2b 84 5b 2b 39 06 78 44 2f c7 ab 7d ed b7 e1 fa 62 9e d6 57 7f 51 e2 90 f4 4f fb 5c 4b 99 09 bc 08 c0 f4 ad f5 a3 d0 a8 ff 85 5c ef f1 7e 19 02 e9 c4 b1 6d 61 d7 de 23 78 28 b0 25 42 eb ea 70 df 04 1a e5 ed c7 4b 45 ed 3d 22 b3 02 68 17 4b d4 44 08 00 f5 15 7d ed 7d 6a be 05 85 44 86 05 d0 cc ab bf 5e db a2 fb 1b 43 bf 52 71 28 a3 d1 07 9b c6 f7 c9 b2 dc bb c4 bd 80 b1 36 5d 50 55 e0 55 b0 b5 b5 56 45 a6 c8 a5 2f 37 96 aa b3 07 15 69 8d f5 0a e3 fe dd ba 62 bb 2f 10 bb 15 91 1f f7 98 a0 98 02 1d 35 1e fe e7 1f 41 33 ad ff ee 7e ee d2 c1 d6 7b ec 47 f1 98 bd aa e8 ac 05 74 b7 30 96 c9 72 f1 c5 9a 8d 65 ba cf c0 e2 3c 44 3f ed 9a ea 3e a2 3f b2 06 63 c3 8a 06 4a c7 b4 26 ed 21 fd 21 17 d8 52 5c 5e 13 4d 54 25 74 f7 92 16 61 df 75 85 b0 07 af 3d 6e e0 ed bb da 77 43 10 5d 2e db 22 53 60 0a 49 fa d6 c6 44 e9 a8 39 ef 2b d0 f6 11 7d 17 76 fa 6e 9b 53 24 1b 56 fd 06 df 47 74 75 08 88 7d da f5 5a e5 af 36 d2 fd 4a dc 91 46 2e 69 4c 7b 41 49 af ef 6b 9b 70 2b ad 24 ca 8d 00 8a 9a 5a 93 ca 92 61 91 f5 d0 f4 21 8d 86 24 14 43 4b 49 ca a6 48 b7 79 9d 02 6d d1 65 a4 27 08 df da 21 57 7b 5b 7a d8 de df ba 62 bb 2f b5 ba 75 45 79 4c 7b c9 40 5c 28 51 43 21 0b 01 3b 0d d1 fb d4 74 1b b3 9a 12 75 28 63 3f 52 a5 f6 ad d4 99 f4 a5 b4 ed 16 c6 52 c4 b2 19 0a 7a fa 9b 89 d7 c3 e2 3c 44 1f 2b e3 c6 6b aa bb 88 de f2 e8 72 a9 ed 7a 6a db 90 c6 c1 d2 cb 31 36 ab de 2a c4 c6 8a 24 39 47 f9 e6 7d 57 08 1b 88 bb ac 70 7f b5 6f 36 d1 cd 45 9d 00 f2 48 3b ea bf 2f 0d 6d 63 52 c0 23 75 d2 7b 31 bb 88 be 0f 3b 73 b5 39 2f 95 d8 64 6a 18 1e da 00 a8 7f f7 11 bd a5 88 e4 63 77 5d af a5 68 59 1d 79 fe 96 7f d6 bd df 08 b0 91 7d b7 5a 87 5d f9 fa 6e 28 d7 7b b8 ab 2c d6 c6 66 05 e5 91 35 eb 8c 24 6a 13 d4 f4 bb e5 a7 a9 ec 53 d3 f0 80 ae d7 43 ae f6 8e fb 97 b2 a3 48 da 15 db b3 5e 68 da b5 c7 78 57 0a 89 60 ab 36 62 57 3a b4 0d 7f 57 3f 3c 3c 06 81 47 b5 0b 63 a4 56 90 44 41 4a b7 69 d7 c3 62 06 d1 c7 6b aa bb 88 2e 77 cc 9a 2b 0c 50 d0 a2 5a cc 46 6f 8d a5 56 1d d4 ca 50 af ad c7 f2 d1 16 b7 35 5a 8e 6b 27 27 ba ef 0a 61 7b ff 2e f0 fa ab 7d b3 89 6e 51 b8 a1 c8 c9 ed 62 01 11 7e 1c 93 9f b7 46 9c fe c6 d7 2e a2 9f 86 5d db 9c bc 24 df 65 93 b5 fc f4 3e a2 b7 dc f2 be eb b5 2c b6 3c ff 85 92 f1 92 06 af 85 75 b7 46 b3 88 ee 48 a3 32 b2 59 6a d5 86 8e 55 2c 1f e5 08 53 fb d6 3e 50 80 d4 2b 1f 95 79 72 cb 3c a9 ad ab d1 bb c8 66 3f ba 8b e0 f3 b3 88 ce 13 51 07 d2 2a 21 cf 4b 74 6b 64 4d 29 a4 7d 18 ab c7 50 3b 72 25 6f 7e 32 97 67 60 71 b5 44 df ba a6 ba 45 74 df f3 f8 12 da 9a a5 b3 41 58 9f b1 64 f4 da 7a 8e 65 51 c4 61 d0 ce 59 fd 99 f3 d0 2b 84 27 73 3d e8 6a df 6c a2 d3 c0 ac a0 f2 45 4a ed 52 89 62 9a ad 9a 65 04 b1 b1 29 4b 15 51 5b 44 3f 04 bb b6 39 29 19 e7 60 d6 52 bf b0 dc 47 74 95 8d 6e 66 ed bb 5e cb bd 76 b4 12 27 19 1b 42 72 9f cd 6f 16 d1 9b 27 c7 63 30 0f e3 53 a8 03 07 78 c1 d4 6f 1b 5c 2e 51 fc d2 ce a5 67 b9 da db 97 e2 3a 4e f4 57 6c 67 11 bd c5 69 da 95 e0 f3 12 dd 58 19 03 85 46 bb 30 86 8d f3 3c 9c 78 3a cf 84 c5 79 88 3e de 5c e2 96 aa e8 a2 15 b5 2d a2 db 08 5c 2e 03 70 1e b9 58 42 13 5f 28 71 ce 6b ad 5d 2e 61 e9 bd df d9 a3 6f 87 5e 21 6c 9f 69 16 dd d9 88 45 d8 ba da 37 93 e8 fa 77 c6 b4 f9 05 50 d4 30 0b 96 dc b5 84 a2 d3 7a 2f 43 e9 ab b9 0a a6 70 bb b6 88 7e 08 76 fd e6 34 1f 8b ce 12 ab fc 3a 2d 18 77 da f5 da 07 56 1f 82 50 63 45 97 b9 78 ce d9 dd a6 6e 9b 90 15 83 75 6b c6 a2 e2 6c 6c bb 5c 77 c7 32 7b 84 c7 e7 1c ef dc ae 54 58 ff ac 38 05 70 b1 84 97 61 5c ed c8 01 73 e7 61 84 d0 b8 fe 1e db 8f ad 8d 64 db ba 62 db e2 1d 87 5e 51 de f2 1a 8d d1 11 93 db 4e 49 3a 76 f8 ee 9e 3b 6d 4c f6 86 bd 3e f6 c3 9b 55 2e 8e 27 3c 17 7b 7f 8b e8 94 b4 79 33 30 3c 39 c7 ac 67 c2 e2 3c 44 ef 6f 2e 6d 5d 53 dd 22 ba cd 22 d2 ce 0d d1 b8 35 dc 30 d1 5e 2e 47 df 58 73 96 42 19 eb e5 ee 85 b3 5c 21 6c 1f 6b e0 ed bb da 37 93 e8 77 ab 2f 7e 58 c9 85 92 a7 9e 0c 42 c4 98 b5 16 c8 d2 c6 05 bd 58 cf a9 47 e6 aa da b8 63 d4 fd 10 ec 46 2b 64 93 f3 28 1c 87 f4 b7 2f ea de b0 b2 b1 b6 ae d7 b2 28 36 23 65 3e 36 eb e4 58 c1 0a b7 4d e8 ef fe d2 8e ef a6 ec c6 b6 8b e8 de 27 f0 e8 a6 98 3d a2 4c b7 79 13 ae 3f 0b 02 3a 4f 73 65 95 e5 6a f6 f1 21 57 a3 47 b2 dd b6 3e 37 5e b1 6d c1 b8 b1 14 76 d7 15 e5 d1 98 f0 54 f1 02 41 c5 0f da 25 2b df dd 73 a7 e1 c1 9b a0 08 76 19 25 31 ad f6 9b 0a 0d 63 31 8c 76 8c 32 07 58 59 73 47 c6 4d 2c ce 43 f4 31 98 21 25 23 82 4c 8b 20 ed 48 f4 46 6a d1 c1 a7 b5 d9 d5 bf b7 2e 11 49 a4 b9 fb 26 40 44 1b aa 97 ef db 59 ae 10 b6 cf ed 3a a3 1b 6b bb da 47 01 09 96 f4 29 bf f6 79 6e a1 33 cf b5 25 bb fa ea c7 28 4b a0 26 bf f7 52 58 79 0b c9 dd b4 40 5b fd 3c b8 9e a7 8d 7d 9e 65 6f e9 b5 43 b1 1b 89 ee a2 8a 8d ef ac 4a e3 ef 22 ba 39 db 7c d6 af 35 7b c2 a6 34 56 ca 82 67 20 75 e7 f6 dd 78 46 17 13 40 38 96 7d 96 eb 6e 1c 36 b7 20 2c 2f 11 11 28 7f 4d a0 51 bb 43 89 00 20 a5 aa 35 77 9f 42 ed 33 0e 48 20 ad d8 ae 46 8f 64 db ba 62 7b 5e d7 bd 19 13 1e 06 2f 64 bc f8 74 a8 eb de fa c1 17 4a c8 c5 1f 19 29 ad 61 6c ff b6 bb ee e6 dc ef b7 4d 2c 66 10 bd b9 5a ed 9a ea 48 74 da 98 86 76 0e e5 d6 b5 c6 62 9b c0 85 92 e6 f6 7b 6d 17 d1 1f 73 32 b9 87 77 7d 78 b8 75 85 b0 bd 65 1f d1 db d5 3e 0a c7 46 57 1f d0 2e 2c f8 3c 45 43 a9 dd b9 04 e0 a7 11 9d bb 26 7a cb 3d 93 9f 6d 8d a5 a4 69 1d 43 68 f9 5d ae 9e 08 2d 72 dd aa a4 11 fd 50 ec b6 36 27 32 20 bb cb 46 6a 10 b6 f2 e8 87 5c af e5 49 51 78 d6 a5 f7 be b8 bd 2c 2e 1c e5 d4 67 12 5d 5a 49 e6 c0 06 66 b5 05 34 b5 db 97 b8 ed e7 38 c3 c3 68 38 9f f7 6a ef d6 15 db f3 12 9d 22 6c 01 d0 93 e1 5e ef 9f 43 89 de f7 03 5b bc 82 07 0f f1 10 8c 37 b1 38 0f d1 f7 5d 53 45 98 91 e8 0f 39 19 e4 b8 d1 7c b7 28 3b ad dc ff 54 d4 16 d1 cf 7a 85 b0 21 dc 48 d5 ae 5d 02 6a eb 6a 9f bc be 40 8f b3 90 73 a1 33 a1 e7 b8 61 a2 e3 72 b7 63 5f fd 2a d2 e0 ac 1a 37 57 91 cf f8 03 0c 3c 14 6e f9 3d bb 7e c6 8d c1 72 ca 1d b7 02 0a fd 1f 8a dd ae cd e9 1c db 2c da 16 d1 0f bd 5e eb d6 96 39 70 a3 91 4e b0 51 94 9a f5 a2 d8 58 fa 43 36 61 8f d9 3e d7 9d f2 f5 3a e1 09 b5 dc 39 4f c5 f9 9d d5 b6 46 be 97 d7 73 35 57 7b c7 2b b6 fb f2 e8 5b 57 94 4f 33 00 6d ce 88 be 95 47 f7 fa 93 77 ec 0b de 20 23 c1 50 f2 9e 4e c3 78 27 16 e7 21 fa be 6b aa 06 dd 13 9d fb 41 cb 39 bb 5e d7 af f2 c9 63 1a 4b 20 c1 d9 b0 05 70 b6 88 7e d6 2b 84 ed ab da 22 b4 bf 59 4c 29 87 f1 6a 1f 00 1d 21 68 4f e9 3d 16 4c 0a ce 75 d8 76 ff 78 ec ab 9f 8e 20 a2 b4 8f b3 29 0f 63 6c dc b1 27 94 20 37 92 20 cb 48 74 6b f1 a8 12 2e 3c 8b 7e 16 ec b8 d8 ad 36 a1 ff 6e 7d a8 22 14 89 df 55 19 c7 3a 9e 76 bd 56 9f 0a 71 b8 ba 3c 33 67 6e 56 9c 82 86 a9 76 da 26 1c 31 d9 47 74 ef 85 a5 c0 66 fb 11 92 f6 79 29 30 df d5 e2 3d 57 7b b5 77 bc 62 db 82 71 e3 78 fd bd 75 45 f9 2c 44 ef b9 d3 f7 cf eb b3 0e 5b fb e2 62 3d af a8 c8 be 64 04 28 b9 fe 47 44 fa 7e 76 62 71 56 a2 6f 4d 3e cf 05 81 20 b0 38 02 21 fa e2 0b 94 e1 05 81 19 08 84 e8 33 50 4c 1f 41 60 71 04 42 f4 c5 17 28 c3 0b 02 33 10 08 d1 67 a0 98 3e 82 c0 e2 08 84 e8 8b 2f 50 86 17 04 66 20 10 a2 cf 40 31 7d 04 81 c5 11 08 d1 17 5f a0 0c 2f 08 cc 40 20 44 9f 81 62 fa 08 02 8b 23 10 a2 2f be 40 19 5e 10 98 81 40 88 3e 03 c5 f4 11 04 16 47 20 44 5f 7c 81 32 bc 20 30 03 81 10 7d 06 8a e9 23 08 2c 8e 40 88 be f8 02 65 78 41 60 06 02 21 fa 0c 14 d3 47 10 58 1c 81 10 7d f1 05 ca f0 82 c0 0c 04 42 f4 19 28 a6 8f 20 b0 38 02 21 fa e2 0b 94 e1 05 81 19 08 84 e8 33 50 4c 1f 41 60 71 04 42 f4 c5 17 28 c3 0b 02 33 10 08 d1 67 a0 98 3e 82 c0 e2 08 84 e8 8b 2f 50 86 17 04 66 20 10 a2 cf 40 31 7d 04 81 c5 11 08 d1 17 5f a0 0c 2f 08 cc 40 20 44 9f 81 62 fa 08 02 8b 23 10 a2 2f be 40 19 5e 10 98 81 40 88 3e 03 c5 f4 11 04 16 47 20 44 5f 7c 81 32 bc 20 30 03 81 10 7d 06 8a e9 23 08 2c 8e 40 88 be f8 02 65 78 41 60 06 02 21 fa 0c 14 d3 47 10 58 1c 81 10 7d f1 05 ca f0 82 c0 0c 04 42 f4 19 28 a6 8f 20 b0 38 02 21 fa e2 0b 94 e1 05 81 19 08 84 e8 33 50 4c 1f 41 60 71 04 42 f4 c5 17 28 c3 0b 02 33 10 08 d1 67 a0 98 3e 82 c0 e2 08 84 e8 8b 2f 50 86 17 04 66 20 10 a2 cf 40 31 7d 04 81 c5 11 08 d1 17 5f a0 0c 2f 08 cc 40 20 44 9f 81 62 fa 08 02 8b 23 10 a2 2f be 40 19 5e 10 98 81 40 88 3e 03 c5 f4 11 04 16 47 20 44 5f 7c 81 32 bc 20 30 03 81 10 7d 06 8a e9 23 08 2c 8e 40 88 be f8 02 65 78 41 60 06 02 21 fa 0c 14 d3 47 10 58 1c 81 10 7d f1 05 ca f0 82 c0 0c 04 42 f4 19 28 a6 8f 20 b0 38 02 21 fa e2 0b 94 e1 05 81 19 08 84 e8 33 50 4c 1f 41 60 71 04 42 f4 c5 17 28 c3 0b 02 33 10 08 d1 67 a0 98 3e 82 c0 e2 08 84 e8 8b 2f 50 86 17 04 66 20 10 a2 cf 40 31 7d 04 81 c5 11 08 d1 17 5f a0 0c 2f 08 cc 40 20 44 9f 81 62 fa 08 02 8b 23 10 a2 2f be 40 19 5e 10 98 81 40 88 3e 03 c5 f4 11 04 16 47 20 44 5f 7c 81 32 bc 20 30 03 81 10 7d 06 8a e9 23 08 2c 8e 40 88 be f8 02 65 78 41 60 06 02 21 fa 0c 14 d3 47 10 58 1c 81 10 7d f1 05 ca f0 82 c0 0c 04 42 f4 19 28 a6 8f 20 b0 38 02 21 fa e2 0b 94 e1 05 81 19 08 84 e8 33 50 4c 1f 41 60 71 04 42 f4 c5 17 28 c3 0b 02 33 10 08 d1 67 a0 98 3e 82 c0 e2 08 84 e8 8b 2f 50 86 17 04 66 20 10 a2 cf 40 31 7d 04 81 c5 11 08 d1 17 5f a0 0c 2f 08 cc 40 20 44 9f 81 62 fa 08 02 8b 23 10 a2 2f be 40 19 5e 10 98 81 40 88 3e 03 c5 f4 11 04 16 47 20 44 5f 7c 81 32 bc 20 30 03 81 10 7d 06 8a e9 23 08 2c 8e 40 88 be f8 02 65 78 41 60 06 02 21 fa 0c 14 d3 47 10 58 1c 81 10 7d f1 05 ca f0 82 c0 0c 04 42 f4 19 28 a6 8f 20 b0 38 02 21 fa e2 0b 94 e1 05 81 19 08 84 e8 33 50 4c 1f 41 60 71 04 42 f4 c5 17 28 c3 0b 02 33 10 08 d1 67 a0 98 3e 82 c0 e2 08 84 e8 8b 2f 50 86 17 04 66 20 10 a2 cf 40 31 7d 04 81 c5 11 08 d1 17 5f a0 0c 2f 08 cc 40 20 44 9f 81 62 fa 08 02 8b 23 10 a2 2f be 40 19 5e 10 98 81 40 88 3e 03 c5 f4 11 04 16 47 20 44 5f 7c 81 32 bc 20 30 03 81 10 7d 06 8a e9 23 08 2c 8e 40 88 be f8 02 65 78 41 60 06 02 21 fa 0c 14 d3 47 10 58 1c 81 10 7d f1 05 ca f0 82 c0 0c 04 42 f4 19 28 a6 8f 20 b0 38 02 21 fa e2 0b 94 e1 05 81 19 08 84 e8 33 50 4c 1f 41 60 71 04 42 f4 c5 17 28 c3 0b 02 33 10 08 d1 67 a0 98 3e 82 c0 e2 08 84 e8 8b 2f 50 86 17 04 66 20 10 a2 cf 40 31 7d 04 81 c5 11 08 d1 17 5f a0 0c 2f 08 cc 40 20 44 9f 81 62 fa 08 02 8b 23 10 a2 2f be 40 19 5e 10 98 81 40 88 3e 03 c5 f4 11 04 16 47 20 44 5f 7c 81 32 bc 20 30 03 81 10 7d 06 8a e9 23 08 2c 8e 40 88 be f8 02 65 78 41 60 06 02 21 fa 0c 14 d3 47 10 58 1c 81 10 7d f1 05 ca f0 82 c0 0c 04 42 f4 19 28 a6 8f 20 b0 38 02 21 fa e2 0b 94 e1 05 81 19 08 84 e8 33 50 4c 1f 41 60 71 04 42 f4 c5 17 28 c3 0b 02 33 10 08 d1 67 a0 98 3e 82 c0 e2 08 84 e8 8b 2f 50 86 17 04 66 20 10 a2 cf 40 31 7d 04 81 c5 11 08 d1 17 5f a0 0c 2f 08 cc 40 e0 7f 01 1f c3 cf 0e f9 f2 e3 c6 00 00 00 00 49 45 4e 44 ae 42 60 82");
	    byte[] bytes	= new byte[formats.length + image.length];		

            byte[] bt = decodeBitmap(bitmap);
//             byte[] bt = {(byte)0x1B,(byte)0x58,(byte)0x31,(byte)0x24,(byte)0x2D,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x0E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x1B,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x39,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x38,(byte)0x80,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x7C,(byte)0x40,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x0F,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x7E,(byte)0x20,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x10,(byte)0xC0,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x03,(byte)0x3F,(byte)0x10,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x37,(byte)0x40,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x06,(byte)0x9F,(byte)0x88,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x25,(byte)0x40,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x0C,(byte)0x4F,(byte)0xF0,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x27,(byte)0x40,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x1E,(byte)0x27,(byte)0xE6,(byte)0x00,(byte)0x03,(byte)0xFF,(byte)0xFC,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0xFF,(byte)0xF0,(byte)0x07,(byte)0xFF,(byte)0xF8,(byte)0x7F,(byte)0xFF,(byte)0x1E,(byte)0x00,(byte)0x7D,(byte)0xFF,(byte)0xFE,(byte)0x0F,(byte)0xFF,(byte)0xC1,(byte)0xFF,(byte)0xF8,(byte)0x25,(byte)0x40,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x3F,(byte)0x93,(byte)0xCD,(byte)0x00,(byte)0x03,(byte)0xFF,(byte)0xFE,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0xFF,(byte)0xF0,(byte)0x07,(byte)0xFF,(byte)0xFC,(byte)0x7F,(byte)0xFF,(byte)0x9F,(byte)0x00,(byte)0x7D,(byte)0xFF,(byte)0xFF,(byte)0x1F,(byte)0xFF,(byte)0xE3,(byte)0xFF,(byte)0xFC,(byte)0x10,(byte)0xC0,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x1F,(byte)0xC9,(byte)0x98,(byte)0x80,(byte)0x03,(byte)0xFF,(byte)0xFF,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0xFF,(byte)0xF0,(byte)0x07,(byte)0xFF,(byte)0xFC,(byte)0xFF,(byte)0xFF,(byte)0x9F,(byte)0x00,(byte)0xFD,(byte)0xFF,(byte)0xFF,(byte)0x3F,(byte)0xFF,(byte)0xE3,(byte)0xFF,(byte)0xFE,(byte)0x0F,(byte)0x80,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0xCF,(byte)0xE4,(byte)0x3C,(byte)0x60,(byte)0x03,(byte)0xC0,(byte)0x0F,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0xFF,(byte)0xFC,(byte)0xFF,(byte)0xFF,(byte)0x9F,(byte)0x80,(byte)0xFD,(byte)0xFF,(byte)0xFF,(byte)0xBF,(byte)0xFF,(byte)0xF7,(byte)0xFF,(byte)0xFE,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x01,(byte)0xA7,(byte)0xF2,(byte)0x3F,(byte)0x30,(byte)0x03,(byte)0x80,(byte)0x07,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0x00,(byte)0x1C,(byte)0xE0,(byte)0x03,(byte)0x9F,(byte)0x81,(byte)0xFD,(byte)0xC0,(byte)0x07,(byte)0xB8,(byte)0x00,(byte)0xF7,(byte)0x80,(byte)0x0E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x03,(byte)0x93,(byte)0xFC,(byte)0x3F,(byte)0x98,(byte)0x03,(byte)0x80,(byte)0x07,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0x00,(byte)0x1C,(byte)0xE0,(byte)0x03,(byte)0x9F,(byte)0xC3,(byte)0xFD,(byte)0xC0,(byte)0x07,(byte)0xB8,(byte)0x00,(byte)0x77,(byte)0x80,(byte)0x0E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0xC9,(byte)0xF9,(byte)0x9F,(byte)0xCC,(byte)0x03,(byte)0xFF,(byte)0xFE,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0xFF,(byte)0xE0,(byte)0x07,(byte)0xFF,(byte)0xFC,(byte)0xE0,(byte)0x03,(byte)0x9F,(byte)0xC3,(byte)0xFD,(byte)0xFF,(byte)0xFF,(byte)0x38,(byte)0x00,(byte)0x77,(byte)0x80,(byte)0x0E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0xE4,(byte)0x73,(byte)0x4F,(byte)0xE4,(byte)0x03,(byte)0xFF,(byte)0xFE,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0xFF,(byte)0xE0,(byte)0x07,(byte)0xFF,(byte)0xF8,(byte)0xE7,(byte)0xFF,(byte)0x9D,(byte)0xE7,(byte)0xBD,(byte)0xFF,(byte)0xFF,(byte)0x38,(byte)0x00,(byte)0x77,(byte)0x80,(byte)0x0E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0xE2,(byte)0x72,(byte)0x27,(byte)0xFC,(byte)0x03,(byte)0xFF,(byte)0xFE,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0xFF,(byte)0xE0,(byte)0x07,(byte)0xFF,(byte)0xF8,(byte)0xE7,(byte)0xFF,(byte)0x9D,(byte)0xE7,(byte)0xBD,(byte)0xFF,(byte)0xFF,(byte)0x38,(byte)0x00,(byte)0x77,(byte)0x80,(byte)0x0E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x03,(byte)0xF1,(byte)0x07,(byte)0x13,(byte)0xF8,(byte)0x03,(byte)0xFF,(byte)0xFF,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0xFF,(byte)0xE0,(byte)0x07,(byte)0xFF,(byte)0xFC,(byte)0xE7,(byte)0xFF,(byte)0x9C,(byte)0xFF,(byte)0x3D,(byte)0xFF,(byte)0xFF,(byte)0x38,(byte)0x00,(byte)0x77,(byte)0x80,(byte)0x0E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x01,(byte)0xF9,(byte)0x8F,(byte)0x89,(byte)0xF0,(byte)0x03,(byte)0xC0,(byte)0x07,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0x00,(byte)0x1E,(byte)0xE7,(byte)0xFF,(byte)0x9C,(byte)0xFF,(byte)0x3D,(byte)0xC0,(byte)0x07,(byte)0xB8,(byte)0x00,(byte)0x77,(byte)0x80,(byte)0x0E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0xFF,(byte)0x8F,(byte)0xC4,(byte)0xE0,(byte)0x03,(byte)0x80,(byte)0x07,(byte)0x78,(byte)0x00,(byte)0x70,(byte)0x00,(byte)0xEF,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0x00,(byte)0x1E,(byte)0xE0,(byte)0x03,(byte)0x9C,(byte)0x7E,(byte)0x3D,(byte)0xC0,(byte)0x03,(byte)0xB8,(byte)0x00,(byte)0x77,(byte)0x80,(byte)0x0E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x7E,(byte)0x27,(byte)0xE2,(byte)0x00,(byte)0x03,(byte)0xC0,(byte)0x07,(byte)0x78,(byte)0x00,(byte)0x78,(byte)0x01,(byte)0xEF,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0x00,(byte)0x1E,(byte)0xE0,(byte)0x03,(byte)0x9C,(byte)0x3E,(byte)0x3D,(byte)0xE0,(byte)0x07,(byte)0xBC,(byte)0x00,(byte)0xF7,(byte)0xC0,(byte)0x1E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x3C,(byte)0xD3,(byte)0xF1,(byte)0x00,(byte)0x03,(byte)0xFF,(byte)0xFF,(byte)0x7F,(byte)0xFF,(byte)0x3F,(byte)0xFF,(byte)0xEF,(byte)0xFF,(byte)0xF0,(byte)0x07,(byte)0xFF,(byte)0xFC,(byte)0xE0,(byte)0x03,(byte)0x9C,(byte)0x3C,(byte)0x3D,(byte)0xFF,(byte)0xFF,(byte)0xBF,(byte)0xFF,(byte)0xF3,(byte)0xFF,(byte)0xFE,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x19,(byte)0xC9,(byte)0xFA,(byte)0x00,(byte)0x03,(byte)0xFF,(byte)0xFF,(byte)0x7F,(byte)0xFF,(byte)0x3F,(byte)0xFF,(byte)0xCF,(byte)0xFF,(byte)0xF0,(byte)0x07,(byte)0xFF,(byte)0xFC,(byte)0xE0,(byte)0x03,(byte)0x9C,(byte)0x18,(byte)0x3D,(byte)0xFF,(byte)0xFF,(byte)0x1F,(byte)0xFF,(byte)0xE3,(byte)0xFF,(byte)0xFC,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x03,(byte)0xE4,(byte)0xFC,(byte)0x00,(byte)0x03,(byte)0xFF,(byte)0xFE,(byte)0x7F,(byte)0xFF,(byte)0x1F,(byte)0xFF,(byte)0x8F,(byte)0xFF,(byte)0xF0,(byte)0x07,(byte)0xFF,(byte)0xF8,(byte)0xE0,(byte)0x03,(byte)0x9C,(byte)0x18,(byte)0x3D,(byte)0xFF,(byte)0xFF,(byte)0x0F,(byte)0xFF,(byte)0xC1,(byte)0xFF,(byte)0xF8,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0xF2,(byte)0x78,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x07,(byte)0xFF,(byte)0xC0,(byte)0xC0,(byte)0x01,(byte)0x9C,(byte)0x00,(byte)0x19,(byte)0xFF,(byte)0xF8,(byte)0x03,(byte)0xFF,(byte)0x00,(byte)0x3F,(byte)0xE0,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x03,(byte)0xF9,(byte)0x30,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x01,(byte)0xFC,(byte)0x80,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0xFE,(byte)0x40,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x3F,(byte)0x80,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x3F,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x1F,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x0E,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,};
			            

            mmOutputStream.write(bytes);
            // tell the user data were sent
            //Log.d(LOG_TAG, "Data Sent");
            callbackContext.success(bt);
            return true;

        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    boolean printPOSCommand(CallbackContext callbackContext, byte[] buffer) throws IOException {
        try {
            mmOutputStream.write(buffer);
            // tell the user data were sent
            Log.d(LOG_TAG, "Data Sent");
            callbackContext.success("Data Sent");
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    // disconnect bluetooth printer.
    boolean disconnectBT(CallbackContext callbackContext) throws IOException {
        try {
            stopWorker = true;
            mmOutputStream.close();
            mmInputStream.close();
            mmSocket.close();
            callbackContext.success("Bluetooth Disconnect");
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    //New implementation, change old
    public static byte[] hexStringToBytes(String hexString) {
        if (hexString == null || hexString.equals("")) {
            return null;
        }
        hexString = hexString.toUpperCase();
        int length = hexString.length() / 2;
        char[] hexChars = hexString.toCharArray();
        byte[] d = new byte[length];
        for (int i = 0; i < length; i++) {
            int pos = i * 2;
            d[i] = (byte) (charToByte(hexChars[pos]) << 4 | charToByte(hexChars[pos + 1]));
        }
        return d;
    }

    private static byte charToByte(char c) {
        return (byte) "0123456789ABCDEF".indexOf(c);
    }

    //New implementation
    private static Bitmap resizeImage(Bitmap bitmap, int w, int h) {
        Bitmap BitmapOrg = bitmap;
        int width = BitmapOrg.getWidth();
        int height = BitmapOrg.getHeight();

        if (width > w) {
            float scaleWidth = ((float) w) / width;
            float scaleHeight = ((float) h) / height + 24;
            Matrix matrix = new Matrix();
            matrix.postScale(scaleWidth, scaleWidth);
            Bitmap resizedBitmap = Bitmap.createBitmap(BitmapOrg, 0, 0, width,
                    height, matrix, true);
            return resizedBitmap;
        } else {
            Bitmap resizedBitmap = Bitmap.createBitmap(w, height + 24, Config.RGB_565);
            Canvas canvas = new Canvas(resizedBitmap);
            Paint paint = new Paint();
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(bitmap, (w - width) / 2, 0, paint);
            return resizedBitmap;
        }
    }

    private static String hexStr = "0123456789ABCDEF";

    private static String[] binaryArray = {"0000", "0001", "0010", "0011",
        "0100", "0101", "0110", "0111", "1000", "1001", "1010", "1011",
        "1100", "1101", "1110", "1111"};

    public static byte[] decodeBitmap(Bitmap bmp) {
        int bmpWidth = bmp.getWidth();
        int bmpHeight = bmp.getHeight();
        List<String> list = new ArrayList<String>(); //binaryString list
        StringBuffer sb;
        int bitLen = bmpWidth / 8;
        int zeroCount = bmpWidth % 8;
        String zeroStr = "";
        if (zeroCount > 0) {
            bitLen = bmpWidth / 8 + 1;
            for (int i = 0; i < (8 - zeroCount); i++) {
                zeroStr = zeroStr + "0";
            }
        }

        for (int i = 0; i < bmpHeight; i++) {
            sb = new StringBuffer();
            for (int j = 0; j < bmpWidth; j++) {
                int color = bmp.getPixel(j, i);

                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                // if color close to white，bit='0', else bit='1'
                if (r > 160 && g > 160 && b > 160) {
                    sb.append("0");
                } else {
                    sb.append("1");
                }
            }
            if (zeroCount > 0) {
                sb.append(zeroStr);
            }
            list.add(sb.toString());
        }

        List<String> bmpHexList = binaryListToHexStringList(list);
        String commandHexString = "1B5831";
        String widthHexString = Integer.toHexString(bmpWidth % 8 == 0 ? bmpWidth / 8 : (bmpWidth / 8 + 1));
        if (widthHexString.length() > 2) {
            Log.d(LOG_TAG, "DECODEBITMAP ERROR : width is too large");
            return null;
        } else if (widthHexString.length() == 1) {
            widthHexString = "0" + widthHexString;
        }
        widthHexString = widthHexString + "00";

        String heightHexString = Integer.toHexString(bmpHeight);
        if (heightHexString.length() > 2) {
            Log.d(LOG_TAG, "DECODEBITMAP ERROR : height is too large");
            return null;
        } else if (heightHexString.length() == 1) {
            heightHexString = "0" + heightHexString;
        }
        heightHexString = heightHexString + "00";

        List<String> commandList = new ArrayList<String>();
        commandList.add(commandHexString + widthHexString + heightHexString);
        commandList.addAll(bmpHexList);

        return hexList2Byte(commandList);
    }

    public static List<String> binaryListToHexStringList(List<String> list) {
        List<String> hexList = new ArrayList<String>();
        for (String binaryStr : list) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < binaryStr.length(); i += 8) {
                String str = binaryStr.substring(i, i + 8);

                String hexString = myBinaryStrToHexString(str);
                sb.append(hexString);
            }
            hexList.add(sb.toString());
        }
        return hexList;

    }

    public static String myBinaryStrToHexString(String binaryStr) {
        String hex = "";
        String f4 = binaryStr.substring(0, 4);
        String b4 = binaryStr.substring(4, 8);
        for (int i = 0; i < binaryArray.length; i++) {
            if (f4.equals(binaryArray[i])) {
                hex += hexStr.substring(i, i + 1);
            }
        }
        for (int i = 0; i < binaryArray.length; i++) {
            if (b4.equals(binaryArray[i])) {
                hex += hexStr.substring(i, i + 1);
            }
        }

        return hex;
    }

    public static byte[] hexList2Byte(List<String> list) {
        List<byte[]> commandList = new ArrayList<byte[]>();

        for (String hexStr : list) {
            commandList.add(hexStringToBytes(hexStr));
        }
        byte[] bytes = sysCopy(commandList);
        return bytes;
    }

    public static byte[] sysCopy(List<byte[]> srcArrays) {
        int len = 0;
        for (byte[] srcArray : srcArrays) {
            len += srcArray.length;
        }
        byte[] destArray = new byte[len];
        int destLen = 0;
        for (byte[] srcArray : srcArrays) {
            System.arraycopy(srcArray, 0, destArray, destLen, srcArray.length);
            destLen += srcArray.length;
        }
        return destArray;
    }
	
  public static byte[] hexStringToBytes (String s) 
  {
    if (null == s)
      return null;
      
    return hexStringToBytes (s, 0, s.length());
  }
  /**
   * @param   hexString   source string (with Hex representation)
   * @param   offset      starting offset
   * @param   count       the length
   * @return  byte array
   */
  public static byte[] hexStringToBytes(String hexString, int offset, int count) 
  {
    if (null == hexString || offset < 0 || count < 2 || (offset + count) > hexString.length())
      return null;

    byte[] buffer =  new byte[count >> 1];
    int stringLength = offset + count;
    int byteIndex = 0;
    for(int i = offset; i < stringLength; i++)
    {
      char ch = hexString.charAt(i);
      if (ch == ' ')
        continue;
      byte hex = isHexChar(ch);
      if (hex < 0)
        return null;
      int shift = (byteIndex%2 == 1) ? 0 : 4;
      buffer[byteIndex>>1] |= hex << shift;
      byteIndex++;
    }
    byteIndex = byteIndex>>1;
    if (byteIndex > 0) {
      if (byteIndex < buffer.length) {
        byte[] newBuff = new byte[byteIndex];
        System.arraycopy(buffer, 0, newBuff, 0, byteIndex);
        buffer = null;
        return newBuff;
      }
    } else {
      buffer = null;
    }
    return buffer;
  }	

}
