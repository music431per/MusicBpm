package com.freeeeedom.waveplayerandroid.musicbpm;

import android.content.Context;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private TempoMeasurementThread tempoMeasurementThread;

    private TextView bpmTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tempoMeasurementThread = new TempoMeasurementThread();

        bpmTextView = (TextView) findViewById(R.id.bpm_id);
    }

    public void onClick(View v){
        final Context context = this;

        bpmTextView.setText("BPM計測中");

        final Handler handler = new Handler();
        new Thread(new Runnable() {
            @Override
            public void run() {
                tempoMeasurementThread.start(context);
                final int bpm = tempoMeasurementThread.getBpm();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        bpmTextView.setText("BPM:" + bpm);
                    }
                });
            }
        }).start();
    }
}
