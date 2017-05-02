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
import android.os.RemoteException;
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
import java.util.ArrayList;
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
        public static final int REGISTER = 3;
        public static final int UNREGISTER = 4;

    }

    public static class RC_CMD {
        public static final int RECEIVED = 0;
        public static final int FOCUS_LOST = 1;
        public static final int DEVICE_LOST = 2;
        public static final int DEVICE_CONNECTED = 4;
        public static final int SERVICE_STOPPED_BY_USER = 5;
    }

    private NotificationManager notificationManger;
    private Messenger messenger;
    private ArrayList<Messenger> clients;
    private int focusedApp = 0;
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

            Log.d(TAG, "SERVICE_STOPPED_BY_USER");
            sendIPCMessage(RC_CMD.SERVICE_STOPPED_BY_USER, null);
            disconnect();

            unregisterReceiver(stopServiceReceiver);
            stopSelf();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        btOutQ = new LinkedList<>();
        btInQ = new LinkedList<>();
        clients = new ArrayList<>();

        messenger = new Messenger(new Handler() {
            @Override
            public void handleMessage(Message msg) {
                onIPCMessage(msg.what, msg.getData(), msg.replyTo);
            }
        });

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
        Log.d(TAG, "onDestroy");
        unregisterReceiver(stopServiceReceiver);
        notificationManger.cancel(01);
        disconnect();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int type = intent.getIntExtra("type", -1);
            onIPCMessage(type, intent.getExtras(), null);
            Log.d(TAG, "onStartCommand request: " + type);
        } else {
            Log.d(TAG, "Null intent on onStartCommand call");
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    protected void onIPCMessage(int type, Bundle data, Messenger reply) {

        Log.d(TAG, "onIPCMessage type=" + type + " pid=" + clients.indexOf(reply));
        if (reply == null) {
            //TODO: handle valid cmds
        }

        switch (type) {
            case RS_CMD.SEND:
                sendBTMessage(data.getByteArray("msg"));
                break;
            case RS_CMD.REQUEST_FOCUS:
                if (focusedApp >= 0) {
                    sendIPCMessage(RC_CMD.FOCUS_LOST, null, focusedApp);
                }
                focusedApp = clients.indexOf(reply);
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
                                    if (focusedApp >= 0) {
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
                            if (focusedApp >= 0) {
                                sendIPCMessage(RC_CMD.DEVICE_LOST, null, focusedApp);
                            }
                        }
                    }.start();
                }
                break;
            case RS_CMD.REGISTER:
                clients.add(reply);
                break;
            case RS_CMD.UNREGISTER:
                if (focusedApp == clients.indexOf(reply)) {
                    focusedApp = -1;
                }
                clients.remove(reply);
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
    }

    protected void sendIPCMessage(int type, Bundle data) {
        for (int i = clients.size() - 1; i >= 0; i--) {
            sendIPCMessage(type, data, i);
        }
    }

    protected void sendIPCMessage(int type, Bundle data, int pid) {
        Messenger messenger = clients.get(pid);
        Log.d(TAG, "sendIPCMessage type=" + type + " pid=" + pid);
        try {
            Message msg = Message.obtain(null, type);
            if (data != null) {
                msg.setData(data);
            }
            messenger.send(msg);
        } catch (RemoteException e) {
            // The client is dead.  Remove it from the list;
            // we are going through the list from back to front
            // so this is safe to do inside the loop.
            clients.remove(messenger);
        }
    }

    public void onBTMessage(String msg) {
        if (focusedApp < 0) {
            btInQ.add(msg);
        } else {
            Bundle bundle = new Bundle();
            bundle.putString("msg", msg);
            sendIPCMessage(RC_CMD.RECEIVED, bundle, focusedApp);
        }
    }

    public void onBTLost() {
        btConnected = false;
        if (focusedApp >= 0) {
            sendIPCMessage(RC_CMD.DEVICE_LOST, null, focusedApp);
        }
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
