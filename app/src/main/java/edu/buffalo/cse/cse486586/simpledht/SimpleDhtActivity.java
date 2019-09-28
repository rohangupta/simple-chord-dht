package edu.buffalo.cse.cse486586.simpledht;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Activity;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class SimpleDhtActivity extends Activity {
    public static final String TAG = SimpleDhtActivity.class.getSimpleName();
    private static final String authority = "edu.buffalo.cse.cse486586.simpledht.provider";
    private static final String KEY_FIELD = "key";
    private static final String VALUE_FIELD = "value";
    public TextView mTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_dht_main);

        TelephonyManager tel = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        String myPort = String.valueOf((Integer.parseInt(portStr) * 2));
        
        mTextView = (TextView) findViewById(R.id.textView1);
        mTextView.setMovementMethod(new ScrollingMovementMethod());
        findViewById(R.id.button3).setOnClickListener(
                new OnTestClickListener(mTextView, getContentResolver()));

        findViewById(R.id.button1).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Task().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "@");
            }
        });

        findViewById(R.id.button2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Task().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "*");
            }
        });

        Button showChord = (Button) findViewById(R.id.buttonNew);
        showChord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Task().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "chord");
            }
        });

        if (myPort.equals("11108")) {
            showChord.setVisibility(View.VISIBLE);
        }
    }

    private class Task extends AsyncTask<String, String, Void> {

        @Override
        protected Void doInBackground(String... strings) {
            if (strings[0].equals("*")) {
                testDelete(strings[0]);
            } else {
                String str = testQuery(strings[0]);
                publishProgress(str);
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            mTextView.append(values[0]);
        }
    }

    private Uri buildUri(String scheme, String authority) {
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.authority(authority);
        uriBuilder.scheme(scheme);
        return uriBuilder.build();
    }

    private String testQuery(String input) {
        ContentResolver mContentResolver = getContentResolver();
        Cursor resultCursor = mContentResolver.query(buildUri("content", authority), null,
                input, null, null);

        if (resultCursor == null)
            return "null";

        int keyIndex = resultCursor.getColumnIndex(KEY_FIELD);
        int valueIndex = resultCursor.getColumnIndex(VALUE_FIELD);
        if (keyIndex == -1 || valueIndex == -1) {
            Log.e(TAG, "Wrong columns");
            resultCursor.close();
            return "wrong columns";
        }

        String str = "";
        while (resultCursor.moveToNext()) {
            String key = resultCursor.getString(keyIndex);
            String value = resultCursor.getString(valueIndex);
            str += key + " : " + value + "\n";
        }
        resultCursor.close();
        return str;
    }

    private void testDelete(String input) {
        ContentResolver mContentResolver = getContentResolver();
        int length = mContentResolver.delete(buildUri("content", authority),
                input, null);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_simple_dht_main, menu);
        return true;
    }

}
