/*
 * RaceTimerFrag5
 * Finalised
 */
package com.marktreble.f3ftimer.dialog;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.marktreble.f3ftimer.R;
import com.marktreble.f3ftimer.racemanager.RaceActivity;

public class RaceTimerFrag5 extends RaceTimerFrag {
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        mView = inflater.inflate(R.layout.race_timer_frag2, container, false);

        Button next = (Button) mView.findViewById(R.id.button_next_pilot);
        next.setVisibility(View.VISIBLE);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mRaceTimerActivity.mAlreadyProgressed) return;
                mRaceTimerActivity.mAlreadyProgressed = true;
                next();

            }
        });

        TextView cd = (TextView) mView.findViewById(R.id.time);
        String str_time = String.format("%.2f", mRaceTimerActivity.mFinalTime);
        cd.setText(str_time);

        TextView min = (TextView) mView.findViewById(R.id.mintime);
        min.setText(str_time);

        TextView status = (TextView) mView.findViewById(R.id.status);
        status.setText(getString(R.string.run_complete));

        super.setPilotName();

        if (mRaceTimerActivity.mWindowState == RaceTimerActivity.WINDOW_STATE_MINIMIZED) {
            setMinimized();
        }


        return mView;
    }

    public void next(){
        mRaceTimerActivity.mFlying = false;
        mRaceTimerActivity.mFinalTime = -1.0f;

        mRaceTimerActivity.sendCommand("abort");
        mRaceTimerActivity.setResult(RaceActivity.RESULT_OK);
        mRaceTimerActivity.finish();

		/* send to ResultsServer Live Listener */
        Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
        i.putExtra("com.marktreble.f3ftimer.value.state", 0);
        mRaceTimerActivity.sendBroadcast(i);
    }

    public void startPressed(){
        if (!mRaceTimerActivity.mAlreadyProgressed) {
            mRaceTimerActivity.mAlreadyProgressed = true;
            next();
        }
    }

}
