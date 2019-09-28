package edu.buffalo.cse.cse486586.simpledht;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StreamCorruptedException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;

public class SimpleDhtProvider extends ContentProvider {

    static final String TAG = SimpleDhtProvider.class.getSimpleName();
    private static final String KEY_FIELD = "key";
    private static final String VALUE_FIELD = "value";
    static final String authority = "edu.buffalo.cse.cse486586.simpledht.provider";
    static final String scheme = "content";
    static final int SERVER_PORT = 10000;
    static final String REMOTE_PORT0 = "11108";
    static final String REMOTE_PORT1 = "11112";
    static final String REMOTE_PORT2 = "11116";
    static final String REMOTE_PORT3 = "11120";
    static final String REMOTE_PORT4 = "11124";
    static final int SOCKET_TIMEOUT = 2000;
    static final String[] ports = {REMOTE_PORT0, REMOTE_PORT1, REMOTE_PORT2, REMOTE_PORT3, REMOTE_PORT4};
    String mMyPort = "", mPredPort = "", mSuccPort = "", mNodeId = "", mPredId = "", mSuccId = "";
    static final String ACK = "ACK";
    static final String delimiter = "~";
    Node mHead = null;
    HashMap<String, Node> mMap;
    HashMap<String, String> mKeyVal;
    MatrixCursor mCursor;

    @Override
    public boolean onCreate() {
        TelephonyManager tel = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        mMyPort = String.valueOf((Integer.parseInt(portStr) * 2));
        mPredPort = mSuccPort = mMyPort;

        mMap = new HashMap<String, Node>();
        mKeyVal = new HashMap<String, String>();

        try {
            mNodeId = genHash(portStr);
            mPredId = mSuccId = mNodeId;
            Log.d(TAG, "Node id : " + mNodeId + " = " + mMyPort);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Node NoSuchAlgorithmException");
        }

        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create ServerSocket " + e.getMessage());
        }

