package com.marktreble.f3ftimer.dialog;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.marktreble.f3ftimer.R;
import com.marktreble.f3ftimer.racemanager.RaceActivity;

/**
 * Created by marktreble on 28/08/2016.
 */
public class RaceTimerFrag6 extends RaceTimerFrag {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        mView = inflater.inflate(R.layout.race_timer_frag2, container, false);

        Button zero = (Button) mView.findViewById(R.id.button_zero);
        zero.setVisibility(View.VISIBLE);
        zero.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mRaceTimerActivity.mAlreadyProgressed) return;
                mRaceTimerActivity.mAlreadyProgressed = true;
                score_zero();

            }
        });

        Button refly = (Button) mView.findViewById(R.id.button_refly);
        refly.setVisibility(View.VISIBLE);
        refly.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mRaceTimerActivity.mAlreadyProgressed) return;
                mRaceTimerActivity.mAlreadyProgressed = true;
                reflight();

            }
        });

        super.setPilotName();

        if (mRaceTimerActivity.mWindowState == RaceTimerActivity.WINDOW_STATE_MINIMIZED) {
            setMinimized();
        }

        // Start Round Timeout now
        mRaceTimerActivity.sendCommand("begin_timeout");

        return mView;
    }

    public void reflight(){
        mRaceTimerActivity.reflight();

        Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
        i.putExtra("com.marktreble.f3ftimer.value.state", 0);
        mRaceTimerActivity.sendBroadcast(i);
    }

    public void score_zero(){
        mRaceTimerActivity.scorePilotZero(mRaceTimerActivity.mPilot.id);
        mRaceTimerActivity.setResult(RaceActivity.RESULT_ABORTED, null);
        mRaceTimerActivity.finish();

        Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
        i.putExtra("com.marktreble.f3ftimer.value.state", 0);
        mRaceTimerActivity.sendBroadcast(i);
    }
}
