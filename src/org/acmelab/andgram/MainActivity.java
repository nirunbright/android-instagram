package org.acmelab.andgram;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.CookieStore;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity
{
    private static final int CAMERA_PIC_REQUEST = 1;
    private static final int CROP_REQUEST = 2;
    private static final String TAG = "ANDGRAM";
    private static final String OUTPUT_DIR = "andgram";
    private static final String OUTPUT_FILE = "andgram.jpg";
    private static final int ID_MAIN = 1;

    private static final String UPLOAD_URL = "http://instagr.am/api/v1/media/upload/";
    private static final String CONFIGURE_URL = "https://instagr.am/api/v1/media/configure/";

    EditText txtCaption  = null;
    ImageView imageView = null;
    Button uploadButton = null;

    private DefaultHttpClient httpClient = null;
    private Uri imageUri = null;
    private boolean imageReady = false;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        httpClient = new DefaultHttpClient();
        httpClient.getParams().setParameter("http.useragent", "Instagram");

        txtCaption  = (EditText)findViewById(R.id.txtCaption);
        imageView = (ImageView)findViewById(R.id.imageView);
        uploadButton = (Button)findViewById(R.id.btnUpload);


        // create the output dir for us
        File outputDirectory = new File(Environment.getExternalStorageDirectory(), OUTPUT_DIR);
        outputDirectory.mkdirs();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_activity_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch( item.getItemId() ) {
            case R.id.clear:
                doClear();
                return true;
            case R.id.preferences:
                return true;
            case R.id.credentials:
                launchCredentials();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void launchCredentials() {
        SharedPreferences sharedPreferences = getSharedPreferences(LoginActivity.PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        editor.putBoolean("loginValid", false);
        editor.commit();

        Intent loginIntent = new Intent(getApplicationContext(), LoginActivity.class);
        startActivity(loginIntent);
    }

    public void takePicture(View view) {
        Log.i(TAG, "Taking picture");
        File outputFile = new File(Environment.getExternalStorageDirectory(), OUTPUT_DIR + "/" + OUTPUT_FILE);
        imageUri = Uri.fromFile(outputFile);
        Intent cameraIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
        startActivityForResult(cameraIntent, CAMERA_PIC_REQUEST);
    }

    public void doClear() {
        Log.i(TAG, "Clear image");
        if( imageReady ) {
            imageReady = false;
            findViewById(R.id.captionRow).setVisibility(View.INVISIBLE);
            ((BitmapDrawable)imageView.getDrawable()).getBitmap().recycle();
            imageView.setImageBitmap(null);
        }
    }

    public void startUpload(View view) {
        Log.i(TAG, "Starting async upload");
        if( !doLogin() ) {
            Toast.makeText(MainActivity.this, "Login failed", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(MainActivity.this, "Starting upload", Toast.LENGTH_SHORT).show();
            new UploadPhotoTask().execute();
        }
    }

    public boolean doLogin() {
        Log.i(TAG, "Doing login");

        // gather login info
        SharedPreferences sharedPreferences = getSharedPreferences(LoginActivity.PREFS_NAME, MODE_PRIVATE);
        Boolean loginValid = sharedPreferences.getBoolean("loginValid",false);

        if( !loginValid ) {
            launchCredentials();
            return false;
        }

        String username = sharedPreferences.getString("username","");
        String password = sharedPreferences.getString("password","");

        // create POST
        HttpPost httpPost = new HttpPost(LoginActivity.LOGIN_URL);
        List<NameValuePair> postParams = new ArrayList<NameValuePair>();
        postParams.add(new BasicNameValuePair("username", username));
        postParams.add(new BasicNameValuePair("password", password));
        postParams.add(new BasicNameValuePair("device_id", "0000"));

        try {
            httpPost.setEntity(new UrlEncodedFormEntity(postParams, HTTP.UTF_8));
            HttpResponse httpResponse = httpClient.execute(httpPost);

            // test result code
            if( httpResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK ) {
                Log.i(TAG, "Login HTTP status fail");
                return false;
            }

            // test json response
            HttpEntity httpEntity = httpResponse.getEntity();
            if( httpEntity != null ) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(httpEntity.getContent(), "UTF-8"));
                String json = reader.readLine();
                JSONTokener jsonTokener = new JSONTokener(json);
                JSONObject jsonObject = new JSONObject(jsonTokener);
                Log.i(TAG,"JSON: " + jsonObject.toString());

                String loginStatus = jsonObject.getString("status");

                if( !loginStatus.equals("ok") ) {
                    Log.e(TAG, "JSON status not ok: " + jsonObject.getString("status"));
                    return false;
                }
            }

        } catch( IOException e ) {
            Log.e(TAG, "HttpPost error: " + e.toString());
            return false;
        } catch( JSONException e ) {
            Log.e(TAG, "JSON parse error: " + e.toString());
            return false;
        }

        return true;
    }

    public Map<String, String> doUpload() {
        Log.i(TAG, "Upload");
        Long timeInMilliseconds = System.currentTimeMillis()/1000;
        String timeInSeconds = timeInMilliseconds.toString();
        MultipartEntity multipartEntity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE);
        Map returnMap = new HashMap<String, String>();

        // check for cookies
        if( httpClient.getCookieStore() == null ) {
            returnMap.put("result", "Not logged in");
            return returnMap;
        }

        try {
            // create multipart data
            File imageFile = new File(imageUri.getPath());
            FileBody partFile = new FileBody(imageFile);
            StringBody partTime = new StringBody(timeInSeconds);
            multipartEntity.addPart("photo", partFile );
            multipartEntity.addPart("device_timestamp", partTime);
        } catch ( Exception e ) {
            Log.e(TAG,"Error creating mulitpart form: " + e.toString());
            //Toast.makeText(MainActivity.this, "Create multipart failed " + e.toString(), Toast.LENGTH_LONG).show();
            returnMap.put("result", "Error creating mulitpart form: " + e.toString());
            return returnMap;
        }

        // upload
        try {
            HttpPost httpPost = new HttpPost(UPLOAD_URL);
            httpPost.setEntity(multipartEntity);
            HttpResponse httpResponse = httpClient.execute(httpPost);
            HttpEntity httpEntity = httpResponse.getEntity();
            Log.i(TAG, "Upload status: " + httpResponse.getStatusLine());

            // test result code
            if( httpResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK ) {
                Log.e(TAG, "Login HTTP status fail: " + httpResponse.getStatusLine().getStatusCode());
                returnMap.put("result", "HTTP status error: " + httpResponse.getStatusLine().getStatusCode() );
                return returnMap;
            }

            // test json response
            // should look like
            /*
            {"status": "ok"}
            */
            if( httpEntity != null ) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(httpEntity.getContent(), "UTF-8"));
                String json = reader.readLine();
                JSONTokener jsonTokener = new JSONTokener(json);
                JSONObject jsonObject = new JSONObject(jsonTokener);
                Log.i(TAG,"JSON: " + jsonObject.toString());

                String loginStatus = jsonObject.getString("status");

                if( !loginStatus.equals("ok") ) {
                    Log.e(TAG, "JSON status not ok: " + jsonObject.getString("status"));
                    returnMap.put("result", "JSON status not ok: " + jsonObject.getString("status") );
                    return returnMap;
                }
            }
        } catch( Exception e ) {
            Log.e(TAG, "HttpPost exception: " + e.toString());
            //Toast.makeText(MainActivity.this, "Upload failed " + e.toString(), Toast.LENGTH_LONG).show();
            returnMap.put("result", "HttpPost exception: " + e.toString());
            return returnMap;
        }

        // configure / comment
        try {
            HttpPost httpPost = new HttpPost(CONFIGURE_URL);
            String partComment = txtCaption.getText().toString();
            List<NameValuePair> postParams = new ArrayList<NameValuePair>();
            postParams.add(new BasicNameValuePair("device_timestamp", timeInSeconds));
            postParams.add(new BasicNameValuePair("caption", partComment));
            httpPost.setEntity(new UrlEncodedFormEntity(postParams, HTTP.UTF_8));
            HttpResponse httpResponse = httpClient.execute(httpPost);
            HttpEntity httpEntity = httpResponse.getEntity();

            // test result code
            if( httpResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK ) {
                Log.e(TAG, "Upload comment fail: " + httpResponse.getStatusLine().getStatusCode());
                returnMap.put("result", "Upload comment fail: " + httpResponse.getStatusLine().getStatusCode() );
                return returnMap;
            }

            returnMap.put("result", "ok");
            return returnMap;
        } catch( Exception e ) {
            Log.e(TAG, "HttpPost comment error: " + e.toString());
            returnMap.put("result", "HttpPost comment error: " + e.toString());
            return returnMap;
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if( resultCode == Activity.RESULT_OK ) {
            switch( requestCode ) {
                case CAMERA_PIC_REQUEST:
                    Log.i(TAG, "Camera returned");
                    getContentResolver().notifyChange(imageUri, null);
                    ContentResolver contentResolver = getContentResolver();
                    Bitmap imageBitmap;
                    LinearLayout captionRow = (LinearLayout)findViewById(R.id.captionRow);
                    try {
                        captionRow.setVisibility(View.VISIBLE);

                        Drawable toRecycle =  imageView.getDrawable();
                        if( toRecycle != null ) {
                            Bitmap bitmapToRecycle = ((BitmapDrawable)imageView.getDrawable()).getBitmap();
                            if( bitmapToRecycle != null ) {
                                ((BitmapDrawable)imageView.getDrawable()).getBitmap().recycle();
                            }
                        }
                        imageBitmap = android.provider.MediaStore.Images.Media.getBitmap(contentResolver, imageUri);
                        imageView.setImageBitmap(imageBitmap);
                        Log.i(TAG, "Image: " + imageUri.toString());
                        imageReady = true;

                        // turn on upload button
                        uploadButton.setEnabled(true);
                    } catch ( Exception e ) {
                        Toast.makeText(MainActivity.this, "Camera error", Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Camera error: " + e.toString() );
                        captionRow.setVisibility(View.INVISIBLE);
                        imageReady = false;
                        imageUri = null;
                    }
                    break;
                case CROP_REQUEST:
                    break;
                default:

            }
        }
    }

    private class UploadPhotoTask extends AsyncTask<Void, Void, Map<String, String>> {

        protected void onPreExecute() {
            Toast.makeText(MainActivity.this, "Uploading in the background", Toast.LENGTH_SHORT).show();
        }

        protected Map<String,String> doInBackground(Void... voids) {
            return doUpload();
        }

        protected void onPostExecute(Map<String,String> resultMap) {
            Toast.makeText(MainActivity.this, resultMap.get("result"), Toast.LENGTH_SHORT).show();
        }
    }

}
