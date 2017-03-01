package example.chatea.servicecamera;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.JavaCameraView;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.imgproc.Imgproc;

import static android.media.RingtoneManager.getDefaultUri;

public class CameraService extends Service implements CvCameraViewListener2 {
    private static final String TAG = CameraService.class.getSimpleName();

    public static final String RESULT_RECEIVER = "resultReceiver";
    public static final String VIDEO_PATH = "recordedVideoPath";

    public static final int RECORD_RESULT_OK = 0;
    public static final int RECORD_RESULT_DEVICE_NO_CAMERA= 1;
    public static final int RECORD_RESULT_GET_CAMERA_FAILED = 2;
    public static final int RECORD_RESULT_ALREADY_RECORDING = 3;
    public static final int RECORD_RESULT_NOT_RECORDING = 4;

    private static final String START_SERVICE_COMMAND = "startServiceCommands";
    private static final int COMMAND_NONE = -1;
    private static final int COMMAND_START_RECORDING = 0;
    private static final int COMMAND_STOP_RECORDING = 1;
    private static final int COMMAND_NOTIFICATION = 2;

    private Camera mCamera;
    private MediaRecorder mMediaRecorder;

    private boolean mRecording = false;
    private String mRecordingPath = null;

    // Use a layout id for a unique identifier
    private static int RECORD_NOTIFICATIONS = 123;

    // variable which controls the notification thread
    private ConditionVariable mCondition;

    // Face Detection
    private static final Scalar    FACE_RECT_COLOR     = new Scalar(0, 255, 0, 255);
    public static final int        JAVA_DETECTOR       = 0;
    public static final int        NATIVE_DETECTOR     = 1;

    private Mat                    mRgba;
    private Mat                    mGray;
    private File                   mCascadeFile;
    private CascadeClassifier      mJavaDetector;
    private DetectionBasedTracker  mNativeDetector;

    private int                    mDetectorType       = JAVA_DETECTOR;
    private String[]               mDetectorName;

    private float                  mRelativeFaceSize   = 0.2f;
    private int                    mAbsoluteFaceSize   = 0;

    private CameraBridgeViewBase   mOpenCvCameraView;
    GestureUtil gesutil = new GestureUtil();
    // end of FD

    public CameraService() {

        mDetectorName = new String[2];
        mDetectorName[JAVA_DETECTOR] = "Java";
        mDetectorName[NATIVE_DETECTOR] = "Native (tracking)";

        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    //Face Detection
    private BaseLoaderCallback  mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG, "OpenCV loaded successfully");

                    // Load native library after(!) OpenCV initialization
                    System.loadLibrary("detection_based_tracker");

                    try {
                        // load cascade file from application resources
                        //InputStream is = getResources().openRawResource(R.raw.lbpcascade_frontalface);
                        InputStream is = getResources().openRawResource(R.raw.cascade);
                        File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
                        //mCascadeFile = new File(cascadeDir, "lbpcascade_frontalface.xml");
                        mCascadeFile = new File(cascadeDir, "cascade.xml");
                        FileOutputStream os = new FileOutputStream(mCascadeFile);

                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                        is.close();
                        os.close();

                        mJavaDetector = new CascadeClassifier(mCascadeFile.getAbsolutePath());
                        if (mJavaDetector.empty()) {
                            Log.e(TAG, "Failed to load cascade classifier");
                            mJavaDetector = null;
                        } else
                            Log.i(TAG, "Loaded cascade classifier from " + mCascadeFile.getAbsolutePath());

                        mNativeDetector = new DetectionBasedTracker(mCascadeFile.getAbsolutePath(), 0);

                        cascadeDir.delete();

                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.e(TAG, "Failed to load cascade. Exception thrown: " + e);
                    }

