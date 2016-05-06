package com.alexstarc.imageloader;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;

import com.alexstarc.imageloader.fragments.ImageDisplayDialogFragment;
import com.alexstarc.imageloader.service.LoaderService;

public class MainActivity extends AppCompatActivity {

    /** To save during config changes */
    private static final String EXTRA_SAVED_LOADING_STATE = "loadingState";
    private static final String TAG = "MainActivity";
    private static final String IMAGE_DIALOG_TAG = "imageDialog";

    private FloatingActionButton mFab = null;

    // Simple UI structure in case if further support / improvements are needed, then
    private EditText mUrlEdit = null;
    private TextView mUrlEditLabel = null;
    private View mLoadingContainer = null;

    // Simple state holder, in case of more complex UIs it would be better to use separate fragments or viewPager or
    // proper state manager
    private boolean mLoadingUi = false;

    /**
     * For testing we can predefine URL, it'll be faster than typing in mobile every time.
     * For production just set to null. Can be set via build.gradle to have special build variant with this variable defined.
     */
    private static final String TEST_URL = "http://dummyimage.com/2600x2400/000/fff.png&text=test";
            //"http://dummyimage.com/2600x2400/000/fff.png&text=test"
            //"http://placehold.it/120x120";
            //"http://dummyimage.com/600x400/000/fff&text=test";
            //"https://placeholdit.imgix.net/~text?txtsize=15&txt=image1&w=120&h=120"; Fails with 400 Bad Request for some reason
            //"http://placehold.it/120x120&text=image1";
            //"http://lorempixel.com/400/200/";

    private LoadBroadcastReceiver mReceiver = null;
    /** Stores activity paused state */
    private boolean mPaused;

    /** Static inner classes are preferable */
    private static final class LoadBroadcastReceiver extends BroadcastReceiver {

        // Interface here will work better
        private MainActivity mActivity = null;

        /**
         * @param activity parent activity
         */
        void setActivity(final MainActivity activity) {
            mActivity = activity;
        }

        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();

            if (LoaderService.ACTION_LOAD_RESULT.equals(action)) {
                final int status = intent.getIntExtra(LoaderService.EXTRA_STATUS, -1);
                final String path = intent.getStringExtra(LoaderService.EXTRA_PATH);
                final String rotatedPath = intent.getStringExtra(LoaderService.EXTRA_ROTATED_PATH);

                Log.d(TAG, "LoadResult " + status + " at [" + path + "]");

                if (status != HttpURLConnection.HTTP_OK) {
                    Toast.makeText(context, String.format(context.getString(R.string.error_loading), status), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(context, context.getResources().getText(R.string.saved_to) + path, Toast.LENGTH_LONG).show();

                    if (mActivity != null && !TextUtils.isEmpty(rotatedPath)) {
                        mActivity.showRotatedImageDialog(rotatedPath);
                    } else {
                        Toast.makeText(context, R.string.no_rotated_image, Toast.LENGTH_LONG).show();
                    }
                }
                if (mActivity != null) {
                    mActivity.showInput();
                }
            }
        }
    }

    /**
     * Creates dialog
     *
     * @param path to rotated image
     */
    private void showRotatedImageDialog(final String path) {
        final ImageDisplayDialogFragment dialog = ImageDisplayDialogFragment.newInstance(path);

        dialog.show(getSupportFragmentManager(), IMAGE_DIALOG_TAG);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);

        setSupportActionBar(toolbar);

        mFab = (FloatingActionButton) findViewById(R.id.fab);
        mUrlEdit = (EditText) findViewById(R.id.urlEdit);
        mUrlEditLabel = (TextView) findViewById(R.id.urlEditLabel);
        mLoadingContainer = findViewById(R.id.loadingContainer);

        if (!TextUtils.isEmpty(TEST_URL)) {
            mUrlEdit.setText(TEST_URL);
        }

        if (mFab != null) {
            mFab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (mUrlEdit == null || mUrlEdit.getText() == null
                            || TextUtils.isEmpty(mUrlEdit.getText().toString())) {
                        Toast.makeText(getApplicationContext(),
                                R.string.url_not_provided, Toast.LENGTH_LONG).show();
                        return;
                    }

                    final String urlStr = mUrlEdit.getText().toString();

                    /* TODO: Android doesn't have good Url validation API,
                       but Apache-commons has, so it can be used for fast format check here */

                    Toast.makeText(getApplicationContext(),
                            getString(R.string.started_loading) + urlStr,
                            Toast.LENGTH_SHORT).show();
                    startLoad(mUrlEdit.getText().toString());
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        final Intent intent = new Intent(LoaderService.Action.STOP_LOADING.name());

        intent.setClass(getApplicationContext(), LoaderService.class);
        startService(intent);
        mLoadingUi = false;

        if (mReceiver != null) {
            LocalBroadcastManager.getInstance(getApplicationContext())
                    .unregisterReceiver(mReceiver);
        }

        mPaused = true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        final IntentFilter filter = new IntentFilter(LoaderService.ACTION_LOAD_RESULT);

        mReceiver = new LoadBroadcastReceiver();

        mReceiver.setActivity(this);
        LocalBroadcastManager.getInstance(getApplicationContext())
                .registerReceiver(mReceiver, filter);

        mPaused = false;
    }

    /**
     * Starts LoaderService with provided url and sets loading UI
     *
     * @param urlStr to load image from
     */
    private void startLoad(final String urlStr) {
        final Intent intent = new Intent(LoaderService.Action.LOAD_IMAGE.name());

        intent.setClass(getApplicationContext(), LoaderService.class);
        intent.putExtra(LoaderService.EXTRA_URL, urlStr);

        startService(intent);

        showLoading();
    }

    /**
     * Shows loading UI
     */
    private void showLoading() {
        mLoadingUi = true;
        mFab.setEnabled(false);
        mUrlEdit.setVisibility(View.GONE);
        mUrlEditLabel.setVisibility(View.GONE);
        mLoadingContainer.setVisibility(View.VISIBLE);

        // Hide keyboard
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        // Seems to be needed after API 23
        imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
        imm.hideSoftInputFromWindow(mFab.getWindowToken(), 0);
    }

    private void showInput() {
        mLoadingUi = false;
        mFab.setEnabled(true);
        mUrlEdit.setVisibility(View.VISIBLE);
        mUrlEditLabel.setVisibility(View.VISIBLE);
        mLoadingContainer.setVisibility(View.GONE);
    }

    private boolean isPaused() {
        return mPaused;
    }
}
