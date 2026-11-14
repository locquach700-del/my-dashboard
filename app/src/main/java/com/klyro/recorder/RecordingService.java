package com.klyro.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Surface;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

public class RecordingService extends Service {
    public static final String ACTION_START = "com.klyro.recorder.START";
    public static final String ACTION_PAUSE = "com.klyro.recorder.PAUSE";
    public static final String ACTION_RESUME = "com.klyro.recorder.RESUME";
    public static final String ACTION_STOP = "com.klyro.recorder.STOP";
    public static final String ACTION_TICK = "com.klyro.recorder.TICK";
    public static final String ACTION_SAVED = "com.klyro.recorder.SAVED";
    public static final String ACTION_ERROR = "com.klyro.recorder.ERROR";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_DATA = "data";
    private static final int NOTIFICATION_ID = 701;
    private static final String CHANNEL_ID = "klyro_recording";
    private static final int PLAYBACK_SAMPLE_RATE = 44100;
    private static final int PLAYBACK_CHANNELS = 2;
    private static final int PLAYBACK_BITRATE = 128000;

    private MediaRecorder recorder;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private File tempFile;
    private boolean audioOnly;
    private boolean internalAudioOnly;
    private boolean withMic;
    private volatile boolean paused;
    private volatile boolean finished;
    private long startedAt;
    private long pausedAt;
    private long accumulatedPause;
    private int requestedWidth;
    private int requestedHeight;
    private int fps;
    private int bitrate;

