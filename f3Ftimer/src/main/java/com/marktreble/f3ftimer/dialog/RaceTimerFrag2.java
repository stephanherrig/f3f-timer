/*
 * RaceTimerFrag2
 * Working time
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


public class RaceTimerFrag2 extends RaceTimerFrag {
	
	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		
        if (savedInstanceState != null) {
	    } else {
			/* first start the timer, then speak working time started */
			mRaceTimerActivity.mLastSecond = 0;
	    	mRaceTimerActivity.mStart = System.currentTimeMillis();
			mRaceTimerActivity.mHandlerEndTime = mRaceTimerActivity.mStart + 10;
		    mRaceTimerActivity.mHandler.postDelayed(updateClock, 10);
	    }

        // Begin the timeout dialog timeout
        // Confusing? - yes. This stops the timeout being annoyingly invoked when working time has started
        // Unless of course the model is not launched before time is up!
    	mRaceTimerActivity.sendCommand("timeout_resumed");
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

		Button ml = (Button) mView.findViewById(R.id.button_model_launched);
		ml.setVisibility(View.VISIBLE);
	    ml.setOnClickListener(new View.OnClickListener() {
	        @Override
	        public void onClick(View v) {
				mRaceTimerActivity.mHandler.removeCallbacks(updateClock);
                mRaceTimerActivity.mHandlerEndTime = 0;
	        	next();

   	        }
	    });
	    
        Button ab = (Button) mView.findViewById(R.id.button_abort);
		ab.setVisibility(View.VISIBLE);
		ab.setOnClickListener(new View.OnClickListener() {
	        @Override
	        public void onClick(View v) {
				mRaceTimerActivity.mHandler.removeCallbacks(updateClock);
                mRaceTimerActivity.mHandlerEndTime = 0;

	        	mRaceTimerActivity.sendCommand("abort");
				mRaceTimerActivity.sendCommand("begin_timeout");
				mRaceTimerActivity.setResult(RaceActivity.RESULT_ABORTED, null);
	        	mRaceTimerActivity.finish();
	            
				Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
				i.putExtra("com.marktreble.f3ftimer.value.state", 0);
				mRaceTimerActivity.sendBroadcast(i);
	        }
	    });

		TextView status = (TextView) mView.findViewById(R.id.status);
		status.setText(getString(R.string.working_time));
		
		setWindWarning(false, "");

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
        	if (seconds > 30) seconds = 30;

			TextView cd = (TextView) mView.findViewById(R.id.time);
			String str_time = String.format("%.2f", 30-seconds);
			cd.setText(str_time);

			TextView min = (TextView) mView.findViewById(R.id.mintime);
			min.setText(str_time);

			/* give .5 leadtime for speaking the numbers */
			int s = (int) Math.floor(seconds + 0.6);

			/* only send when the second changes, and not 100 times per second */
			if (s != mRaceTimerActivity.mLastSecond) {
				/* send to ResultsServer Live Listener */
				Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
				i.putExtra("com.marktreble.f3ftimer.value.workingTime", 30.0f - (float)Math.ceil(seconds));
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
				mRaceTimerActivity.sendCommand("0");
        		// Runout of working time
        		// -- pilot scores zero!
        		
        		mRaceTimerActivity.scorePilotZero(mRaceTimerActivity.mPilot.id);
	        	mRaceTimerActivity.setResult(RaceActivity.RESULT_OK, null);
	        	mRaceTimerActivity.finish();
        	} else {
                mRaceTimerActivity.mHandlerEndTime = System.currentTimeMillis() + 10;
				mRaceTimerActivity.mHandler.postDelayed(updateClock, 10);
        	}
			mRaceTimerActivity.mLastSecond = s;
		}
	};
	
	public void next(){
		mRaceTimerActivity.mHandler.removeCallbacks(updateClock);
        mRaceTimerActivity.mHandlerEndTime = 0;
 	
		// Send model launched to server
 		mRaceTimerActivity.sendCommand("launch");
		
		/* send to TcpIoService for UI tracking */
		Intent i = new Intent("com.marktreble.f3ftimer.onUpdateFromUI");
		i.putExtra("com.marktreble.f3ftimer.ui_callback", "model_launched");
		mRaceTimerActivity.sendBroadcast(i);

		/* send to ResultsServer Live Listener */
		i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
		i.putExtra("com.marktreble.f3ftimer.value.state", 3);
		mRaceTimerActivity.sendBroadcast(i);
		
		mRaceTimerActivity.mCourseStatus = 0;
		mRaceTimerActivity.mFinalTime = -1.0f;
		mRaceTimerActivity.mFlying = true;

    	// Move on to 30 climbout timer
    	mRaceTimerActivity.getFragment(new RaceTimerFrag3(), 3);
	}
	
	public void startPressed(){
		next();
	}

 }