                    mOpenCvCameraView.enableView();
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };
    public void onCameraViewStarted(int width, int height) {
        mGray = new Mat();
        mRgba = new Mat();
    }

    public void onCameraViewStopped() {
        mGray.release();
        mRgba.release();
    }

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {

        mRgba = inputFrame.rgba();
        mGray = inputFrame.gray();

        if (mAbsoluteFaceSize == 0) {
            int height = mGray.rows();
            if (Math.round(height * mRelativeFaceSize) > 0) {
                mAbsoluteFaceSize = Math.round(height * mRelativeFaceSize);
            }
            mNativeDetector.setMinFaceSize(mAbsoluteFaceSize);
        }

        MatOfRect faces = new MatOfRect();

        if (mDetectorType == JAVA_DETECTOR) {
            if (mJavaDetector != null) {
                mJavaDetector.detectMultiScale(mGray, faces, 1.1, 2, 2, // TODO: objdetect.CV_HAAR_SCALE_IMAGE
                        new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());
                Log.e(TAG, "Detection method is JAVA_DETECTOR!");
            }
        }
        else if (mDetectorType == NATIVE_DETECTOR) {
            if (mNativeDetector != null) {
                mNativeDetector.detect(mGray, faces);
                Log.e(TAG, "Detection method is NATIVE_DETECTOR!");
            }
        }
        else {
            Log.e(TAG, "Detection method is not selected!");
        }

        Rect[] facesArray = faces.toArray();
        for (int i = 0; i < facesArray.length; i++)
            Imgproc.rectangle(mRgba, facesArray[i].tl(), facesArray[i].br(), FACE_RECT_COLOR, 3);

        if(facesArray.length == 1)
        {
            gesutil.checkRect(facesArray[0]);
            getMouseState();
        } else if (facesArray.length == 0)
        {
            gesutil.checkPassFrame();
        }

        return mRgba;
    }

    public void getMouseState()
    {
        if(gesutil.isMoveRight())
        {   // send Right event
            Log.e(TAG, "Mouse: Send Right event");
        } else if (gesutil.isMoveLeft()){
            // send LEFT event
            Log.e(TAG, "Mouse: Send Left event");
        }
    }

    private void setMinFaceSize(float faceSize) {
        mRelativeFaceSize = faceSize;
        mAbsoluteFaceSize = 0;
    }

    private void setDetectorType(int type) {
        if (mDetectorType != type) {
            mDetectorType = type;

            if (type == NATIVE_DETECTOR) {
                Log.i(TAG, "Detection Based Tracker enabled");
                mNativeDetector.start();
            } else {
                Log.i(TAG, "Cascade detector enabled");
                mNativeDetector.stop();
            }
        }
    }
    // end of FD

    public static void startToStartRecording(Context context, ResultReceiver resultReceiver) {
        Intent intent = new Intent(context, CameraService.class);
        intent.putExtra(START_SERVICE_COMMAND, COMMAND_START_RECORDING);
        intent.putExtra(RESULT_RECEIVER, resultReceiver);
        context.startService(intent);
    }

    public static void startToStopRecording(Context context, ResultReceiver resultReceiver) {
        Intent intent = new Intent(context, CameraService.class);
        intent.putExtra(START_SERVICE_COMMAND, COMMAND_STOP_RECORDING);
        intent.putExtra(RESULT_RECEIVER, resultReceiver);
        context.startService(intent);
    }

    public static void startToNotification(Context context, ResultReceiver resultReceiver) {
        Intent intent = new Intent(context, CameraService.class);
        intent.putExtra(START_SERVICE_COMMAND, COMMAND_NOTIFICATION);
        intent.putExtra(RESULT_RECEIVER, resultReceiver);
        context.startService(intent);
    }

    /**
     * Used to take picture.
     */
    private Camera.PictureCallback mPicture = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            File pictureFile = Util.getOutputMediaFile(Util.MEDIA_TYPE_IMAGE);

            if (pictureFile == null) {
                return;
            }

            try {
                FileOutputStream fos = new FileOutputStream(pictureFile);
                fos.write(data);
                fos.close();
            } catch (FileNotFoundException e) {
            } catch (IOException e) {
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        //Face Detection
        mOpenCvCameraView = new JavaCameraView(getApplicationContext(), CameraBridgeViewBase.CAMERA_ID_ANY);
        mOpenCvCameraView.setCvCameraViewListener(this);

        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }

        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);




    }

    @Override
    public void onDestroy() {
        mNM.cancel(RECORD_NOTIFICATIONS);
        // Stop the thread from generating further notifications
        mCondition.open();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        switch (intent.getIntExtra(START_SERVICE_COMMAND, COMMAND_NONE)) {
            case COMMAND_START_RECORDING:
                handleStartRecordingCommand(intent);
                break;
            case COMMAND_STOP_RECORDING:
                handleStopRecordingCommand(intent);
                break;

            case COMMAND_NOTIFICATION:
                setNotification("Recording");
                break;
            default:
                throw new UnsupportedOperationException("Cannot start service with illegal commands");
        }


        return START_STICKY;
    }

    private void handleStartRecordingCommand(Intent intent) {
        final ResultReceiver resultReceiver = intent.getParcelableExtra(RESULT_RECEIVER);

        if (mRecording) {
            // Already recording
            resultReceiver.send(RECORD_RESULT_ALREADY_RECORDING, null);
            return;
        }
        mRecording = true;

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);

        wm.addView(mOpenCvCameraView, params);

        setNotification("Recording~");
        resultReceiver.send(RECORD_RESULT_OK, null);
        Log.d(TAG, "Recording is started");

