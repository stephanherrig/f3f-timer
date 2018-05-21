/*
 * ResultsActivity
 * Entry Point for Results Manager App
 * Provides a list of races stored on the device
 */
package com.marktreble.f3ftimer.resultsmanager;

import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;

import com.marktreble.f3ftimer.R;
import com.marktreble.f3ftimer.data.race.Race;
import com.marktreble.f3ftimer.data.race.RaceData;
import com.marktreble.f3ftimer.dialog.AboutActivity;
import com.marktreble.f3ftimer.dialog.HelpActivity;
import com.marktreble.f3ftimer.pilotmanager.PilotsActivity;
import com.marktreble.f3ftimer.racemanager.RaceListActivity;

import java.util.ArrayList;

public class ResultsActivity extends ListActivity {

	private ArrayAdapter<String> mArrAdapter;
	private ArrayList<String> mArrNames;
	private ArrayList<Integer> mArrIds;

    private Context mContext;

	static final boolean DEBUG = true;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		ImageView view = (ImageView)findViewById(android.R.id.home);
		Resources r = getResources();
		int px = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, r.getDisplayMetrics());
		view.setPadding(0, 0, px, 0);

        mContext = this;

		setContentView(R.layout.results_manager);

        mArrNames = new ArrayList<>();
        mArrIds = new ArrayList<>();

	    this.getNamesArray();   	    
   	   	mArrAdapter = new ArrayAdapter<>(this, R.layout.listrow , mArrNames);
        setListAdapter(mArrAdapter);        
	}
	
	@Override
	public void onResume(){
        super.onResume();

        this.getNamesArray();
        mArrAdapter.notifyDataSetChanged();
    }

	@Override
	public void onListItemClick(ListView l, View v, int position, long id) {
        Intent intent = new Intent(this, ResultsRaceActivity.class);
        Integer pid = mArrIds.get(position);
        intent.putExtra("race_id", pid);
    	startActivityForResult(intent, pid);
	}
	
	private void getNamesArray(){

		RaceData datasource = new RaceData(this);
		datasource.open();
		ArrayList<Race> allRaces = datasource.getAllRaces();
		datasource.close();
		
        mArrNames.removeAll(mArrNames);
        mArrIds.removeAll(mArrIds);

		
		for (Race r: allRaces){
			mArrNames.add(String.format("%s", r.name));
			mArrIds.add(r.id);
			
		}
	}

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.results_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle presses on the action bar items
        switch (item.getItemId()) {
            case R.id.menu_pilot_manager:
                pilotManager();
                return true;
            case R.id.menu_race_manager:
                raceManager();
                return true;
            case R.id.menu_help:
                help();
                return true;
            case R.id.menu_about:
                about();
                return true;


            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void pilotManager(){
        Intent intent = new Intent(mContext,PilotsActivity.class);
        startActivity(intent);
    }

    public void raceManager(){
        Intent intent = new Intent(mContext, RaceListActivity.class);
        startActivity(intent);
    }

    public void help(){
        Intent intent = new Intent(mContext, HelpActivity.class);
        startActivity(intent);
    }

    public void about(){
        Intent intent = new Intent(mContext, AboutActivity.class);
        startActivity(intent);
    }
}
