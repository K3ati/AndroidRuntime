package com.tacttiles.runtime;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ResultReceiver;
import android.os.Vibrator;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * Created by andy on 17/04/17.
 */

public class RuntimeService extends Service {

    private static final String TAG = "RTS";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    public static String address = "30:14:12:17:15:63";//"00:14:03:19:33:30";//

    public static class RS_CMD {
        public static final int SEND = 0;
        public static final int CONNECT_BT = 1;
        public static final int REQUEST_FOCUS = 2;
        public static final int UNBIND = 3;


    }

    public static class RC_CMD {
        public static final int RECEIVED = 0;
        public static final int FOCUS_LOST = 1;
        public static final int DEVICE_LOST = 2;
        public static final int DEVICE_CONNECTED = 4;
        public static final int SERVICE_STOPPED_BY_USER = 5;
    }

    private NotificationManager notificationManger;
    private Map<UUID, ResultReceiver> processes;
    private UUID focusedApp = null;
    private Queue<byte[]> btOutQ;
    private Queue<String> btInQ;
    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    private OutputStream btOutput;
    private boolean btConnected = false;


    //We need to declare the receiver with onReceive function as below
    private BroadcastReceiver stopServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            notificationManger.cancel(01);
            Vibrator v = (Vibrator) RuntimeService.super.getSystemService(VIBRATOR_SERVICE);
            v.vibrate(500);

            sendIPCMessage(RC_CMD.SERVICE_STOPPED_BY_USER, null);
            disconnect();

            unregisterReceiver(stopServiceReceiver);
            stopSelf();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        processes = new HashMap<>();
        btOutQ = new LinkedList<>();
        btInQ = new LinkedList<>();
        IntentFilter intentFilter = new IntentFilter("STOP_TT_RUNTIME_SERVICE");
        registerReceiver(stopServiceReceiver, intentFilter);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent("STOP_TT_RUNTIME_SERVICE"), PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(getApplicationContext())
                .setContentTitle("Tact-Tiles Service Running").setContentText("Click here to disable").setOngoing(true)
                .setSmallIcon(R.mipmap.ic_ttlogo)
                .setContentIntent(pendingIntent)
                .build();