        new ClientTask().executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR, "add_node", REMOTE_PORT0, mNodeId);
        return false;
    }

    /* References:
       1. Socket programming:
       https://docs.oracle.com/javase/tutorial/networking/sockets/index.html
       2. Two-way communication via Socket:
       https://www.youtube.com/watch?v=-xKgxqG411c
       3. Keeping socket connection alive
       https://stackoverflow.com/questions/36135983/cant-send-multiple-messages-via-socket
     */
    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {
        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            ServerSocket serverSocket = sockets[0];

            try {
                while (true) {
                    Socket socket = serverSocket.accept();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
                    String message = in.readLine();
                    if (message != null) {
                        String[] result = message.split(delimiter);
                        if (result[0].equals("add_node")) {
                            Log.d(TAG, "Message received by Server " + getClientName(mMyPort));
                            String id = result[1];
                            String port = result[2];

                            List<String> updateNodes = addNode(id, port);
                            if (updateNodes != null && updateNodes.size() > 0) {
                                String nodes = "";
                                int i;
                                for (i = 0; i < updateNodes.size() - 1; i++) {
                                    nodes += updateNodes.get(i) + ";";
                                }
                                nodes += updateNodes.get(i);
                                publishProgress("update", nodes);
                            }

                            PrintWriter out = new PrintWriter(socket.getOutputStream());
                            String str = "ack" + "\n";
                            out.write(str);
                            out.flush();
                        } else if (result[0].equals("update_node")) {
                            Log.d(TAG, "Message received by Server " + getClientName(mMyPort));
                            mPredPort = result[1];
                            mSuccPort = result[2];

                            try {
                                mPredId = genHash(String.valueOf(Integer.valueOf(mPredPort)/2));
                                mSuccId = genHash(String.valueOf(Integer.valueOf(mSuccPort)/2));
                            } catch (NoSuchAlgorithmException e) {
                                Log.d(TAG, "ServerTask NoSuchAlgorithmException");
                            }

                            PrintWriter out = new PrintWriter(socket.getOutputStream());
                            String str = "ack" + "\n";
                            out.write(str);
                            out.flush();
                        } else if (result[0].equals("insert")) {
                            Log.d(TAG, "Message received by Server " + getClientName(mMyPort));
                            String objectId = result[1];
                            String key = result[2];
                            String value = result[3];

                            if (mPredPort.equals(mMyPort) && mSuccPort.equals(mMyPort)) {
                                insertLocal(key, value);

                                PrintWriter out = new PrintWriter(socket.getOutputStream());
                                String str = "ack" + "\n";
                                out.write(str);
                                out.flush();
                                continue;
                            }

                            //Log.d(TAG, "objectId=" + objectId + ", nodeId=" + mNodeId + ", predId=" + mPredId + ", succId=" + mSuccId);
                            if (objectId.compareTo(mPredId) > 0 && mNodeId.compareTo(objectId) >= 0) {
                                insertLocal(key, value);
                            } else if (objectId.compareTo(mPredId) > 0 && mNodeId.compareTo(mPredId) < 0) {
                                insertLocal(key, value);
                            } else if (objectId.compareTo(mNodeId) < 0 && mPredId.compareTo(mNodeId) > 0) {
                                insertLocal(key, value);
                            } else {
                                publishProgress("insert", mSuccPort, objectId, key, value);
                            }

                            PrintWriter out = new PrintWriter(socket.getOutputStream());
                            String str = "ack" + "\n";
                            out.write(str);
                            out.flush();
                        } else if (result[0].equals("query")) {
                            String queryPort = result[1];
                            String objectId = result[2];
                            String key = result[3];
                            Log.d(TAG, "Message received by Server " + getClientName(mMyPort) + ": " + queryPort + delimiter + objectId + delimiter + key);

                            if (mPredPort.equals(mMyPort) && mSuccPort.equals(mMyPort)) {
                                if (key.equals("*")) {
                                    String data = queryAll();

                                    PrintWriter out = new PrintWriter(socket.getOutputStream());
                                    String str = "ack_query" + delimiter + key + delimiter + data + "\n";
                                    out.write(str);
                                    out.flush();
                                    continue;
                                }

                                String value = queryLocal(key);
                                PrintWriter out = new PrintWriter(socket.getOutputStream());
                                String str = "ack_query" + delimiter + key + delimiter + value + "\n";
                                out.write(str);
                                out.flush();
                                continue;
                            }

                            if (key.equals("*")) {
                                String temp = queryAll();
                                String data;

                                if (result.length == 4) {
                                    data = temp;
                                } else {
                                    data = result[4];
                                    if (!temp.isEmpty()) {
                                        data += ";" + temp;
                                    }
                                }

                                if (mSuccPort.equals(queryPort)) {
                                    publishProgress("return_query_all", queryPort, key, data);
                                } else {
                                    publishProgress("query_all", mSuccPort, queryPort, objectId, key, data);
                                }

                                PrintWriter out = new PrintWriter(socket.getOutputStream());
                                String str = "ack" + "\n";
                                out.write(str);
                                out.flush();
                                continue;
                            }

                            if (objectId.compareTo(mPredId) > 0 && mNodeId.compareTo(objectId) >= 0) {
                                publishProgress("return_query", queryPort, key);
                            } else if (objectId.compareTo(mPredId) > 0 && mNodeId.compareTo(mPredId) < 0) {
                                publishProgress("return_query", queryPort, key);
                            } else if (objectId.compareTo(mNodeId) < 0 && mPredId.compareTo(mNodeId) > 0) {
                                publishProgress("return_query", queryPort, key);
                            } else {
                                publishProgress("query", mSuccPort, queryPort, objectId, key);
                            }

                            PrintWriter out = new PrintWriter(socket.getOutputStream());
                            String str = "ack" + "\n";
                            out.write(str);
                            out.flush();
                        } else if (result[0].equals("return_query")) {
                            Log.d(TAG, "Message received by Server " + getClientName(mMyPort));
                            String key = result[1];
                            String value = result[2];
                            mKeyVal.put(key, value);

                            PrintWriter out = new PrintWriter(socket.getOutputStream());
                            String str = "ack" + "\n";
                            out.write(str);
                            out.flush();
                        } else if (result[0].equals("return_query_all")) {
                            Log.d(TAG, "Message received by Server " + getClientName(mMyPort));
                            String key = result[1];
                            String data = result.length == 3 ? result[2] : "";
                            publishProgress(key, data);

                            PrintWriter out = new PrintWriter(socket.getOutputStream());
                            String str = "ack" + "\n";
                            out.write(str);
                            out.flush();
                        } else if (result[0].equals("delete")) {
                            Log.d(TAG, "Message received by Server " + getClientName(mMyPort));
                            String queryPort = result[1];
                            String objectId = result[2];
                            String key = result[3];

                            if (mPredPort.equals(mMyPort) && mSuccPort.equals(mMyPort)) {
                                if (key.equals("*")) {
                                    publishProgress("delete_all_local");

                                    PrintWriter out = new PrintWriter(socket.getOutputStream());
                                    String str = "ack" + "\n";
                                    out.write(str);
                                    out.flush();
                                    continue;
                                }

                                publishProgress("delete_local", key);
                                PrintWriter out = new PrintWriter(socket.getOutputStream());
                                String str = "ack" + "\n";
                                out.write(str);
                                out.flush();
                                continue;
                            }

                            if (key.equals("*")) {
                                publishProgress("delete_all_local");

                                if (!mSuccPort.equals(queryPort)) {
                                    publishProgress("delete", mSuccPort, queryPort, objectId, key);
                                }

                                PrintWriter out = new PrintWriter(socket.getOutputStream());
                                String str = "ack" + "\n";
                                out.write(str);
                                out.flush();
                                continue;
                            }

                            if (objectId.compareTo(mPredId) > 0 && mNodeId.compareTo(objectId) >= 0) {
                                publishProgress("delete_local", key);
                            } else if (objectId.compareTo(mPredId) > 0 && mNodeId.compareTo(mPredId) < 0) {
                                publishProgress("delete_local", key);
                            } else if (objectId.compareTo(mNodeId) < 0 && mPredId.compareTo(mNodeId) > 0) {
                                publishProgress("delete_local", key);
                            } else {
                                publishProgress("delete", mSuccPort, queryPort, objectId, key);
                            }

                            PrintWriter out = new PrintWriter(socket.getOutputStream());
                            String str = "ack" + "\n";
                            out.write(str);
                            out.flush();
                        }
                    }
                }
            } catch (SocketTimeoutException e) {
                Log.e(TAG, "ServerTask SocketTimeoutException");
            } catch (IOException e) {
                Log.e(TAG, "ServerTask Socket IOException");
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            if (values[0].equals("update")) {
                sendChanges(values[1]);
            } else if (values[0].equals("insert")) {
                insertHop(values[0], values[1], values[2], values[3], values[4]);
            } else if (values[0].equals("query")) {
                queryHop(values[0], values[1], values[2], values[3], values[4]);
            } else if (values[0].equals("return_query")) {
                returnQuery(values[0], values[1], values[2]);
            } else if (values[0].equals("query_all")) {
                queryAllHop(values[0], values[1], values[2], values[3], values[4], values[5]);
            } else if (values[0].equals("return_query_all")) {
                returnQueryAll(values[0], values[1], values[2], values[3]);
            } else if (values[0].equals("*")) {
                strToCursor(values[1]);
            } else if (values[0].equals("delete_all_local")) {
                deleteAllLocal();
            } else if (values[0].equals("delete_local")) {
                deleteLocal(values[1]);
            } else if (values[0].equals("delete")) {
                queryHop(values[0], values[1], values[2], values[3], values[4]);
            }
        }
    }

    public List<String> addNode(String id, String port) {
        if (mMap.containsKey(port)) return null;

        if (mMyPort.equals(port)) {
            Node node = new Node(id, port);
            node.pred = node;
            node.succ = node;

            mMap.put(port, node);
            mHead = node;
            return null;
        }

        if (mHead != null) {
            Node node = new Node(id, port);

            Node ptr = mHead.succ;
            while (ptr != mHead && (id.compareTo(ptr.id) > 0 || id.compareTo(ptr.pred.id) <= 0)) {
                ptr = ptr.succ;
            }

            node.succ = ptr;
            node.pred = ptr.pred;

            ptr.pred.succ = node;
            ptr.pred = node;

            if (node.id.compareTo(mHead.id) < 0)
                mHead = node;

            mMap.put(port, node);

            //Log.d(TAG, "update head : " + mHead.port);

            List<String> list = new ArrayList<String>();
            list.add(node.port);
            if (!list.contains(node.pred.port))
                list.add(node.pred.port);
            if (!list.contains(node.succ.port))
                list.add(node.succ.port);
            return list;
        }
        return null;
    }

    public void insertLocal(String key, String value) {
        /*
            References:
            1. Content Provider Android Documentation
            https://developer.android.com/guide/topics/providers/content-provider-creating
            2. File storage Android Documentation
            https://developer.android.com/training/data-storage/files#java
            3. Reading and writing string from a file on Android
            https://stackoverflow.com/questions/14376807/how-to-read-write-string-from-a-file-in-android
            4. Overwriting a file in internal storage on Android
            https://stackoverflow.com/questions/36740254/how-to-overwrite-a-file-in-sdcard-in-android
         */
        String fileName = key;
        String fileContents = value + "\n";
        FileOutputStream outputStream;

        try {
            File file = new File(getContext().getFilesDir(), fileName);
            if (file.exists()) {
                outputStream = new FileOutputStream(file, false);
            } else {
                outputStream = new FileOutputStream(file);
            }
            outputStream.write(fileContents.getBytes());
            outputStream.close();
            Log.d(TAG, "Key Inserted : " + key);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write file.");
        }
    }

    public String queryLocal(String key) {
        String value = "";
        try {
            /*
            References:
             1. Content Provider Android Documentation
            https://developer.android.com/guide/topics/providers/content-provider-creating
            2. Reading file contents from internal storage on Android
            https://stackoverflow.com/questions/14768191/how-do-i-read-the-file-content-from-the-internal-storage-android-app
             */
            if (getContext().openFileInput(key) == null) {
                Log.e(TAG, "NULL");
                return  "";
            }
            FileInputStream inputStream = getContext().openFileInput(key);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(inputStream));
            value = in.readLine();
            inputStream.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read file.");
        }
        return value;
    }

    public String queryAll() {
        File directory = new File(getContext().getFilesDir().toString());
        File[] files = directory.listFiles();
        String str = "";
        if (files.length == 0)
            return str;
        int i;
        for (i = 0; i < files.length-1; i++)
        {
            str += files[i].getName() + ":" + queryLocal(files[i].getName()) + ";";
        }
        str += files[i].getName() + ":" + queryLocal(files[i].getName());
        return str;
    }

    public void sendChanges(String data) {
        String[] ports = data.split(";");

        for (String port : ports) {
            Node temp = mMap.get(port);
            new ClientTask().executeOnExecutor(
                    AsyncTask.THREAD_POOL_EXECUTOR, "update_node", port,  temp.pred.port, temp.succ.port);
        }
    }

    public void insertHop(String operation, String port, String objectId, String key, String value) {
        Log.d(TAG, "insertHop");
        new ClientTask().executeOnExecutor(
                    AsyncTask.THREAD_POOL_EXECUTOR, operation, port, objectId, key, value);
    }

    public void queryHop(String operation, String port, String queryPort, String objectId, String key) {
        Log.d(TAG, "queryHop");
        new ClientTask().executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR, operation, port, queryPort, objectId, key);
    }

    public void queryAllHop(String operation, String port, String queryPort, String objectId, String key, String data) {
        Log.d(TAG, "queryAllHop");
        new ClientTask().executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR, operation, port, queryPort, objectId, key, data);
    }

    public void returnQuery(String operation, String queryPort, String key) {
        Log.d(TAG, "returnQuery");
        String value = queryLocal(key);
        new ClientTask().executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR, operation, queryPort, key, value);
    }

    public void returnQueryAll(String operation, String queryPort, String key, String data) {
        Log.d(TAG, "returnQueryAll");
        new ClientTask().executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR, operation, queryPort, key, data);
    }

    /*
        References:
        1. https://stackoverflow.com/questions/4943629/how-to-delete-a-whole-folder-and-content
     */
    public int deleteAllLocal() {
        File directory = new File(getContext().getFilesDir().toString());
        String[] filenames = directory.list();
        int size = filenames.length;
        for (String filename : filenames)
            new File(directory, filename).delete();
        return size;
    }

    public void deleteLocal(String filename) {
        File directory = new File(getContext().getFilesDir().toString());
        new File(directory, filename).delete();
    }

    private class ClientTask extends AsyncTask<String, String, Void> {
        @Override
        protected Void doInBackground(String... args) {
            try {
                String operation = args[0];
                String remotePort = args[1];
                Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                        Integer.parseInt(remotePort));
                PrintWriter out = new PrintWriter(socket.getOutputStream());
                //socket.setSoTimeout(SOCKET_TIMEOUT);

                String str = "";
                if (operation.equals("add_node")) {
                    String node_id = args[2];
                    str = operation + delimiter + node_id + delimiter + mMyPort + "\n";
                } else if (operation.equals("update_node")) {
                    String pred = args[2];
                    String succ = args[3];
                    str = operation + delimiter + pred + delimiter + succ + "\n";
                } else if (operation.equals("insert")) {
                    String objectId = args[2];
                    String key = args[3];
                    String value = args[4];
                    str = operation + delimiter + objectId + delimiter + key + delimiter + value + "\n";
                } else if (operation.equals("query")) {
                    String queryPort = args[2];
                    String objectId = args[3];
                    String key = args[4];
                    str = operation + delimiter + queryPort + delimiter + objectId + delimiter + key + "\n";
                } else if (operation.equals("return_query")) {
                    String key = args[2];
                    String value = args[3];
                    str = operation + delimiter + key + delimiter + value + "\n";
                } else if (operation.equals("query_all")) { //operation, port, queryPort, objectId, key, data
                    String queryPort = args[2];
                    String objectId = args[3];
                    String key = args[4];
                    String data = args.length == 6 ? args[5] : "";
                    str = "query" + delimiter + queryPort + delimiter + objectId + delimiter + key + delimiter + data + "\n";
                } else if (operation.equals("return_query_all")) {
                    String key = args[2];
                    String data = args[3];
                    str = operation + delimiter + key + delimiter + data + "\n";
                } else if (operation.equals("delete")) {
                    String queryPort = args[2];
                    String objectId = args[3];
                    String key = args[4];
                    str = operation + delimiter + queryPort + delimiter + objectId + delimiter + key + "\n";
                }

                Log.d(TAG, "Message sent from Client " + getClientName(mMyPort) +
                        " to Server " + getClientName(remotePort) + " : " + str);
                out.write(str);
                out.flush();

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                String reply = in.readLine();
                if (reply != null) {
                    String[] ack = reply.split(delimiter);
                    if (ack[0] != null) {
                        if (ack[0].equals("ack_query")) {
                            publishProgress(ack[1], ack.length == 3 ? ack[2] : "");
                        }
                        in.close();
                        out.close();
                        socket.close();
                    }
                }
                return null;
            } catch (SocketTimeoutException e) {
                Log.e(TAG, "ClientTask SocketTimeoutException");
            } catch (IOException e) {
                Log.e(TAG, "ClientTask Socket IOException");
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            if (values[0].equals("*")) {
                strToCursor(values[1]);
            } else {
                if (!values[1].isEmpty())
                    mKeyVal.put(values[0], values[1]);
            }
        }
    }

    public void strToCursor(String data) {
        MatrixCursor cursor = new MatrixCursor(new String[]{KEY_FIELD, VALUE_FIELD});

        if (!data.isEmpty()) {
            String[] pairs = data.split(";");
            for (String pair : pairs) {
                //Log.d(TAG, "pair: " + pair);
                String[] duo = pair.split(":");
                cursor.addRow(new String[]{duo[0], duo[1]});
            }
        }
        mCursor = cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        Log.v("delete", selection);

        if (selection.equals("@")) {
            return deleteAllLocal();
        }

        try {
            String objectId = genHash(selection);
            new ClientTask().executeOnExecutor(
                    AsyncTask.THREAD_POOL_EXECUTOR, "delete", mMyPort, mMyPort, objectId, selection);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Query NoSuchAlgorithmException");
        }
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Log.v("insert", values.toString());
        String key = values.getAsString(KEY_FIELD);
        String value = values.getAsString(VALUE_FIELD);

        try {
            String objectId = genHash(key);

            new ClientTask().executeOnExecutor(
                    AsyncTask.THREAD_POOL_EXECUTOR, "insert", mMyPort, objectId, key, value);

        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Insert NoSuchAlgorithmException");
        }
        return uri;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        Log.v("query", selection);

        if (selection.equals("chord")) {
            String str = mHead.port + " (" + mHead.id + ")";
            Node ptr = mHead.succ;
            while (ptr != mHead) {
                str += " -> " + ptr.port + " (" + ptr.id + ")";
                ptr = ptr.succ;
            }
            Log.d(TAG, "Chord: " + str);
            return null;
        }

        /*
            References:
            1. https://stackoverflow.com/questions/8646984/how-to-list-files-in-an-android-directory/8647397#8647397
         */
        if (selection.equals("@")) {
            File directory = new File(getContext().getFilesDir().toString());
            File[] files = directory.listFiles();
            MatrixCursor cursor = new MatrixCursor(new String[]{KEY_FIELD, VALUE_FIELD});
            for (File file : files)
            {
                cursor.addRow(new String[]{file.getName(), queryLocal(file.getName())});
            }
            return cursor;
        }

        try {
            String objectId = genHash(selection);

            new ClientTask().executeOnExecutor(
                    AsyncTask.THREAD_POOL_EXECUTOR, "query", mMyPort, mMyPort, objectId, selection);

            if (selection.equals("*")) {
                while (mCursor == null) {

                }
                MatrixCursor tempCursor = mCursor;
                mCursor = null;
                return tempCursor;
            } else {
                while (!mKeyVal.containsKey(selection)) {

                }
            }

            MatrixCursor cursor = new MatrixCursor(new String[]{KEY_FIELD, VALUE_FIELD});
            cursor.addRow(new String[]{selection, mKeyVal.get(selection)});
            mKeyVal.remove(selection);
            return cursor;

        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Query NoSuchAlgorithmException");
        }
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub
        return 0;
    }

    private String genHash(String input) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sha1Hash = sha1.digest(input.getBytes());
        Formatter formatter = new Formatter();
        for (byte b : sha1Hash) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    private String getClientName(String portNumber) {
        switch (Integer.valueOf(portNumber)) {
            case 11108:
                return "1";
            case 11112:
                return "2";
            case 11116:
                return "3";
            case 11120:
                return "4";
            case 11124:
                return "5";
            default: return "";
        }
    }

    class Node {
        String id;
        String port;
        Node pred;
        Node succ;

        Node(String id, String port) {
            this.id = id;
            this.port = port;
            pred = null;
            succ = null;
        }
    }
}
