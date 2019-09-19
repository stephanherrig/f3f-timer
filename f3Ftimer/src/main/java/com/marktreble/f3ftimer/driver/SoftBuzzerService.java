package com.marktreble.f3ftimer.driver;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.marktreble.f3ftimer.R;
import com.marktreble.f3ftimer.racemanager.RaceActivity;

import org.apache.commons.lang3.StringUtils;

import java.text.DecimalFormat;

public class SoftBuzzerService extends Service implements DriverInterface, Thread.UncaughtExceptionHandler {

    private static final String TAG = "SoftBuzzerService";
    
    private static DecimalFormat mNumberFormatter = new DecimalFormat("+0.00;-0.00");

	private Driver mDriver;

    public int mTimerStatus = 0;

    public boolean mBoardConnected = false;

    static final String ICN_CONN = "on";
    static final String ICN_DISCONN = "off";

    private Handler mWindEmulator;
    private static float mSlopeOrientation = 0.0f;
    
    private boolean mWindLegal = false;
    private boolean mWindSpeedIlegal = false;
    private boolean mWindDirectionIlegal = false;
    private long mWindSpeedLegalTimestamp;
    private long mWindDirectionLegalTimestamp;
    
    private static final long WIND_ILLEGAL_TIMEOUT = 20000;

	/*
	 * General life-cycle function overrides
	 */

    @Override
    public void uncaughtException(Thread thread, Throwable ex){
        stopSelf();
    }
    
	@Override
    public void onCreate() {
		super.onCreate();
		mDriver = new Driver(this);

        this.registerReceiver(onBroadcast, new IntentFilter("com.marktreble.f3ftimer.onUpdateFromUI"));

        Thread.setDefaultUncaughtExceptionHandler(this);
    }

	@Override
	public void onDestroy() {
        Log.i(TAG, "Destroyed");
		super.onDestroy();
        if (mDriver != null)
		    mDriver.destroy();

        try {
            this.unregisterReceiver(onBroadcast);
        } catch (IllegalArgumentException e){
            e.printStackTrace();
        }
        mBoardConnected = false;
        driverDisconnected();

        mWindEmulator.removeCallbacksAndMessages(null);

    }

    public static void startDriver(RaceActivity context, String inputSource, Integer race_id, Bundle params){
        if (inputSource.equals(context.getString(R.string.Demo))){
            Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
            i.putExtra("icon", ICN_DISCONN);
            i.putExtra("com.marktreble.f3ftimer.service_callback", "driver_stopped");
            context.sendBroadcast(i);

            Intent serviceIntent = new Intent(context, SoftBuzzerService.class);
            serviceIntent.putExtras(params);
            serviceIntent.putExtra("com.marktreble.f3ftimer.race_id", race_id);
            context.startService(serviceIntent);
        }
    }

    public static boolean stop(RaceActivity context){
        if (context.isServiceRunning("com.marktreble.f3ftimer.driver.SoftBuzzerService")) {
            Log.d(TAG,"SERVER STOPPED");
            Intent serviceIntent = new Intent(context, SoftBuzzerService.class);
            context.stopService(serviceIntent);
            return true;
        }
        return false;
    }
    
