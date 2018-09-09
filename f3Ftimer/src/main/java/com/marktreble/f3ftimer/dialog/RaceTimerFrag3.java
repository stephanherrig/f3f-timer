/*
 * RaceTimerFrag3
 * Climbout
 */
package com.marktreble.f3ftimer.dialog;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.marktreble.f3ftimer.R;

public class RaceTimerFrag3 extends RaceTimerFrag {

	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
	    } else {
	    	mRaceTimerActivity.mStart = System.currentTimeMillis();
			mRaceTimerActivity.mLastSecond = 0;
			mRaceTimerActivity.mHandlerEndTime = System.currentTimeMillis() + 10;
			mRaceTimerActivity.mHandler.postDelayed(updateClock, 10);
		}
	}

	@Override
	public void onDestroy(){
		super.onDestroy();
		mRaceTimerActivity.mHandler.removeCallbacks(updateClock);
		mRaceTimerActivity.mHandlerEndTime = 0;
	}
	
	@Override
	public void onDetach(){
		super.onDetach();
		mRaceTimerActivity.mHandler.removeCallbacks(updateClock);
		mRaceTimerActivity.mHandlerEndTime = 0;
	}

	@Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        mView = inflater.inflate(R.layout.race_timer_frag2, container, false);
		
		if (mRaceTimerActivity.mHandlerEndTime > 0) {
			long remainingTime = mRaceTimerActivity.mHandlerEndTime - System.currentTimeMillis();
			long delay = remainingTime >= 0 ? remainingTime : 0;
			mRaceTimerActivity.mHandler.postDelayed(updateClock, delay);
		}
  
		Button ab = (Button) mView.findViewById(R.id.button_abort);
		ab.setVisibility(View.VISIBLE);
	    ab.setOnClickListener(new View.OnClickListener() {
	        @Override
	        public void onClick(View v) {
				mRaceTimerActivity.mHandler.removeCallbacks(updateClock);
				mRaceTimerActivity.mHandlerEndTime = 0;
				mRaceTimerActivity.mFinalTime = -1.0f;
				mRaceTimerActivity.mFlying = false;
		
				mRaceTimerActivity.sendCommand("abort");

				Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
				i.putExtra("com.marktreble.f3ftimer.value.state", 7);
				mRaceTimerActivity.sendBroadcast(i);

				mRaceTimerActivity.sendCommand("begin_timeout");
				mRaceTimerActivity.getFragment(new RaceTimerFrag6(), 6); // Abort submenu (reflight or score 0)
	        }
	    });

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getActivity());
        String soft_buttons = sharedPref.getString("pref_input_src", getString(R.string.Demo));
        if (soft_buttons.equals(getString(R.string.Demo))){
            Button baseA = (Button) mView.findViewById(R.id.base_A);
            baseA.setVisibility(View.VISIBLE);
            
            baseA.setOnClickListener(new Button.OnClickListener(){
                @Override
                public void onClick(View v){
                    mRaceTimerActivity.sendCommand("baseA");
                }
            });

            Button baseB = (Button) mView.findViewById(R.id.base_B);
            baseB.setVisibility(View.VISIBLE);

            baseB.setOnClickListener(new Button.OnClickListener(){
                @Override
                public void onClick(View v){
                    mRaceTimerActivity.sendCommand("baseB");
                }
            });

        }
        
		TextView statusText = (TextView) mView.findViewById(R.id.status);
		switch (mRaceTimerActivity.mCourseStatus) {
			case 0:
				statusText.setText(R.string.model_launched);
				break;
			case 1:
				statusText.setText(R.string.off_course);
				break;
			case 2:
				statusText.setText(R.string.on_course);
				break;
		}
		
		setWindWarning(mRaceTimerActivity.mIllegalWindValues.contains("illegal"), mRaceTimerActivity.mIllegalWindValues);

		super.setPilotName();

		if (mRaceTimerActivity.mWindowState == RaceTimerActivity.WINDOW_STATE_MINIMIZED){
			setMinimized();
		}

		return mView;
	}	
	
	private Runnable updateClock = new Runnable(){
		public void run(){
        	long elapsed = System.currentTimeMillis() - mRaceTimerActivity.mStart;
        	float seconds = (float)elapsed/1000;
        	if (seconds>30) seconds = 30;

			TextView cd = (TextView) mView.findViewById(R.id.time);
			String str_time = String.format("%.2f", 30-seconds);
			cd.setText(str_time);

			TextView min = (TextView) mView.findViewById(R.id.mintime);
			min.setText(str_time);

			/* give .5 leadtime for speaking the numbers */
			int s = (int) Math.floor(seconds + 0.5);
			
			/* only send when the second changes, and not 100 times per second */
			if (s != mRaceTimerActivity.mLastSecond) {
				Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
				i.putExtra("com.marktreble.f3ftimer.value.climbOutTime", 30.0f - (float)Math.ceil(seconds));
				mRaceTimerActivity.sendBroadcast(i);
			}
			
			if (s==5 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("25");
			if (s==10 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("20");
			if (s==15 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("15");
			if (s==20 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("10");
			if (s==21 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("9");
			if (s==22 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("8");
			if (s==23 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("7");
			if (s==24 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("6");
			if (s==25 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("5");
			if (s==26 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("4");
			if (s==27 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("3");
			if (s==28 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("2");
			if (s==29 && s != mRaceTimerActivity.mLastSecond) mRaceTimerActivity.sendCommand("1");
        	if (s==30 && s != mRaceTimerActivity.mLastSecond){
        		// Runout of climbout time
        		// Force the server to start the clock
				mRaceTimerActivity.sendCommand("0"); // Informs the driver that this was a late entry
        		next();
        		
        	} else {
				mRaceTimerActivity.mHandlerEndTime = System.currentTimeMillis() + 10;
				mRaceTimerActivity.mHandler.postDelayed(updateClock, 10);
        	}
			mRaceTimerActivity.mLastSecond = s;
		}
	};
	
	public void setOffCourse(){
		if (mView != null) {
			TextView status = (TextView) mView.findViewById(R.id.status);
			status.setText(R.string.off_course);
		}

		/* send to ResultsServer Live Listener */
        Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
        i.putExtra("com.marktreble.f3ftimer.value.state", 4);
        mRaceTimerActivity.sendBroadcast(i);
	}

	public void next(){
		mRaceTimerActivity.mHandler.removeCallbacks(updateClock);
		mRaceTimerActivity.mHandlerEndTime = 0;
		
		mRaceTimerActivity.mLap = 0;
		mRaceTimerActivity.mEstimate = 0;
		
		mRaceTimerActivity.getFragment(new RaceTimerFrag4(), 4);

		/* send to ResultsServer Live Listener */
        Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
        i.putExtra("com.marktreble.f3ftimer.value.state", 5);
        mRaceTimerActivity.sendBroadcast(i);
	}

	public void startPressed(){
		// Ignore
	}
	
 }
