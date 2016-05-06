package com.alexstarc.imageloader.service;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Simple local service to load image and save into provided directory
 */
public class LoaderService extends IntentService {
    private static final String TAG = "LoaderService";

    /** Extra to hold url to load image from */
    public static final String EXTRA_URL = "";
    /** Action to be handled by result receiver */
    public static final String ACTION_LOAD_RESULT = "com.alexstarc.imageloader.LOAD_RESULT";
    /** Return extra, int from HttpUrlConnection statuses */
    public static final String EXTRA_STATUS = "statusExtra";
    /** Return extra, String with full path to saved file */
    public static final String EXTRA_PATH = "pathExtra";
    /** Return extra, String with full path to saved rotated file */
    public static final String EXTRA_ROTATED_PATH = "rotatedPathExtra";

    /** Chunk size for files loading */
    private static final int CHUNK_SIZE = 1024; // bytes
    /** Apps folder to store downloaded images */
    private static final String IMAGES_FOLDER = "img";
    /** Downloaded image name */
    private static final String DEFAULT_FILENAME = "image";
    /** Rotated image default name */
    private static final String DEFAULT_ROTATE_FILENAME = "rotated_image.jpg";
    /** Maximum supported file size, bytes */
    private static final int FILE_SIZE_LIMIT = 20 * 1024 * 1024; // 20 MB
    private static final int CONNECT_TIMEOUT = 15000; // ms
    /** Degrees to rotate image for */
    private static final float ROTATION_DEGRESS = 180;

    private AtomicBoolean mIsStopRequested = new AtomicBoolean(false);

    /** Supported actions enum for client to avoid hard-coded action or string constants */
    public enum Action {
        LOAD_IMAGE,
        STOP_LOADING
    }

    // TODO: limit number of possible image types
    private final static String TYPE_JPG = "jpg";
    private final static String TYPE_JPEG = "jpeg";
    private final static String TYPE_PNG = "png";
    private final static String TYPE_BMP = "bmp";

    /**
     * Creates an IntentService.  Invoked by your subclass's constructor.
     *
     * @param name Used to name the worker thread, important only for debugging.
     */
    public LoaderService(final String name) {
        super(name);
    }

    /**
     * Default constructor
     */
    public LoaderService() {
        super(LoaderService.class.getSimpleName());
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        final String actionStr = intent.getAction();
        Action action = null;

        try {
            action = Action.valueOf(actionStr);
        } catch(IllegalArgumentException e) {
            Log.e(TAG, "Invalid action received");
            return;
        }

        switch (action) {
            case LOAD_IMAGE:
                mIsStopRequested.set(false);
                loadImage(intent.getStringExtra(EXTRA_URL));
                break;

            case STOP_LOADING:
                // Not to be handled here
            default:
                break;
        }
    }

