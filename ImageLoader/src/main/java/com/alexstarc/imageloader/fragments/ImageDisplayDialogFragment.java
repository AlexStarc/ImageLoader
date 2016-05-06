package com.alexstarc.imageloader.fragments;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.lang.ref.WeakReference;

import com.alexstarc.imageloader.R;

/**
 * Dialog fragment to show rotated image
 */
public class ImageDisplayDialogFragment extends DialogFragment {
    /** Fragment argument to hold filename for loading */
    private static final String KEY_FILENAME = "filename";
    private String mPath = null;
    private AsyncTask<Void, Void, Void> mLoadingTask = null;

    /**
     * Created image showing dialog.
     *
     * @param path to the file, assumed to be no more than screen size
     *
     * @return dialog
     */
    public static ImageDisplayDialogFragment newInstance(final String path) {
        final Bundle args = new Bundle();
        final ImageDisplayDialogFragment f = new ImageDisplayDialogFragment();

        args.putString(KEY_FILENAME, path);
        f.setArguments(args);

        return f;
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPath = getArguments().getString(KEY_FILENAME);
    }

    @Override
    public void onPause() {
        super.onPause();

        if (mLoadingTask != null) {
            mLoadingTask.cancel(true);
        }

        dismiss();
    }

    @Nullable
    @Override
    public View onCreateView(final LayoutInflater inflater, @Nullable final ViewGroup container, @Nullable final Bundle savedInstanceState) {
        // TODO: probably can start earlier
        mLoadingTask = new ShowRotatedImageTask().execute();

        View v = inflater.inflate(R.layout.image_dialog, container, false);

        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                dismiss();
            }
        });

        Toast.makeText(getActivity(), R.string.tap_anywhere_to_close, Toast.LENGTH_LONG).show();

        return v;

    }

    /**
     * Simple AsyncTask to show rotated image in the dialog
     */
    private class ShowRotatedImageTask extends AsyncTask<Void, Void, Void> {
        /** Loaded bitmap */
        private Bitmap mBitmap = null;

        @Override
        protected Void doInBackground(final Void... params) {
            if (new File(mPath).exists()) {
                mBitmap = BitmapFactory.decodeFile(mPath);
            }

            return null;
        }

        @Override
        protected void onPostExecute(final Void aVoid) {
            super.onPostExecute(aVoid);

            setImage(mBitmap);
        }
    }

    /**
     * Sets image to dialogs UI
     *
     * @param bitmap
     */
    private void setImage(final Bitmap bitmap) {
        if (getDialog() != null) {
            getDialog().findViewById(R.id.progress).setVisibility(View.GONE);
            getDialog().findViewById(R.id.image).setVisibility(View.VISIBLE);
            ((ImageView)getDialog().findViewById(R.id.image)).setImageBitmap(bitmap);
        }

        mLoadingTask = null;
    }
}
