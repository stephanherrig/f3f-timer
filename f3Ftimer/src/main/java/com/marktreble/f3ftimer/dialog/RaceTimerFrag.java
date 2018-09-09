package com.marktreble.f3ftimer.dialog;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.support.v4.app.Fragment;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.marktreble.f3ftimer.R;

public class RaceTimerFrag extends Fragment {

	protected View mView;
	protected RaceTimerActivity mRaceTimerActivity;
	
	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		if (context instanceof RaceTimerActivity) {
            mRaceTimerActivity = (RaceTimerActivity) context;
        }
	}
	
	public void setPilotName() {
		String name = String.format("%s %s", mRaceTimerActivity.mPilot.firstname, mRaceTimerActivity.mPilot.lastname);

		if (name.trim().equals(""))
			name="noname! "+ mRaceTimerActivity.mPilot.id;

		TextView pilot_name = (TextView) mView.findViewById(R.id.current_pilot);
		TextView min_name = (TextView) mView.findViewById(R.id.minpilot);
		pilot_name.setText(name);
		min_name.setText(name);

		Drawable flag = mRaceTimerActivity.mPilot.getFlag(mRaceTimerActivity);
		if (flag != null){
			pilot_name.setCompoundDrawablesWithIntrinsicBounds(flag, null, null, null);
			min_name.setCompoundDrawablesWithIntrinsicBounds(flag, null, null, null);
		    int padding = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
			pilot_name.setCompoundDrawablePadding(padding);
			min_name.setCompoundDrawablePadding(padding);
		}

		TextView pilot_number = (TextView) mView.findViewById(R.id.number);
		TextView min_number = (TextView) mView.findViewById(R.id.minnumber);
		pilot_number.setText(mRaceTimerActivity.mNumber);
		min_number.setText(mRaceTimerActivity.mNumber);
	}

	public void setWindWarning(boolean on, String text){
		if (mView != null) {
			TextView warning = (TextView) mView.findViewById(R.id.wind_warning);
			warning.setText(text);
			warning.setVisibility( on ? View.VISIBLE : View.INVISIBLE);
		}
	}

	public void startPressed(){
		// Abstract
	}

	public void setMinimized(){
		View min = mView.findViewById(R.id.minimised);
		min.setVisibility(View.VISIBLE);
		View full =  mView.findViewById(R.id.full);
		full.setVisibility(View.GONE);
	}

	public void setExpanded(){
		View min = mView.findViewById(R.id.minimised);
		min.setVisibility(View.GONE);
		View full =  mView.findViewById(R.id.full);
		full.setVisibility(View.VISIBLE);
	}
}