/*
        if (Util.checkCameraHardware(this)) {
            mCamera = Util.getCameraInstance();
            if (mCamera != null) {
                    SurfaceView sv = new SurfaceView(this);

                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1,
                        WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.TRANSLUCENT);

                SurfaceHolder sh = sv.getHolder();

                sv.setZOrderOnTop(true);
                sh.setFormat(PixelFormat.TRANSPARENT);

                sh.addCallback(new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(SurfaceHolder holder) {
                        Camera.Parameters params = mCamera.getParameters();
                        mCamera.setParameters(params);
                        Camera.Parameters p = mCamera.getParameters();

                        List<Camera.Size> listSize;

                        listSize = p.getSupportedPreviewSizes();
                        Camera.Size mPreviewSize = listSize.get(2);
                        Log.v("TAG", "preview width = " + mPreviewSize.width
                                + " preview height = " + mPreviewSize.height);
                        p.setPreviewSize(mPreviewSize.width, mPreviewSize.height);

                        listSize = p.getSupportedPictureSizes();
                        Camera.Size mPictureSize = listSize.get(2);
                        Log.v("TAG", "capture width = " + mPictureSize.width
                                + " capture height = " + mPictureSize.height);
                        p.setPictureSize(mPictureSize.width, mPictureSize.height);
                        mCamera.setParameters(p);

                        try {
                            mCamera.setPreviewDisplay(holder);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        mCamera.startPreview();

                        mCamera.unlock();

                        mMediaRecorder = new MediaRecorder();
                        mMediaRecorder.setCamera(mCamera);

                        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

                        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));

                        mRecordingPath = Util.getOutputMediaFile(Util.MEDIA_TYPE_VIDEO).getPath();
                        mMediaRecorder.setOutputFile(mRecordingPath);

                        mMediaRecorder.setPreviewDisplay(holder.getSurface());

                        try {
                            mMediaRecorder.prepare();
                        } catch (IllegalStateException e) {
                            Log.d(TAG, "IllegalStateException when preparing MediaRecorder: " + e.getMessage());
                        } catch (IOException e) {
                            Log.d(TAG, "IOException when preparing MediaRecorder: " + e.getMessage());
                        }
                        mMediaRecorder.start();

                        setNotification("Recording~");
                        resultReceiver.send(RECORD_RESULT_OK, null);
                        Log.d(TAG, "Recording is started");
                    }

                    @Override
                    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    }

                    @Override
                    public void surfaceDestroyed(SurfaceHolder holder) {
                    }
                });


                wm.addView(sv, params);

            } else {
                Log.d(TAG, "Get Camera from service failed");
                resultReceiver.send(RECORD_RESULT_GET_CAMERA_FAILED, null);
            }
        } else {
            Log.d(TAG, "There is no camera hardware on device.");
            resultReceiver.send(RECORD_RESULT_DEVICE_NO_CAMERA, null);
        }*/
    }

    private void handleStopRecordingCommand(Intent intent) {
        ResultReceiver resultReceiver = intent.getParcelableExtra(RESULT_RECEIVER);

        if (!mRecording) {
            // have not recorded
            resultReceiver.send(RECORD_RESULT_NOT_RECORDING, null);
            return;
        }
/*
        mMediaRecorder.stop();
        mMediaRecorder.release();
        mCamera.stopPreview();
        mCamera.release();

        Bundle b = new Bundle();
        b.putString(VIDEO_PATH, mRecordingPath);

        mRecordingPath = null;

        resultReceiver.send(RECORD_RESULT_OK, b);
*/
        resultReceiver.send(RECORD_RESULT_OK, null);

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.removeView(mOpenCvCameraView);

        mRecording = false;
        Log.d(TAG, "recording is finished.");
    }


    private void setNotification(String notificationMessage) {

        int requestID = (int) System.currentTimeMillis();

        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent contentIntent = PendingIntent.getActivity(this, requestID,notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder mBuilder = new Notification.Builder(getApplicationContext())
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("My Notification")
                .setStyle(new Notification.BigTextStyle()
                        .bigText(notificationMessage))
                .setContentText(notificationMessage).setAutoCancel(true);
        mBuilder.setContentIntent(contentIntent);
        Notification notification = mBuilder.build();
        mNM.notify(RECORD_NOTIFICATIONS, notification);
    }

    private NotificationManager mNM;

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