    /**
     * Loads image from provided
     * @param urlStr to load image from
     */
    private void loadImage(final String urlStr) {
        // Here DownloadManager will be easiest way probably
        InputStream input = null;
        HttpURLConnection connection = null;
        OutputStream output = null;
        boolean connected = false;
        String currUrlStr = urlStr;
        int status = HttpURLConnection.HTTP_OK;
        String fileName = "";

        try {
            while (!connected) {
                URL url = new URL(currUrlStr);

                if (url.getProtocol().toLowerCase().equals("https")) {
                    trustAllHosts();
                    Log.d(TAG, "Trust all hosts");

                    final HttpsURLConnection httpsConnection = (HttpsURLConnection) url.openConnection();

                    httpsConnection.setHostnameVerifier(DO_NOT_VERIFY);
                    connection = httpsConnection;
                } else {
                    connection = (HttpURLConnection) url.openConnection();
                }

                connection.setDoOutput(true);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                // Note that in some Android versions UrlConnection prepares silent retries,
                // so with one-time links it might be issue (second request fails before first one).
                // It's better to have read timeout no less than connection one
                connection.setReadTimeout(CONNECT_TIMEOUT);
                connection.setInstanceFollowRedirects(true);
                connection.connect();

                status = connection.getResponseCode();

                // Get location header
                switch (status) {
                    case HttpURLConnection.HTTP_OK:
                        connected = true;
                        break;

                    case HttpURLConnection.HTTP_MOVED_PERM:
                    case HttpURLConnection.HTTP_MOVED_TEMP:
                        // Obtain new URL and try connect again
                        final String location = connection.getHeaderField("location");
                        currUrlStr = new URL(url, location).toExternalForm();
                        Log.d(TAG, "Try URL " + currUrlStr);
                        break;

                    default:
                        returnStatus(status, "", "");
                        Log.e(TAG, "Connection failed " + status + " returned " + connection.getResponseMessage()
                                + " / Error Stream: " + new Scanner(connection.getErrorStream()).useDelimiter("\\A").next());
                        return;
                }
            }

            final String contentType = connection.getContentType();
            final String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType);

            if (TextUtils.isEmpty(contentType)) {
                // Here and later, return empty string since returning null is not best practice
                status = HttpURLConnection.HTTP_NO_CONTENT;
                returnStatus(HttpURLConnection.HTTP_NO_CONTENT, "", "");
                Log.e(TAG, "Empty content type");
                return;
            }

            final String normExt = extension.toLowerCase();

            if (!TYPE_JPEG.equals(normExt)
                    && !TYPE_JPG.equals(normExt)
                    && !TYPE_BMP.equals(normExt)
                    && !TYPE_PNG.equals(normExt)) {
                status = HttpURLConnection.HTTP_NO_CONTENT;
                returnStatus(HttpURLConnection.HTTP_NO_CONTENT, "", "");
                Log.e(TAG, "Wrong content type " + normExt);
                return;
            }

            final File outFolder = new File(getApplicationContext().getFilesDir().getAbsolutePath() + "/" + IMAGES_FOLDER);

            //noinspection ResultOfMethodCallIgnored
            outFolder.mkdir();
            cleanFolder(outFolder);

            // Let's store with same name, so only one file will exist
            fileName = outFolder.getAbsolutePath() + "/" + DEFAULT_FILENAME + "." + normExt;
            final int fileLength = connection.getContentLength();

            // Let's limit size of file to 20 MB, seems all above cannot be an image in a real world
            if (fileLength >= FILE_SIZE_LIMIT) {
                status = HttpURLConnection.HTTP_NO_CONTENT;
                returnStatus(HttpURLConnection.HTTP_NO_CONTENT, "", "");
                Log.e(TAG, "Wrong content size " + fileLength);
                return;
            }

            // download the file
            input = new BufferedInputStream(connection.getInputStream());
            output = new FileOutputStream(fileName);

            byte data[] = new byte[CHUNK_SIZE];
            int count;

            while ((count = input.read(data)) != -1 && !mIsStopRequested.get()) {
                output.write(data, 0, count);
            }

            output.flush();
        } catch (MalformedURLException e) {
            Log.e(TAG, "Malformed URL was provided " + e);
            status = HttpURLConnection.HTTP_BAD_REQUEST;
            returnStatus(HttpURLConnection.HTTP_BAD_REQUEST, "", "");
        } catch (IOException e) {
            Log.e(TAG, "Failed to download " + e);
            status = HttpURLConnection.HTTP_INTERNAL_ERROR;
            returnStatus(HttpURLConnection.HTTP_INTERNAL_ERROR, "", "");
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close output " + e);
                }
            }
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close input " + e);
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }

        String rotatedImagePath = "";

        if (status == HttpURLConnection.HTTP_OK) {
            rotatedImagePath = saveRotatedImage(fileName);
        }

        returnStatus(status, fileName, rotatedImagePath);
    }

    /**
     * Decodes and rotates provided file by name to be displayed on the screen (for full screen)
     *
     * @param fileName to rotate
     *
     * @return path to rotated image
     */
    private String saveRotatedImage(final String fileName) {
        String path = getCacheDir().getPath() + "/" + DEFAULT_ROTATE_FILENAME;
        final File outFile = new File(path);

        if (outFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
        }

        try {
            outFile.createNewFile();
        } catch (IOException e) {
            Log.wtf(TAG, "Failed to create file!");
        }

        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();

        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(fileName, options);

        final WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        final Display display = wm.getDefaultDisplay();
        final Point screenSize = new Point();

        display.getSize(screenSize);

        // Calculate inSampleSize
        int inSampleSize = 1;

        // Trivial algorithm to find closes inSampleSize which is power of 2
        if (options.outHeight > screenSize.y || options.outWidth > screenSize.x) {
            final int halfHeight = options.outHeight / 2;
            final int halfWidth = options.outWidth / 2;

            while ((halfHeight / inSampleSize) >= screenSize.x
                    && (halfWidth / inSampleSize) >= screenSize.y) {
                inSampleSize *= 2;
            }
        }

        options.inSampleSize = inSampleSize;

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;

        // Here don't expect OutOfMemory since
        final Bitmap orginalBitmap = BitmapFactory.decodeFile(fileName, options);
        final Matrix matrix = new Matrix();
        matrix.postRotate(ROTATION_DEGRESS);

        final Bitmap rotatedBitmap = Bitmap.createBitmap(orginalBitmap, 0, 0, orginalBitmap.getWidth(), orginalBitmap.getHeight(), matrix, true);
        FileOutputStream fileOutputStream = null;

        try {
            fileOutputStream = new FileOutputStream(outFile);
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fileOutputStream);
            fileOutputStream.flush();
            fileOutputStream.close();
        } catch (FileNotFoundException e) {
            Log.wtf(TAG, "File not found!");
        } catch (IOException e) {
            Log.e(TAG, "Failed to save rotated image " + e);
        } finally {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close output " + e);
                }
            }
        }

        return outFile.getPath();
    }

    /**
     * Cleans contents of provided folder
     *
     * @param outFolder to be cleaned
     */
    private void cleanFolder(final File outFolder) {
        if (outFolder.isDirectory()) {
            for (File child : outFolder.listFiles()) {
                Log.d(TAG, "Removed " + child.getName() + " " + child.delete());
            }
        }
    }

    // Use simple local broadcast to deliver results. In case if there's some performance issue
    // can use more complex ways like ResultReceiver, Messenger etc.
    /**
     * Sends finish callback.
     *
     * @param status Http status of request, 200 is OK
     * @param path of saved file
     * @param rotatedPath of rotated saved file
     */
    private void returnStatus(final int status, final String path, final String rotatedPath) {
        Log.d(TAG, "returnStatus " + status + " on " + path);

        final Intent intent = new Intent(ACTION_LOAD_RESULT);

        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_PATH, path);
        intent.putExtra(EXTRA_ROTATED_PATH, rotatedPath);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    // Use onStartCommand callback in order to handle STOP_LOADING action properly
    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (Action.STOP_LOADING.name().equals(intent.getAction())) {
            mIsStopRequested.set(true);
        }

        return super.onStartCommand(intent, flags, startId);
    }

    // START: Trust all certificates, under cc by-sa 3.0 from Ulrich Scheller at http://stackoverflow.com/a/1000205/657487

    // always verify the host - don't check for certificate
    final static HostnameVerifier DO_NOT_VERIFY = new HostnameVerifier() {
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    /**
     * Trust every server - don't check for any certificate
     */
    private static void trustAllHosts() {
        // Create a trust manager that does not validate certificate chains
        final TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[] {};
            }

            public void checkClientTrusted(X509Certificate[] chain,
                                           String authType) throws CertificateException {
            }

            public void checkServerTrusted(X509Certificate[] chain,
                                           String authType) throws CertificateException {
            }
        } };

        // Install the all-trusting trust manager
        try {
            final SSLContext sc = SSLContext.getInstance("TSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection
                    .setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // END: Trust all certificates
}
