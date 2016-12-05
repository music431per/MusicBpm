package com.freeeeedom.waveplayerandroid.musicbpm;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static android.R.attr.path;

/**
 * Created by eri on 2016/11/23.
 */

public class TempoMeasurementThread {

    private final static String TAG = "TempoMeasurementThread";

    // 読み込む音楽のデータ
    private int channel = 0;
    private int sampleRate = 0;

    // 1フレームを512とする
    private final static int FRAME_LEN = 512;

    // デコーダーに使う部分
    private MediaExtractor extractor;
    private MediaCodec decoder;
    private final static int TIMEOUT_US = 10000;

    // 音楽のデータを格納する配列（chunkごと）
    private ArrayList soundDataList;

    private int bpm = 0;


    public void start(Context context) {

        soundDataList = new ArrayList();
        extractor = new MediaExtractor();


        // ファイルを読み込み
        final AssetFileDescriptor afd = context.getResources().openRawResourceFd(R.raw.meto);
        try {
            extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        } catch (IOException e) {
            e.printStackTrace();
        }

        // デコーダの作成
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channel = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            // 音楽のデータなら
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                try {
                    decoder = MediaCodec.createDecoderByType(mime);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                decoder.configure(format, null, null, 0);
                break;
            }
        }

        // 動画のとき
        if (decoder == null) {
            Log.e(TAG, "動画データ");
            return;
        }

        Log.d(TAG, "decoder start");

        decoder.start();

        ByteBuffer[] inputBuffers = decoder.getInputBuffers();
        ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean isDecoding = true;

        while (!Thread.interrupted()) {

            if (isDecoding) {
                int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer buffer = inputBuffers[inIndex];
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        // 終了
                        Log.d(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isDecoding = false;
                    } else {
                        // 次へ
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            switch (outIndex) {
                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                    Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                    outputBuffers = decoder.getOutputBuffers();
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    Log.d(TAG, "New format " + decoder.getOutputFormat());
                    break;
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    Log.d(TAG, "time out");
                    break;
                default:
                    ByteBuffer buffer = outputBuffers[outIndex];

                    final byte[] chunk = new byte[info.size];
                    Log.d("size", String.valueOf(info.size));
                    buffer.get(chunk);
                    buffer.clear();
                    soundDataList.add(chunk);
                    decoder.releaseOutputBuffer(outIndex, true);
                    break;
            }

            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                break;
            }
        }

        decoder.stop();
        decoder.release();
        extractor.release();

        Log.d(TAG, "decoder end");

        Log.d(TAG, "soundDataList.size:" + soundDataList.size());
        Log.d(TAG, "samplingRate:" + String.valueOf(sampleRate));
        Log.d(TAG, "channel:" + String.valueOf(channel));

        // BPMの測定
        measurementBpm();

    }

    private void measurementBpm() {

        Log.d(TAG, "bpm measurement start");

        // chunkに何バイト入っているか知るため
        byte[] sampleChunk = (byte[]) soundDataList.get(soundDataList.size()/2);

        // 1サンプル16bit(2バイト)だから2で割ってる
        int sampleSize = sampleChunk.length * soundDataList.size()/2;
        int[] samples = new int[sampleSize];

        // サンプルごとに格納
        int c = 0;
        for (int i = 0; i < soundDataList.size(); i++) {
            byte[] chunk = (byte[]) soundDataList.get(i);
            for (int j = 0; j < chunk.length; j = j + 2) {
                int value = 0;
                value = (value << 8) + (chunk[j]);
                value = (value << 8) + (chunk[j + 1]);
                samples[c] = value;
                c++;
            }
        }

        // これ以降、使わないのでリソースの解放
        soundDataList.clear();

        // フレームの数
        int n = c / FRAME_LEN;

        // フレームごとの音量を求める
        double[] vols = new double[n];
        for (int i = 0; i < n; i++) {
            double vol = 0;
            for (int j = 0; j < FRAME_LEN; j++) {
                int sound = samples[i * FRAME_LEN + j];
                vol += Math.pow(sound, 2);
            }
            vol = Math.sqrt((1.0 / FRAME_LEN) * vol);
            vols[i] = vol;
        }

        // 隣り合うフレームの音量の増加分を求める
        double[] diffs = new double[n];
        for (int i = 0; i < n - 1; i++) {
            double diff = vols[i] - vols[i + 1];
            if (diff > 0) {
                diffs[i] = diff;
            } else {
                diffs[i] = 0;
            }
        }

        // 増加量の時間変化の周波数成分を求める
        // どのテンポがマッチするか

        double s = (double) sampleRate / FRAME_LEN;

        double[] a = new double[240 - 60 + 1];
        double[] b = new double[240 - 60 + 1];
        double[] r = new double[240 - 60 + 1];
        for (int bpm = 60; bpm <= 240; bpm++) {
            double aSum = 0;
            double bSum = 0;
            double f = (double) bpm / 60;
            for (int i = 0; i < n; i++) {
                aSum += diffs[i] * Math.cos(2.0 * Math.PI * f * i / s);
                bSum += diffs[i] * Math.sin(2.0 * Math.PI * f * i / s);
            }
            double aTmp = aSum / n;
            double bTmp = bSum / n;
            a[bpm - 60] = aTmp;
            b[bpm - 60] = bTmp;
            r[bpm - 60] = Math.sqrt(Math.pow(aTmp, 2) + Math.pow(bTmp, 2));
        }

        // マッチ度が一番高いインデックス
        int maxIndex = -1;

        // 一番マッチするインデックスを求める
        double dy = 0;
        for (int i = 1; i < 240 - 60 + 1; ++i) {
            double dyPre = dy;
            dy = r[i] - r[i - 1];
            if (dyPre > 0 && dy <= 0) {
                if (maxIndex < 0 || r[i - 1] > r[maxIndex]) {
                    maxIndex = i - 1;
                }
            }
        }

        bpm = maxIndex + 60;

        Log.d(TAG, "bpm measurement end");

    }

    public int getBpm() {
        return bpm;
    }
}