    private volatile AudioRecord playbackRecord;
    private MediaCodec playbackEncoder;
    private MediaMuxer playbackMuxer;
    private Thread playbackThread;
    private volatile boolean playbackRunning;
    private volatile String playbackFailure;
    private boolean muxerStarted;
    private int muxerTrack = -1;
    private long encodedAudioFrames;
    private final MediaCodec.BufferInfo audioBufferInfo = new MediaCodec.BufferInfo();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (finished) return;
            Intent tick = new Intent(ACTION_TICK).setPackage(getPackageName());
            tick.putExtra("elapsed", elapsed());
            tick.putExtra("paused", paused);
            sendBroadcast(tick);
            updateNotification();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PAUSE.equals(action)) pauseRecording();
        else if (ACTION_RESUME.equals(action)) resumeRecording();
        else if (ACTION_STOP.equals(action)) stopRecording();
        else if (ACTION_START.equals(action)) startRecording(intent);
        return START_NOT_STICKY;
    }

    private void startRecording(Intent intent) {
        if (recorder != null || playbackRunning || finished) return;
        String mode = intent.getStringExtra(EXTRA_MODE);
        audioOnly = "audio".equals(mode);
        internalAudioOnly = "internal_audio".equals(mode);
        withMic = intent.getBooleanExtra("mic", false);
        requestedWidth = intent.getIntExtra("width", 1280);
        requestedHeight = intent.getIntExtra("height", 720);
        fps = intent.getIntExtra("fps", 30);
        bitrate = intent.getIntExtra("bitrate", 2000000);

        int foregroundType = 0;
        if (!audioOnly && Build.VERSION.SDK_INT >= 29) foregroundType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (audioOnly && Build.VERSION.SDK_INT >= 30) foregroundType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (!audioOnly && !internalAudioOnly && withMic && Build.VERSION.SDK_INT >= 30) {
            foregroundType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, buildNotification(), foregroundType);
            } else if (Build.VERSION.SDK_INT >= 29 && !audioOnly) {
                startForeground(NOTIFICATION_ID, buildNotification(), foregroundType);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }

            boolean isAudioFile = audioOnly || internalAudioOnly;
            File folder = getExternalFilesDir(isAudioFile ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
            if (folder == null) folder = getFilesDir();
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create recording folder");
            if (folder.getUsableSpace() < 16L * 1024L * 1024L) {
                throw new IllegalStateException("Bộ nhớ trống quá thấp để bắt đầu ghi");
            }

            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
            tempFile = new File(folder, "Klyro_" + stamp + (isAudioFile ? ".m4a" : ".mp4"));

            if (internalAudioOnly) {
                startInternalAudioCapture(intent);
            } else {
                recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
                recorder.setOnErrorListener((mr, what, extra) -> handler.post(() -> {
                    if (!finished) {
                        broadcastError("Bộ ghi gặp lỗi; Klyro đang cố hoàn tất và lưu bản ghi.");
                        stopRecording();
                    }
                }));
                recorder.setOnInfoListener((mr, what, extra) -> {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                            || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        handler.post(() -> {
                            if (!finished) {
                                broadcastError("Đã chạm giới hạn an toàn; Klyro đang chốt và lưu tệp.");
                                stopRecording();
                            }
                        });
                    }
                });

                if (audioOnly) {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    recorder.setAudioSamplingRate(48000);
                    recorder.setAudioEncodingBitRate(128000);
                    recorder.setOutputFile(tempFile.getAbsolutePath());
                    configureRecorderFileLimit(folder);
                    recorder.prepare();
                    recorder.start();
                } else {
                    startVideoCapture(intent);
                }
            }

            startedAt = SystemClock.elapsedRealtime();
            paused = false;
            accumulatedPause = 0;
            handler.post(ticker);

            if (getSharedPreferences("klyro", MODE_PRIVATE).getBoolean("floating", true)
                    && Build.VERSION.SDK_INT >= 23
                    && android.provider.Settings.canDrawOverlays(this)) {
                startService(new Intent(this, FloatingControlsService.class));
            }
            broadcastState("recording");
        } catch (Exception e) {
            broadcastError("Không thể bắt đầu ghi: " + (e.getMessage() == null ? "lỗi thiết bị" : e.getMessage()));
            stopRecording();
        }
    }

    private void configureRecorderFileLimit(File folder) {
        if (recorder == null) return;
        long free = folder.getUsableSpace();
        long safeLimit = Math.max(8L * 1024L * 1024L, free - 64L * 1024L * 1024L);
        try { recorder.setMaxFileSize(safeLimit); } catch (Exception ignored) {}
    }

    private void startVideoCapture(Intent intent) throws Exception {
        Intent data = getProjectionData(intent);
        projection = createProjection(intent, data);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        if (withMic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        if (withMic) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(48000);
            recorder.setAudioEncodingBitRate(128000);
        }
        int[] safe = findSafeVideoSize(requestedWidth, requestedHeight, fps);
        recorder.setVideoSize(safe[0], safe[1]);
        recorder.setVideoFrameRate(findSafeFrameRate(safe[0], safe[1], fps));
        recorder.setVideoEncodingBitRate(bitrate);
        recorder.setOutputFile(tempFile.getAbsolutePath());
        configureRecorderFileLimit(tempFile.getParentFile());
        recorder.prepare();
        Surface surface = recorder.getSurface();
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        virtualDisplay = projection.createVirtualDisplay(
                "KlyroScreenCapture", safe[0], safe[1], metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler);
        if (virtualDisplay == null) throw new IllegalStateException("Unable to create screen display");
        recorder.start();
    }

    private Intent getProjectionData(Intent intent) {
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) throw new IllegalStateException("Screen capture permission was not returned");
        return data;
    }

    private MediaProjection createProjection(Intent intent, Intent data) {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        int resultCode = intent.getIntExtra(EXTRA_RESULT, 0);
        MediaProjection created = manager.getMediaProjection(resultCode, data);
        if (created == null) throw new IllegalStateException("Screen capture permission was not granted");
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() {
                if (!finished) handler.post(() -> {
                    if (!finished) {
                        broadcastError("Android đã ngắt phiên ghi; Klyro đang cố lưu phần đã ghi.");
                        stopRecording();
                    }
                });
            }
        };
        created.registerCallback(projectionCallback, handler);
        return created;
    }

    private void startInternalAudioCapture(Intent intent) throws Exception {
        projection = createProjection(intent, getProjectionData(intent));

        AudioPlaybackCaptureConfiguration captureConfig =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

        int channelMask = AudioFormat.CHANNEL_IN_STEREO;
        int minBuffer = AudioRecord.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) minBuffer = 8192;

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(PLAYBACK_SAMPLE_RATE)
                .setChannelMask(channelMask)
                .build();

        playbackRecord = new AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(Math.max(minBuffer * 2, 16384))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build();
        if (playbackRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("Thiết bị không hỗ trợ thu âm thanh ứng dụng");
        }

        MediaFormat encodedFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, PLAYBACK_SAMPLE_RATE, PLAYBACK_CHANNELS);
        encodedFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfoAACProfile());
        encodedFormat.setInteger(MediaFormat.KEY_BIT_RATE, PLAYBACK_BITRATE);
        encodedFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        playbackEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        playbackEncoder.configure(encodedFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        playbackEncoder.start();
        playbackMuxer = new MediaMuxer(tempFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        playbackRunning = true;
        playbackFailure = null;
        encodedAudioFrames = 0L;
        playbackRecord.startRecording();
        playbackThread = new Thread(this::runPlaybackEncoder, "Klyro-Playback-Audio");
        playbackThread.start();
    }

    private int MediaCodecInfoAACProfile() {
        return android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC;
    }

    private void runPlaybackEncoder() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] pcm = new short[4096];
        boolean eosQueued = false;
        try {
            while (playbackRunning) {
                AudioRecord source = playbackRecord;
                if (source == null) break;
                int samples = source.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (samples == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw new IllegalStateException("Luồng âm thanh hệ thống đã bị ngắt");
                }
                if (samples < 0) {
                    throw new IllegalStateException("Không thể đọc âm thanh ứng dụng: " + samples);
                }
                if (samples == 0 || paused) {
                    drainPlaybackEncoder(false);
                    continue;
                }

                boolean queued = false;
                while (!queued && playbackRunning) {
                    int inputIndex = playbackEncoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = playbackEncoder.getInputBuffer(inputIndex);
                        if (input == null) throw new IllegalStateException("Bộ mã hoá không cấp input buffer");
                        input.clear();
                        input.order(ByteOrder.nativeOrder());
                        for (int i = 0; i < samples; i++) input.putShort(pcm[i]);
                        long pts = encodedAudioFrames * 1000000L / PLAYBACK_SAMPLE_RATE;
                        encodedAudioFrames += samples / PLAYBACK_CHANNELS;
                        playbackEncoder.queueInputBuffer(inputIndex, 0, samples * 2, pts, 0);
                        queued = true;
                    } else {
                        drainPlaybackEncoder(false);
                    }
                }
                drainPlaybackEncoder(false);
            }

            if (playbackEncoder != null) {
                int inputIndex;
                int tries = 0;
                do {
                    inputIndex = playbackEncoder.dequeueInputBuffer(10000);
                    if (inputIndex < 0) drainPlaybackEncoder(false);
                    tries++;
                } while (inputIndex < 0 && tries < 20);
                if (inputIndex >= 0) {
                    long pts = encodedAudioFrames * 1000000L / PLAYBACK_SAMPLE_RATE;
                    playbackEncoder.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    eosQueued = true;
                }
                drainPlaybackEncoder(eosQueued);
            }
        } catch (Exception e) {
            playbackFailure = e.getMessage() == null ? "Lỗi thu âm thanh ứng dụng" : e.getMessage();
        } finally {
            closePlaybackPipeline();
            if (playbackFailure != null && !finished) {
                handler.post(() -> {
                    if (!finished) {
                        broadcastError("Thu âm thanh ứng dụng bị gián đoạn; Klyro đang cố lưu tệp.");
                        stopRecording();
                    }
                });
            }
        }
    }

    private void drainPlaybackEncoder(boolean waitForEnd) throws Exception {
        if (playbackEncoder == null) return;
        boolean end = false;
        int idleCount = 0;
        while (!end) {
            int outputIndex = playbackEncoder.dequeueOutputBuffer(audioBufferInfo, waitForEnd ? 10000 : 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEnd || ++idleCount >= 30) break;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IllegalStateException("Audio encoder changed format twice");
                muxerTrack = playbackMuxer.addTrack(playbackEncoder.getOutputFormat());
                playbackMuxer.start();
                muxerStarted = true;
            } else if (outputIndex >= 0) {
                ByteBuffer encoded = playbackEncoder.getOutputBuffer(outputIndex);
                if (encoded != null && audioBufferInfo.size > 0 && muxerStarted
                        && (audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    encoded.position(audioBufferInfo.offset);
                    encoded.limit(audioBufferInfo.offset + audioBufferInfo.size);
                    playbackMuxer.writeSampleData(muxerTrack, encoded, audioBufferInfo);
                }
                end = (audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                playbackEncoder.releaseOutputBuffer(outputIndex, false);
                if (!waitForEnd && outputIndex >= 0 && audioBufferInfo.size == 0) {
                    // Empty codec output is normal; continue draining until the encoder is idle.
                }
            }
        }
    }

    private void closePlaybackPipeline() {
        AudioRecord source = playbackRecord;
        playbackRecord = null;
        if (source != null) {
            try { if (source.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) source.stop(); } catch (Exception ignored) {}
            try { source.release(); } catch (Exception ignored) {}
        }
        MediaCodec encoder = playbackEncoder;
        playbackEncoder = null;
        if (encoder != null) {
            try { encoder.stop(); } catch (Exception ignored) {}
            try { encoder.release(); } catch (Exception ignored) {}
        }
        MediaMuxer muxer = playbackMuxer;
        playbackMuxer = null;
        if (muxer != null) {
            if (muxerStarted) {
                try { muxer.stop(); } catch (Exception ignored) {}
            }
            try { muxer.release(); } catch (Exception ignored) {}
        }
        muxerStarted = false;
        muxerTrack = -1;
    }

    private void finishInternalAudioCapture() {
        playbackRunning = false;
        AudioRecord source = playbackRecord;
        if (source != null) {
            try {
                if (source.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) source.stop();
            } catch (Exception ignored) {}
        }
        Thread worker = playbackThread;
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(10000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (worker != null && worker.isAlive()) {
            worker.interrupt();
            broadcastError("Android chưa trả bộ thu âm kịp thời; tệp có thể thiếu phần cuối.");
        }
        playbackThread = null;
    }

    private int[] findSafeVideoSize(int width, int height, int frameRate) {
        int w = Math.max(2, width - (width % 2));
        int h = Math.max(2, height - (height % 2));
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", w, h);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            String encoder = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
            if (encoder != null) return new int[]{w,h};
        } catch (Exception ignored) {}
        int[][] fallbacks = {{1920,1080},{1280,720},{854,480}};
        for (int[] candidate : fallbacks) {
            try {
                MediaFormat f = MediaFormat.createVideoFormat("video/avc", candidate[0], candidate[1]);
                f.setInteger(MediaFormat.KEY_FRAME_RATE, Math.min(frameRate, 60));
                if (new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f) != null) {
                    return new int[]{candidate[0],candidate[1]};
                }
            } catch (Exception ignored) {}
        }
        return new int[]{1280,720};
    }

    private int findSafeFrameRate(int width, int height, int requested) {
        int[] choices = {requested, 60, 30, 24};
        for (int candidate : choices) {
            if (candidate > requested) continue;
            try {
                MediaFormat f = MediaFormat.createVideoFormat("video/avc", width, height);
                f.setInteger(MediaFormat.KEY_FRAME_RATE, candidate);
                if (new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f) != null) return candidate;
            } catch (Exception ignored) {}
        }
        return 30;
    }

    private void pauseRecording() {
        if (finished || paused) return;
        if (internalAudioOnly && playbackRunning) {
            paused = true;
            pausedAt = SystemClock.elapsedRealtime();
            broadcastState("paused");
            return;
        }
        if (recorder == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                recorder.pause();
                paused = true;
                pausedAt = SystemClock.elapsedRealtime();
                broadcastState("paused");
            }
        } catch (Exception e) {
            broadcastError("Không thể tạm dừng trên thiết bị này");
        }
    }

    private void resumeRecording() {
        if (finished || !paused) return;
        if (internalAudioOnly && playbackRunning) {
            accumulatedPause += SystemClock.elapsedRealtime() - pausedAt;
            paused = false;
            broadcastState("recording");
            return;
        }
        if (recorder == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                accumulatedPause += SystemClock.elapsedRealtime() - pausedAt;
                recorder.resume();
                paused = false;
                broadcastState("recording");
            }
        } catch (Exception e) {
            broadcastError("Không thể tiếp tục ghi");
        }
    }

    private long elapsed() {
        if (startedAt == 0) return 0;
        long now = paused ? pausedAt : SystemClock.elapsedRealtime();
        return Math.max(0, now - startedAt - accumulatedPause);
    }

    private void stopRecording() {
        if (finished) return;
        finished = true;
        handler.removeCallbacks(ticker);
        boolean hadRecorder = recorder != null || internalAudioOnly;
        boolean recorderFinalized = true;
        try {
            if (internalAudioOnly) finishInternalAudioCapture();

            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (RuntimeException stopFailure) {
                    recorderFinalized = false;
                }
                try { recorder.reset(); } catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }

            if (virtualDisplay != null) {
                try { virtualDisplay.release(); } catch (Exception ignored) {}
                virtualDisplay = null;
            }
            if (projection != null) {
                try {
                    if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
                } catch (Exception ignored) {}
                projectionCallback = null;
                try { projection.stop(); } catch (Exception ignored) {}
                projection = null;
            }

            stopService(new Intent(this, FloatingControlsService.class));
            Uri saved = null;
            boolean audioFile = audioOnly || internalAudioOnly;
            if (recorderFinalized && tempFile != null && tempFile.exists() && tempFile.length() > 1024) {
                saved = publishFile(tempFile, audioFile);
            }
            if (saved != null) {
                Intent done = new Intent(ACTION_SAVED).setPackage(getPackageName());
                done.putExtra("uri", saved.toString());
                done.putExtra("audio", audioFile);
                sendBroadcast(done);
            } else if (hadRecorder) {
                if (!recorderFinalized && tempFile != null && tempFile.exists()) {
                    broadcastError("Bộ ghi bị ngắt đột ngột; tệp tạm được giữ lại trong thư mục dữ liệu Klyro vì chưa thể hoàn tất MP4.");
                } else if (internalAudioOnly && playbackFailure != null) {
                    broadcastError("Không thể hoàn tất âm thanh ứng dụng: " + playbackFailure);
                } else {
                    broadcastError("Bản ghi quá ngắn hoặc không thể lưu");
                }
            }
        } catch (Exception e) {
            broadcastError("Lỗi khi lưu bản ghi: " + (e.getMessage() == null ? "không rõ" : e.getMessage()));
        } finally {
            if (tempFile != null && tempFile.exists() && (recorderFinalized || tempFile.length() <= 1024)) {
                tempFile.delete();
            }
            stopForeground(true);
            stopSelf();
        }
    }

    private Uri publishFile(File source, boolean audio) throws Exception {
        String name = source.getName();
        android.content.ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, audio ? "audio/mp4" : "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                audio ? Environment.DIRECTORY_MUSIC + "/Klyro/" : Environment.DIRECTORY_MOVIES + "/Klyro/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = audio ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                               : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri target = resolver.insert(collection, values);
        if (target == null) throw new IllegalStateException("MediaStore insert failed");
        try (InputStream in = new FileInputStream(source); OutputStream out = resolver.openOutputStream(target, "w")) {
            if (out == null) throw new IllegalStateException("Cannot open saved file");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            out.flush();
        } catch (Exception e) {
            resolver.delete(target, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(target, done, null, null);
        return target;
    }

    private void broadcastState(String state) {
        Intent i = new Intent(ACTION_TICK).setPackage(getPackageName());
        i.putExtra("elapsed", elapsed());
        i.putExtra("paused", paused);
        i.putExtra("state", state);
        sendBroadcast(i);
    }

    private void broadcastError(String message) {
        Intent i = new Intent(ACTION_ERROR).setPackage(getPackageName());
        i.putExtra("message", message);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Klyro recording",
                    NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Controls for an active screen recording");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getBroadcast(this, 2,
                new Intent(this, RecordingActionReceiver.class).setAction(paused ? ACTION_RESUME : ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getBroadcast(this, 3,
                new Intent(this, RecordingActionReceiver.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Klyro đang ghi")
                .setContentText(paused ? "Đã tạm dừng" : (internalAudioOnly ? "Đang thu âm thanh ứng dụng" : "Đang ghi"))
                .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, paused ? "Tiếp tục" : "Tạm dừng", pause)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", stop);
        return b.build();
    }

    private void updateNotification() {
        NotificationManager n = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        n.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(ticker);
        if (!finished && (recorder != null || playbackRunning || internalAudioOnly)) stopRecording();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}