    // Binding for UI->Service Communication
    private BroadcastReceiver onBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra("com.marktreble.f3ftimer.ui_callback")) {
                Bundle extras = intent.getExtras();
                if (extras == null)
                    extras = new Bundle();

                String data = extras.getString("com.marktreble.f3ftimer.ui_callback", "");
                Log.i(TAG, data);

                if (data.equals("get_connection_status")) {
                    if (mBoardConnected){
                        driverConnected();
                    } else {
                        driverDisconnected();
                    }
                }

                if (data.equals("pref_wind_angle_offset")) {
                    mSlopeOrientation = Float.valueOf(intent.getExtras().getString("com.marktreble.f3ftimer.value"));
                }
            }
        }
    };
    
	@Override
    public int onStartCommand(Intent intent, int flags, int startId){
    	super.onStartCommand(intent, flags, startId);
        driverDisconnected();

        mBoardConnected = true;
        mDriver.start(intent);
        driverConnected();

        // Output dummy wind readings
        Bundle extras = intent.getExtras();
        mSlopeOrientation = 0.f;
        if (extras != null)
            mSlopeOrientation = Float.parseFloat(extras.getString("pref_wind_angle_offset", "0.0"));

        mWindEmulator = new Handler();
        Runnable runnable = new Runnable() {
            long currentTime;

            @Override
            public void run() {
                currentTime = System.currentTimeMillis();
                Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
                float wind_angle_absolute = mSlopeOrientation + (float)(Math.random()*20) - 1.f;
                float wind_angle_relative = wind_angle_absolute - mSlopeOrientation;
                if (wind_angle_relative > 180) {
                    wind_angle_relative = wind_angle_relative - 360;
                } else if (wind_angle_relative < -180) {
                    wind_angle_relative = 360 + wind_angle_relative;
                }
                float wind_speed = 3f + (float)(Math.random()*3) - 1.5f;

                if ((wind_speed >= 3) && (wind_speed <= 25)) {
                    mWindSpeedLegalTimestamp = currentTime;
                    mWindSpeedIlegal = false;
                } else {
                    if (!mWindSpeedIlegal) {
                        mWindSpeedLegalTimestamp = currentTime - 1;
                        mWindSpeedIlegal = true;
                    }
                }
                if ((wind_angle_relative <= 45) && (wind_angle_relative >= -45)) {
                    mWindDirectionLegalTimestamp = currentTime;
                    mWindDirectionIlegal = false;
                } else {
                    if (!mWindDirectionIlegal) {
                        mWindDirectionLegalTimestamp = currentTime - 1;
                        mWindDirectionIlegal = true;
                    }
                }

                /* compute user readable wind report */
                if (((currentTime - mWindSpeedLegalTimestamp) < WIND_ILLEGAL_TIMEOUT) &&
                    ((currentTime - mWindDirectionLegalTimestamp) < WIND_ILLEGAL_TIMEOUT)) {
                    mWindLegal = true;
                    mDriver.windLegal();
                } else {
                    mWindLegal = false;
                    mDriver.windIllegal();
                }

                String wind_data = formatWindValues(mWindLegal, wind_angle_absolute, wind_angle_relative, wind_speed,
                        currentTime - mWindSpeedLegalTimestamp, currentTime - mWindDirectionLegalTimestamp,0);
                i.putExtra("com.marktreble.f3ftimer.value.wind_values", wind_data);
                sendBroadcast(i);

                mWindEmulator.postDelayed(this, 1000);
            }
        };
        mWindEmulator.post(runnable);

    	return (START_STICKY);    	
    }
       	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
	
	// Input - Listener Loop
	private void base(String base){
        switch (mTimerStatus){
            case 0:
                mDriver.offCourse();
                break;
            case 1:
                mDriver.onCourse();
                break;
            default:
                mDriver.legComplete();
                break;

        }
        mTimerStatus++;
        
    }

	// Driver Interface implementations
    public void driverConnected(){
        mDriver.driverConnected(ICN_CONN);
    }

    public void driverDisconnected(){
        mDriver.driverDisconnected(ICN_DISCONN);
    }
    
    public void sendWorkingTimeStarted(){
    }

    public void sendLaunch(){
        mTimerStatus = 0;
	}

	public void sendAbort(){
	}
	
	public void sendAdditionalBuzzer(){
	}
	
	public void sendResendTime(){
	}
    
    public void baseA(){
        Log.i(TAG, "BASE A "+ Integer.toString(mTimerStatus % 2));
        if ((mTimerStatus == 0) || (mTimerStatus%2 == 1))
            base("A");
    }
    
    public void baseB(){
        Log.i(TAG, "BASE B "+ Integer.toString(mTimerStatus % 2));
        if ((mTimerStatus>0) && (mTimerStatus%2 == 0))
            base("B");
    }
    
    public void finished(String time){
        Log.d(TAG, "TIME "+ time.trim());
        mDriver.mPilot_Time = Float.parseFloat(time.trim().replace(",", "."));
        Log.d(TAG, "TIME "+ Float.toString(mDriver.mPilot_Time) );
        mDriver.runComplete();
        mTimerStatus = 0;
        mDriver.ready();

    }

    public String formatWindValues(boolean windLegal, float windAngleAbsolute, float windAngleRelative, float windSpeed, long windSpeedIllegalTimer, long windDirectionIllegalTimer, long windDisconnectedTimer) {
        String str = String.format("a: %s°", StringUtils.leftPad(mNumberFormatter.format(windAngleAbsolute),7)).replace(",",".")
                + String.format(" r: %s°", StringUtils.leftPad(mNumberFormatter.format(windAngleRelative),7)).replace(",",".")
                + String.format(" v: %sm/s", StringUtils.leftPad(mNumberFormatter.format(windSpeed),7)).replace(",",".");
    
        if (!windLegal) {
            if (windDisconnectedTimer >= WIND_ILLEGAL_TIMEOUT) {
                str += " s: illegal (no data)";
            } else if ((windSpeedIllegalTimer >= WIND_ILLEGAL_TIMEOUT) && (windDirectionIllegalTimer >= WIND_ILLEGAL_TIMEOUT)) {
                str += " s: illegal (bad speed and direction)";
            } else if (windSpeedIllegalTimer >= WIND_ILLEGAL_TIMEOUT) {
                str += " s: illegal (bad speed)";
            } else if (windDirectionIllegalTimer >= WIND_ILLEGAL_TIMEOUT) {
                str += " s: illegal (bad direction)";
            }
        } else {
            long windIllegalTimer;
            if (windSpeedIllegalTimer > windDirectionIllegalTimer) {
                windIllegalTimer = windSpeedIllegalTimer;
            } else {
                windIllegalTimer = windDirectionIllegalTimer;
            }
        
            if ((windSpeedIllegalTimer > 0) && (windDirectionIllegalTimer > 0)) {
                str += String.format(" s:  legal (%ds - bad speed and direction)", (int)Math.ceil((WIND_ILLEGAL_TIMEOUT - windIllegalTimer) / 1000f));
            } else if (windSpeedIllegalTimer > 0) {
                str += String.format(" s:  legal (%ds - bad speed)", (int)Math.ceil((WIND_ILLEGAL_TIMEOUT - windIllegalTimer) / 1000f));
            } else if (windDirectionIllegalTimer > 0) {
                str += String.format(" s:  legal (%ds - bad direction)", (int)Math.ceil((WIND_ILLEGAL_TIMEOUT - windIllegalTimer) / 1000f));
            } else {
                str += " s:  legal";
            }
        }
        return str;
    }
}