        notificationManger = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManger.notify(01, notification);

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) {
            if (!btAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                enableBtIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getApplicationContext().startActivity(enableBtIntent);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        notificationManger.cancel(01);
        disconnect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            onIPCMessage(intent.getIntExtra("type", -1), intent.getExtras(), null);
        } else {
            Log.d(TAG,"Null intent on onStartCommand call");
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        final UUID pid = UUID.randomUUID();
        processes.put(pid, (ResultReceiver) intent.getParcelableExtra("receiver"));

        Messenger messenger = new Messenger(new Handler() {

            private final UUID fpid = pid;

            @Override
            public void handleMessage(Message msg) {
                onIPCMessage(msg.what, msg.getData(), pid);
            }
        });

        return messenger.getBinder();
    }


    protected void onIPCMessage(int type, Bundle data, UUID pid) {

        if (pid == null) {
            //TODO: handle valid cmds
        }

        switch (type) {
            case RS_CMD.SEND:
                sendBTMessage(data.getByteArray("msg"));
                break;
            case RS_CMD.REQUEST_FOCUS:
                if (focusedApp != null) {
                    sendIPCMessage(RC_CMD.FOCUS_LOST, null, focusedApp);
                }
                focusedApp = pid;
                if (btConnected) {
                    sendIPCMessage(RC_CMD.DEVICE_CONNECTED, null, focusedApp);
                }
                //break; pass over
            case RS_CMD.CONNECT_BT:
                if (btSocket == null || !btConnected) {
                    new Thread() {
                        @Override
                        public void run() {
                            for (int i = 0; i < 3; i++) {
                                if (connect(address)) {
                                    if (focusedApp != null) {
                                        sendIPCMessage(RC_CMD.DEVICE_CONNECTED, null, focusedApp);
                                    }
                                    return;
                                } else {
                                    try {
                                        Thread.sleep(2000);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                            if (focusedApp != null) {
                                sendIPCMessage(RC_CMD.DEVICE_LOST, null, focusedApp);
                            }
                        }
                    }.start();
                }
                break;
            case RS_CMD.UNBIND:
                if (focusedApp.equals(pid)) {
                    focusedApp = null;
                }
                processes.remove(pid);
                break;

            case -1:
                //nothing to do
                break;
            default:
                throw new RuntimeException("Invalid CMD type: " + type);
                //TODO warning
        }


            /*

            Activity (settings)


            start/stop service button

            [] auto connect
            [] start after boot
            []

            view persistent triggers
            debug panel


            -------------
            REQUEST_FOCUS <DEVICE>
            REQUEST_BT_IN_QUEUE
            REQUEST_BT_OUT_FLUSH
            REGISTER_TRIGGER <DEVICE> <TRIGGER_TYPE> <ACTION> <PERSISTENT(save)>
                - <TRIGGER_TYPE>
                    - MESSAGE
                    - EXPRESSION
                    - CHANGE_FOCUS (<APP> <GAIN/LOST>)
                    - DEVICE_STATUS(<DEVICE> <CONNECTED/DESCONNECTED>
                    -
                - <ACTION>
                    - SEND_INTENT <INTENT> extra?
                    - SEND_MESSAGE (current bonded app) <TYPE> data?
            LIST_DEVICES
            SCAN <ADDRS> or CONNECT <DEVICE>
            DISCONNECT <DEVICE>
            PAIR <ADDRS> <PIN>

            ---------------------------
            CLIENT

            DEVICE_LIST use Bundle.keySet() to get device names and status

            SEND byte[]
            RECEIVED byte[]




             NOTA: Seguranca: apps precisam de permissao para adicionar triggers?
             */
/*
        switch (msgType) {
            case TO_UPPER_CASE: {
                try {
                    // Incoming data
                    String data = msg.getData().getString("data");
                    Message resp = Message.obtain(null, TO_UPPER_CASE_RESPONSE);
                    Bundle bResp = new Bundle();
                    bResp.putString("respData", data.toUpperCase());
                    resp.setData(bResp);

                    msg.replyTo.send(resp);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                break;
            }
            default:
                super.handleMessage(msg);
        }*/
    }

    protected void sendIPCMessage(int type, Bundle data) {
        for (Map.Entry<UUID, ResultReceiver> e : processes.entrySet()){
            e.getValue().send(type, data);
        }
    }

    protected void sendIPCMessage(int type, Bundle data, UUID pid) {
        ResultReceiver resultReceiver = processes.get(pid);
        if (resultReceiver != null) {
            resultReceiver.send(type, data);
        } else {
            throw new RuntimeException("Invalid process UUID");
        }
    }

    public void onBTMessage(String msg) {
        if (focusedApp == null) {
            btInQ.add(msg);
        } else {
            Bundle bundle = new Bundle();
            bundle.putString("msg", msg);
            sendIPCMessage(RC_CMD.RECEIVED, bundle, focusedApp);
        }
    }

    public void onBTLost() {
        sendIPCMessage(RC_CMD.DEVICE_LOST, null, focusedApp);
        btConnected = false;
    }

    public void flushBTOutQueue() {
        while (btConnected && !btOutQ.isEmpty()) {
            sendBTMessage(btOutQ.remove());
        }
    }

    public void sendBTMessage(byte[] data) {
        Log.d(TAG, "...Data to send: " + Arrays.toString(data) + "...");
        try {
            btOutput.write(data.length);
            btOutput.write(data);
        } catch (IOException e) {
            btOutQ.add(data);
            onBTLost();
        }
    }

    public void disconnect() {
        btConnected = false;
        try {
            btSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean connect(String address) {
        btConnected = false;
        BluetoothDevice device = btAdapter.getRemoteDevice(address);

        try {
            if (Build.VERSION.SDK_INT >= 10) {
                btSocket = device.createInsecureRfcommSocketToServiceRecord(MY_UUID);
            } else {
                btSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
            }
        } catch (Exception e) {
            e.printStackTrace();
            btAdapter.cancelDiscovery();
            return false;
        }

        btAdapter.cancelDiscovery();

        try {
            btSocket.connect();
            btOutput = btSocket.getOutputStream();
            final BufferedReader reader;
            reader = new BufferedReader(new InputStreamReader(btSocket.getInputStream()));

            new Thread("BT Reader Thread") {
                @Override
                public void run() {
                    // Keep listening to the InputStream until an exception occurs
                    while (true) {
                        try {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                Log.d(TAG, "RECEIVED: " + line);
                                if (line.startsWith("POWER OFF")) {
                                    return;
                                }

                                onBTMessage(line);
                            }
                        } catch (IOException e) {
                            onBTLost();
                            break;
                        }
                    }
                }
            }.start();
        } catch (Exception e) {
            e.printStackTrace();
            disconnect();
            return false;
        }

        btConnected = true;
        return true;
    }
}
