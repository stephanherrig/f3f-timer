/*
 * RaceTimerFrag4
 * Clock has started
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
import com.marktreble.f3ftimer.driver.Driver;
import com.marktreble.f3ftimer.racemanager.RaceActivity;

public class RaceTimerFrag4 extends RaceTimerFrag {
	
	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
	    } else {
			mRaceTimerActivity.mLastSecond = 0;
			mRaceTimerActivity.mStart = System.currentTimeMillis();
			mRaceTimerActivity.mHandlerEndTime = mRaceTimerActivity.mStart + 10;
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
		ab.setText(getResources().getText(R.string.abort));
		ab.setVisibility(View.VISIBLE);
		ab.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				mRaceTimerActivity.mHandler.removeCallbacks(updateClock);
				mRaceTimerActivity.mHandlerEndTime = 0;
				mRaceTimerActivity.mFinalTime = -1.0f;
				mRaceTimerActivity.mFlying = false;
				
				mRaceTimerActivity.sendCommand("abort");
				mRaceTimerActivity.sendCommand("begin_timeout");
				mRaceTimerActivity.getFragment(new RaceTimerFrag6(), 6); // Abort submenu (reflight or score 0)
			}
		});
		
		Button refly = (Button) mView.findViewById(R.id.button_refly);
		refly.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mRaceTimerActivity.mAlreadyProgressed) return;
				mRaceTimerActivity.mAlreadyProgressed = true;
				reflight();
				
			}
		});
		
		Button fin = (Button) mView.findViewById(R.id.button_finish);
		fin.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mRaceTimerActivity.mAlreadyProgressed) return;
				mRaceTimerActivity.mAlreadyProgressed = true;
				next();
				
			}
		});
		
		SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getActivity());
		String soft_buttons = sharedPref.getString("pref_input_src", getString(R.string.Demo));
		if (soft_buttons.equals(getString(R.string.Demo))) {
			Button baseA = (Button) mView.findViewById(R.id.base_A);
			baseA.setVisibility(View.VISIBLE);
			
			baseA.setOnClickListener(new Button.OnClickListener() {
				@Override
				public void onClick(View v) {
					mRaceTimerActivity.sendCommand("baseA");
				}
			});
			
			Button baseB = (Button) mView.findViewById(R.id.base_B);
			baseB.setVisibility(View.VISIBLE);
			
			baseB.setOnClickListener(new Button.OnClickListener() {
				@Override
				public void onClick(View v) {
					mRaceTimerActivity.sendCommand("baseB");
				}
			});
			
			
		}
		
		TextView cd = (TextView) mView.findViewById(R.id.time);
		TextView min = (TextView) mView.findViewById(R.id.mintime);
		String str_time;
		if (mRaceTimerActivity.mFinalTime < 0) {
			long elapsed = System.currentTimeMillis() - mRaceTimerActivity.mStart;
			float seconds = (float) elapsed / 1000;
			str_time = String.format("%.2f", seconds);
			if (mRaceTimerActivity.mLap > 0) {
				str_time += String.format(" T%d", mRaceTimerActivity.mLap);
			}
		} else {
			str_time = String.format("%.2f", mRaceTimerActivity.mFinalTime);
		}
		cd.setText(str_time);
		min.setText(str_time);
		
		TextView lap = (TextView) mView.findViewById(R.id.lap);
        if (mRaceTimerActivity.mLap > 0) {
			String str_lap = String.format("Turn: %d", mRaceTimerActivity.mLap);
			lap.setText(str_lap);
		} else {
			lap.setText("");
		}
		
		TextView est = (TextView) mView.findViewById(R.id.estimated);
		if (mRaceTimerActivity.mEstimate > 0){
			String str_est = String.format("Est: %.2f", (float)mRaceTimerActivity.mEstimate/1000);
			est.setText(str_est);
		} else {
			est.setText("");
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
			case 3:
				statusText.setText(R.string.run_complete);
				break;
		}

		setWindWarning(mRaceTimerActivity.mIllegalWindValues.contains("illegal"), mRaceTimerActivity.mIllegalWindValues);

		super.setPilotName();

		if (mRaceTimerActivity.mWindowState == RaceTimerActivity.WINDOW_STATE_MINIMIZED) {
			setMinimized();
		}

		return mView;
	}	

	public void setOnCourse(){
		if (mView != null) {
			TextView status = (TextView) mView.findViewById(R.id.status);
			status.setText(R.string.on_course);
		}
	}

	public void setOffCourse(){
		if (mView != null) {
			TextView status = (TextView) mView.findViewById(R.id.status);
			status.setText(R.string.off_course);
		}
	}

	private Runnable updateClock = new Runnable(){
		public void run(){
        	long elapsed = System.currentTimeMillis() - mRaceTimerActivity.mStart;
        	float seconds = (float)elapsed/1000;

			TextView cd = (TextView) mView.findViewById(R.id.time);
			String str_time = String.format("%.2f", seconds);
			if (mRaceTimerActivity.mLap>0){
				str_time += String.format(" T%d", mRaceTimerActivity.mLap);
			}
			cd.setText(str_time);
			
			TextView min = (TextView) mView.findViewById(R.id.mintime);
			min.setText(str_time);
			
			int s = (int) Math.floor(seconds);
			if (s != mRaceTimerActivity.mLastSecond) {
				Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
				i.putExtra("com.marktreble.f3ftimer.value.flightTime", 0.0f + s);
				mRaceTimerActivity.sendBroadcast(i);
			}
			mRaceTimerActivity.mLastSecond = s;

			mRaceTimerActivity.mHandlerEndTime = System.currentTimeMillis() + 10;
			mRaceTimerActivity.mHandler.postDelayed(updateClock, 10);
		}
	};

	public void setLeg(int number, long estimated, long fastestLegTime, long legTime, long deltaTime, String fastestFlightPilot){
		// Stop the clock here
		if (number == Driver.LEGS_PER_FLIGHT && mRaceTimerActivity.mFinalTime < 0){
            long elapsed = System.currentTimeMillis() - mRaceTimerActivity.mStart;
			mRaceTimerActivity.mHandler.removeCallbacks(updateClock);
			mRaceTimerActivity.mHandlerEndTime = 0;
            TextView cd = (TextView) mView.findViewById(R.id.time);
            cd.setText("");

			TextView min = (TextView) mView.findViewById(R.id.mintime);
			min.setText("");

            Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
            i.putExtra("com.marktreble.f3ftimer.value.flightTime", elapsed/1000.0f);
			mRaceTimerActivity.sendBroadcast(i);
            mRaceTimerActivity.sendCommand(String.format("::%.2f", (float)elapsed/1000));
        }
		
		mRaceTimerActivity.mLap = number;
		mRaceTimerActivity.mEstimate = estimated;
		
		if (number>0){
			Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
			i.putExtra("com.marktreble.f3ftimer.value.turnNumber", number);
            i.putExtra("com.marktreble.f3ftimer.value.legTime", legTime / 1000.0f);
			i.putExtra("com.marktreble.f3ftimer.value.fastestLegTime", fastestLegTime / 1000.0f);
			i.putExtra("com.marktreble.f3ftimer.value.fastestLegPilot", fastestFlightPilot);
			i.putExtra("com.marktreble.f3ftimer.value.deltaTime", deltaTime / 1000.0f);
			i.putExtra("com.marktreble.f3ftimer.value.estimatedTime", estimated / 1000.0f);
			mRaceTimerActivity.sendBroadcast(i);

			TextView lap = (TextView) mView.findViewById(R.id.lap);
			String str_lap = String.format("Turn: %d", number);
			lap.setText(str_lap);
		}
		
		if (estimated>0){
			TextView est = (TextView) mView.findViewById(R.id.estimated);
			String str_est = String.format("Est: %.2f", (float)estimated/1000);
			est.setText(str_est);
		}
		
	}
	
	public void setFinal(Float time, float fastestFlightTime, String fastestFlightPilot){
		mRaceTimerActivity.mHandler.removeCallbacks(updateClock);
		mRaceTimerActivity.mHandlerEndTime = 0;
		
		TextView cd = (TextView) mView.findViewById(R.id.time);
		String str_time = String.format("%.2f", time);
		cd.setText(str_time);

		TextView min = (TextView) mView.findViewById(R.id.mintime);
		min.setText(str_time);

		TextView lap = (TextView) mView.findViewById(R.id.lap);
		lap.setText("");

		TextView est = (TextView) mView.findViewById(R.id.estimated);
		est.setText("");

		TextView status = (TextView) mView.findViewById(R.id.status);
		status.setText(R.string.run_complete);

		Button abort = (Button) mView.findViewById(R.id.button_abort);
		abort.setVisibility(View.GONE);

        Button baseA = (Button) mView.findViewById(R.id.base_A);
        Button baseB = (Button) mView.findViewById(R.id.base_B);
        baseA.setVisibility(View.GONE);
        baseB.setVisibility(View.GONE);

        Button f = (Button) mView.findViewById(R.id.button_finish);
		f.setVisibility(View.VISIBLE);

        Button r = (Button) mView.findViewById(R.id.button_refly);
        r.setVisibility(View.VISIBLE);
		
		// Start Round Timeout now
		mRaceTimerActivity.sendCommand("begin_timeout");

		/* send to ResultsServer Live Listener */
		Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
        i.putExtra("com.marktreble.f3ftimer.value.fastestFlightTime", fastestFlightTime);
		i.putExtra("com.marktreble.f3ftimer.value.fastestFlightPilot", fastestFlightPilot);
		i.putExtra("com.marktreble.f3ftimer.value.state", 6);
		mRaceTimerActivity.sendBroadcast(i);
	}
		
	public void next() {
		// Tell Driver to finalise the score
		// Driver will post back run_finalised when finished
		mRaceTimerActivity.sendOrderedCommand("finalise");
	}

	public void cont(){
		/* send to ResultsServer Live Listener */
		Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
		i.putExtra("com.marktreble.f3ftimer.value.state", 0);
		mRaceTimerActivity.sendBroadcast(i);
		
		SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(mRaceTimerActivity);
		boolean automatic_progression = sharedPref.getBoolean("pref_automatic_pilot_progression", false);
		if (automatic_progression) {
			mRaceTimerActivity.sendCommand("abort");
			mRaceTimerActivity.setResult(RaceActivity.RESULT_OK);
			mRaceTimerActivity.finish();
		} else {
			mRaceTimerActivity.getFragment(new RaceTimerFrag5(), 5);
		}
	}
	
    public void reflight(){
        mRaceTimerActivity.reflight();
        
		/* send to ResultsServer Live Listener */
		Intent i = new Intent("com.marktreble.f3ftimer.onLiveUpdate");
		i.putExtra("com.marktreble.f3ftimer.value.state", 0);
		mRaceTimerActivity.sendBroadcast(i);
		
		mRaceTimerActivity.mFlying = false;
	}

	public void startPressed(){
		if (mRaceTimerActivity.mFinalTime < 0) return; // Ignore if the race is still in progress
		if (!mRaceTimerActivity.mAlreadyProgressed) {
			mRaceTimerActivity.mAlreadyProgressed = true;
			next();
		}
	}
 }
