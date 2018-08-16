package com.marktreble.f3ftimer.driver;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;

import com.marktreble.f3ftimer.R;
import com.marktreble.f3ftimer.racemanager.RaceActivity;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.DecimalFormat;

import static java.lang.Thread.sleep;

public class TcpIoService extends Service implements DriverInterface {

	private static final String TAG = "TcpIoService";
	private static final String DEFAULT_F3FTIMER_SERVER_IP = "192.168.42.2";
	private static final int F3FTIMER_SERVER_PORT = 1234;
    
    private static final long WIND_ILLEGAL_TIME = 20000;

	// Commands from raspberrypi
	private static final String FT_TURNA = "A";
	private static final String FT_TURNB = "B";
	private static final String FT_START = "S";
	private static final String FT_CANCEL = "C";
	private static final String FT_CANCEL_ZERO = "Z";
	private static final String FT_PENALTY = "P";
	private static final String FT_WIND = "W";
	private static final String FT_TIME = "T";
	private static final String FT_LEGS = "L";
	private static final String FT_SPEECH = "X";

	private static final String ICN_CONN = "on_rasp";
	private static final String ICN_DISCONN = "off_rasp";
	
	private static DecimalFormat mNumberFormatter = new DecimalFormat("+0.00;-0.00");
	
	private Intent mIntent;

	private int mTimerStatus = 0;
	private int mState = 0;
	private boolean mConnected = false;
    
    private boolean mDriverDestroyed = true;
	
	boolean mWindDisconnected = true;
	boolean mWindLegal = false;
	long mWindMeasurementReceivedTimestamp;
	long mWindSpeedLegalTimestamp;
	long mWindDirectionLegalTimestamp;

	private boolean mTurnA = false;
	private boolean mTurnB = false;
	private int mLeg = 0;

	private boolean timeAlreadyReceived;
	private boolean timeReceived;

	private boolean mReceivedAbort = false;

	private static float mSlopeOrientation = 0.0f;
	private static String mF3ftimerServerIp = DEFAULT_F3FTIMER_SERVER_IP;

	private static boolean stopConnectThread = false;
	private static Driver mDriver;
	private static Socket mmSocket;
	private static InputStream mmInStream;
	private static OutputStream mmOutStream;
	private static Thread connectThread;
	private static Thread listenThread;
	private static SendThread sendThread;

	/*
	 * General life-cycle function overrides
	 */

	@Override
    public void onCreate() {
		super.onCreate();
		mDriver = new Driver(this);

		mF3ftimerServerIp = PreferenceManager.getDefaultSharedPreferences(this).getString("pref_input_tcpio_ip", DEFAULT_F3FTIMER_SERVER_IP);
		mSlopeOrientation = Float.parseFloat(PreferenceManager.getDefaultSharedPreferences(this).getString("pref_wind_angle_offset", "0.0"));

		this.registerReceiver(onBroadcast, new IntentFilter("com.marktreble.f3ftimer.onUpdateFromUI"));
    }

	@Override
	public void onDestroy() {
		Log.i(TAG, "ONDESTROY!");
		super.onDestroy();

		stopConnectThread = true;

		if (mConnected) driverDisconnected();

		try {
			this.unregisterReceiver(onBroadcast);
		} catch (IllegalArgumentException e){
			e.printStackTrace();
		}

		destroy();
    }

    private void destroy() {
		mConnected = false;

		try {
			if (mmInStream != null) mmInStream.close();
			if (mmOutStream != null) mmOutStream.close();
			if (mmSocket != null) mmSocket.close();

		} catch (IOException e){
			e.printStackTrace();
		}

		if (connectThread != null) {
			connectThread.interrupt();
			connectThread = null;
		}
		if (sendThread != null) {
			sendThread.interrupt();
			sendThread = null;
		}
		if (listenThread != null) {
			listenThread.interrupt();
			listenThread = null;
		}

		if (mDriver != null && !mDriverDestroyed) {
			mDriver.destroy();
			mDriverDestroyed = true;
		}
	}

	public static void startDriver(RaceActivity context, String inputSource, Integer race_id, Bundle params){
		if (inputSource.equals(context.getString(R.string.TCP_IO))){
			Intent serviceIntent = new Intent(context, TcpIoService.class);
			serviceIntent.putExtras(params);
			mF3ftimerServerIp = params.getString("pref_input_tcpio_ip", DEFAULT_F3FTIMER_SERVER_IP);
			mSlopeOrientation = Float.parseFloat(params.getString("pref_wind_angle_offset"));
			serviceIntent.putExtra("com.marktreble.f3ftimer.race_id", race_id);
			context.startService(serviceIntent);
			Log.d(TAG, "TCP DRIVER STARTED");
		}
	}

	public static boolean stop(RaceActivity context){
		if (context != null && context.isServiceRunning("com.marktreble.f3ftimer.driver.TcpIoService")) {
			Log.i(TAG, "RUNNING");
			Intent serviceIntent = new Intent(context, TcpIoService.class);
			context.stopService(serviceIntent);
			return true;
		}
		Log.i(TAG, "NOT RUNNING??");
		return false;
	}

	// Binding for UI->Service Communication
	private BroadcastReceiver onBroadcast = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.hasExtra("com.marktreble.f3ftimer.ui_callback")) {
				Bundle extras = intent.getExtras();
				String data = extras.getString("com.marktreble.f3ftimer.ui_callback");
				Log.d(TAG, data);

				if (data == null) return;

				if (data.equals("get_connection_status")) {
					if (!mConnected) {
						driverDisconnected();
					} else {
						driverConnected();
					}
				}

				if (data.equals("model_launched")) {
					mState = 2;
				}

				if (data.equals("working_time_started")) {
					mState = 1;
				}

				if (data.equals("pref_wind_angle_offset")) {
					mSlopeOrientation = Float.valueOf(intent.getExtras().getString("com.marktreble.f3ftimer.value"));
					Log.d("TcpIoService", "pref_wind_angle_offset=" + mSlopeOrientation);
				}

				if (data.equals("pref_input_tcpio_ip")) {
					mF3ftimerServerIp = intent.getExtras().getString("com.marktreble.f3ftimer.value", DEFAULT_F3FTIMER_SERVER_IP);
					Log.d("TcpIoService", "Connecting to new IP: " + mF3ftimerServerIp);
					destroy();
				}
			}
		}
	};

	@Override
    public int onStartCommand(Intent intent, int flags, int startId){
    	super.onStartCommand(intent, flags, startId);

		Log.d(TAG, "onStartCommand");
		mIntent = intent;

		if (!mConnected) {
			driverDisconnected();
		} else {
			driverConnected();
		}

		startConnectThread();

		return (START_STICKY);
	}


	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private class SendThread extends Thread {
		private Handler handler;
		private Looper myLooper;

		@SuppressLint("HandlerLeak")
		private SendThread() {
		}

		public void destroy() {
			myLooper.quit();
		}

		public void interrupt() {
			myLooper.quit();
			super.interrupt();
		}

		void sendCmd(String cmd) {
			Message msg = new Message();
			msg.obj = cmd;
			handler.sendMessage(msg);
		}

		@Override
		public void run() {
			setName("SendThread" + this.getId());
			Looper.prepare();
			myLooper = Looper.myLooper();
			handler = new Handler(myLooper) {
				public void handleMessage(Message msg) {
					try {
						String cmd = ((String)msg.obj);
						if (cmd.length() > 0)
							Log.i(TAG, "send Cmd \"" + cmd + "\" (" + cmd.length() + ")");
						mmOutStream.write(cmd.getBytes(), 0, cmd.length());
						mmOutStream.flush();
					} catch (Throwable e) {
						handleSocketThrowable(e);
					}
				}
			};
			// send leg count to fly per flight
			sendCmd(FT_LEGS + Driver.LEGS_PER_FLIGHT + " ");
			Looper.loop();
		}
	}

	@Override
	public void driverConnected() {
		mDriver.driverConnected(ICN_CONN);
	}

	@Override
	public void driverDisconnected() {
		mDriver.driverDisconnected(ICN_DISCONN);
	}

	// Driver Interface implementations
	public void sendLaunch(){
		timeAlreadyReceived = false;
		timeReceived = false;
		if (sendThread != null) {
			Log.i(TAG, "sendLaunch");
			sendThread.sendCmd(FT_START + " ");
		}
	}

	public void sendAbort(){
		mState = 0;
		mTimerStatus = 0;
		mLeg = 0;

		if (sendThread != null && !mReceivedAbort) {
			Log.i(TAG, "sendAbort");
			sendThread.sendCmd(FT_CANCEL + " ");
		} else {
			Log.i(TAG, "received Abort -> no sendAbort");
			mReceivedAbort = false;
		}
	}

	public void sendAdditionalBuzzer(){}

	public void sendResendTime(){}

	public void baseA(){}
	public void baseB(){}

	public void finished(String time){
		if (!timeReceived) {
			try {
				sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		if (!timeAlreadyReceived) {
			timeAlreadyReceived = true;
			Log.d(TAG, "TIME " + time.trim());
			mDriver.mPilot_Time = Float.parseFloat(time.trim().replace(",", "."));
			Log.d(TAG, "TIME " + Float.toString(mDriver.mPilot_Time));
			mDriver.runComplete();
			mState = 0;
			mTimerStatus = 0;
			mLeg = 0;
			mDriver.ready();
		}
	}

	public void sendSpeechText(String lang, String text){
		if (sendThread != null) {
			Log.i(TAG, "sendSpeechText \"" + lang.substring(0, 2) + "\" \"" + text + "\"");
			sendThread.sendCmd(FT_SPEECH + lang.substring(0, 2) + text + " ");
		}
	}

	// socket functions
	private void startConnectThread() {
		mConnected = false;

		stopConnectThread = true;
		if (connectThread != null) {
			connectThread.interrupt();
			try {
				connectThread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		connectThread = new Thread(new Runnable() {
			@Override
			public void run() {
				Thread.currentThread().setName("ConnectThread" + Thread.currentThread().getId());
				stopConnectThread = false;
				while (!stopConnectThread) {
					Log.d(TAG, "Starting Runnable");
					android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

					try {
						// Connect the device through the socket. This will block
						// until it succeeds or throws an exception
						InetSocketAddress rpiSocketAdr = new InetSocketAddress(mF3ftimerServerIp, F3FTIMER_SERVER_PORT);
						Log.i(TAG, "connecting to " + rpiSocketAdr.getHostName() + ":" + rpiSocketAdr.getPort());
						mmSocket = new Socket();
						mmSocket.setReuseAddress(true);
						mmSocket.setTcpNoDelay(true);
						mmSocket.setSoLinger(false, 0);
						mmSocket.setSoTimeout(1000);
						//mmSocket.setKeepAlive(true);
						mmSocket.connect(rpiSocketAdr, 5000);
						// Do work to manage the connection (in a separate thread)
						Log.d(TAG, "GET IO STREAMS");
						mmInStream = mmSocket.getInputStream();
						mmOutStream = mmSocket.getOutputStream();

						mDriver.start(mIntent);
						mDriverDestroyed = false;

						if (listenThread != null) {
							listenThread.interrupt();
						}
						listenThread = new Thread(null, new Runnable() {
							@Override
							public void run() {
								Thread.currentThread().setName("ListenThread" + Thread.currentThread().getId());
								listen();
							}
						});
						listenThread.start();

						if (sendThread != null) {
							sendThread.interrupt();
						}
						sendThread = new SendThread();
						sendThread.start();
						
						mConnected = true;
						driverConnected();

						Log.i(TAG, "connected to " + rpiSocketAdr.getHostName() + ":" + rpiSocketAdr.getPort());
					} catch (IOException connectException) {
						closeSocketAndStop();
					}
					try {
						sleep(100);
					} catch (InterruptedException e) {
						if (!stopConnectThread) e.printStackTrace();
					}
					while (mConnected && !stopConnectThread) {
						try {
							sleep(500);
						} catch (InterruptedException e) {
							if (!stopConnectThread) e.printStackTrace();
						}
					}
				}
			}
		});
		connectThread.start();
	}

	private void listen() {
		// Listen
		byte[] buffer = new byte[1024];  // 1K buffer store for the stream
		int bufferLength; // bytes returned from read()
		String wind_angle_str;
		String wind_speed_str;
		float wind_angle_absolute = 0;
		float wind_angle_relative = 0;
		float wind_speed = 0;
		long currentTime;

		//noinspection InfiniteLoopStatement
		while(true) {
			currentTime = System.currentTimeMillis();
			try {
				// Read from the InputStream
				bufferLength = mmInStream.read(buffer);
				if (bufferLength > 0) {
					byte[] data = new byte[bufferLength];
					System.arraycopy(buffer, 0, data, 0, bufferLength);
					String strbuf = new String(data, 0, data.length).replaceAll("[^\\x20-\\x7F]", "").trim();
					String strarray[] = strbuf.split(" ");
					for (String str : strarray) {
						int len = str.length();
						if (len > 0) {
							// Get code (first char)
							String code = str.substring(0, 1);
							// We have data/command from the timer, pass this on to the server
							switch (code) {
								case FT_START:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									if (mState < 2) {
										mDriver.startPressed();
										mState++;
									}
									break;
								case FT_CANCEL:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									cancelDialog();
									mReceivedAbort = true;
									sendAbort();
									break;
								case FT_CANCEL_ZERO:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									scoreZeroAndCancelDialogAndNextPilot();
									mReceivedAbort = true;
									sendAbort();
									break;
								case FT_PENALTY:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									if (mLeg >= 1) {
										mDriver.incPenalty();
									}
									break;
								case FT_TIME:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									String flight_time = str.substring(1, str.length());
									Log.i(TAG, "Flight time: " + flight_time);
									timeReceived = true;
									finished(flight_time);
									break;
								case FT_TURNB:
									// after the model has been started only accept turn A as the first signal
									// then only accept A after B after A ...
									mTurnB = true;
								case FT_TURNA:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									if (!mTurnB) mTurnA = true;
									if (mState >= 2) {
										switch (mTimerStatus) {
											case 0:
												if (mTurnA) {
													mDriver.offCourse();
												}
												break;
											case 1:
												if (mTurnA) {
													mDriver.onCourse();
													mLeg = 1;
												}
												break;
											default:
												int turn = mLeg % 2;
												if ((turn == 1 && mTurnB) || (turn == 0 && mTurnA)) {
													mDriver.legComplete();
													mLeg++;
												}
												break;
										}
										if (mTimerStatus <= 1 && mTurnA) {
											mTimerStatus++;
										}
									}
									mTurnA = false;
									mTurnB = false;
									break;
								case FT_WIND:
									if (mDriver.mWindMeasurement) {
										Log.d(TAG, "received: \"" + strbuf + "\"");
										try {
											wind_angle_str = str.substring(str.indexOf(",") + 1, str.lastIndexOf(","));
											wind_speed_str = str.substring(str.lastIndexOf(",") + 1, str.length());
											/* decode wind measurement */
											wind_angle_absolute = (Float.parseFloat(wind_angle_str)) % 360;
											wind_angle_relative = wind_angle_absolute - mSlopeOrientation;
											if (wind_angle_absolute > 180 + mSlopeOrientation) {
												wind_angle_relative -= 360;
											}
											wind_speed = Float.parseFloat(wind_speed_str);
											/* evaluate validity of wind values */
											if ((wind_speed >= 3) && (wind_speed <= 25)) {
												mWindSpeedLegalTimestamp = currentTime;
											}
											if ((wind_angle_relative <= 45) && (wind_angle_relative >= -45)) {
												mWindDirectionLegalTimestamp = currentTime;
											}
											mWindMeasurementReceivedTimestamp = currentTime;
											mWindDisconnected = false;
											/* compute user readable wind report */
											if (((currentTime - mWindSpeedLegalTimestamp) < WIND_ILLEGAL_TIME) && ((currentTime - mWindDirectionLegalTimestamp) < WIND_ILLEGAL_TIME)) {
												mWindLegal = true;
												mDriver.windLegal();
											} else {
												mWindLegal = false;
												mDriver.windIllegal();
											}
										} catch (NumberFormatException | IndexOutOfBoundsException e) {
											if (!stopConnectThread) e.printStackTrace();
										}
									}
									break;
							} // switch
						}
					} // end for loop
				} else if (bufferLength == -1) {
					closeSocketAndStop();
				}
			} catch (Throwable e) {
				if (1 == handleSocketThrowable(e)) {
					break;
				}
			}
			try {
				sendThread.sendCmd(""); // send alive
				
				if (mDriver.mWindMeasurement) {
					if (!mWindDisconnected) {
						if ((currentTime - mWindMeasurementReceivedTimestamp) > WIND_ILLEGAL_TIME) {
							wind_angle_absolute = 0;
							wind_angle_relative = 0;
							wind_speed = 0;
							mWindLegal = false;
							mDriver.windIllegal();
							mWindDisconnected = true;
						}
						String wind_data = formatWindValues(mWindLegal, wind_angle_absolute, wind_angle_relative, wind_speed, mWindMeasurementReceivedTimestamp - mWindSpeedLegalTimestamp, mWindMeasurementReceivedTimestamp - mWindDirectionLegalTimestamp, currentTime - mWindMeasurementReceivedTimestamp);
						/* send wind report to GUI */
						Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
						i.putExtra("com.marktreble.f3ftimer.value.wind_values", wind_data);
						sendBroadcast(i);
					}
				}
			} catch (Throwable e) {
				if (1 == handleSocketThrowable(e)) {
					break;
				}
			}
		}
	}

	private int handleSocketThrowable(Throwable e) {
		if (!(e instanceof SocketTimeoutException)) {
			closeSocketAndStop();
			if (!stopConnectThread) e.printStackTrace();
			return 1;
		} else {
			try {
				sendThread.sendCmd(""); // send alive
			} catch (Throwable e1) {
				e1.printStackTrace();
			}
		}
		return 0;
	}

	private void closeSocketAndStop() {
		if (mConnected) {
			driverDisconnected();
			if (!stopConnectThread) Log.d(TAG, "Not Listening anymore (EXCEPTION)");
			mConnected = false;
		}
		try {
			mmSocket.close();
		} catch (IOException e1) {
			if (!stopConnectThread) e1.printStackTrace();
		}
	}

	private void cancelDialog(){
		mDriver.cancelWorkingTime();
		Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
		i.putExtra("com.marktreble.f3ftimer.service_callback", "cancel");
		sendBroadcast(i);
	}

	private void scoreZeroAndCancelDialogAndNextPilot(){
		mDriver.cancelWorkingTime();
		Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
		i.putExtra("com.marktreble.f3ftimer.service_callback", "score_zero_and_cancel");
		i.putExtra("com.marktreble.f3ftimer.pilot_id", mDriver.mPid);
		sendBroadcast(i);
	}

	public String formatWindValues(boolean windLegal, float windAngleAbsolute, float windAngleRelative, float windSpeed, long windSpeedIllegalTimer, long windDirectionIllegalTimer, long windDisconnectedTimer) {
		String str = String.format("a: %s°", StringUtils.leftPad(mNumberFormatter.format(windAngleAbsolute),7)).replace(",",".")
				   + String.format(" r: %s°", StringUtils.leftPad(mNumberFormatter.format(windAngleRelative),7)).replace(",",".")
				   + String.format(" v: %sm/s", StringUtils.leftPad(mNumberFormatter.format(windSpeed),7)).replace(",",".");
		
		if (windSpeedIllegalTimer > WIND_ILLEGAL_TIME) {
			windSpeedIllegalTimer = WIND_ILLEGAL_TIME;
		}
		if (windDirectionIllegalTimer > WIND_ILLEGAL_TIME) {
			windDirectionIllegalTimer = WIND_ILLEGAL_TIME;
		}
		if (windDisconnectedTimer > WIND_ILLEGAL_TIME) {
			windDisconnectedTimer = WIND_ILLEGAL_TIME;
		}

		if (!windLegal) {
			if (windDisconnectedTimer >= WIND_ILLEGAL_TIME) {
				str += " illegal (no data)";
			} else if ((windSpeedIllegalTimer >= WIND_ILLEGAL_TIME) && (windDirectionIllegalTimer >= WIND_ILLEGAL_TIME)) {
				str += " illegal speed and direction";
			} else if (windSpeedIllegalTimer >= WIND_ILLEGAL_TIME) {
				str += " illegal speed";
			} else if (windDirectionIllegalTimer >= WIND_ILLEGAL_TIME) {
				str += " illegal direction";
			}
	    } else {
			long windIllegalTimer;
			if (windSpeedIllegalTimer > windDirectionIllegalTimer) {
				windIllegalTimer = windSpeedIllegalTimer;
			} else {
				windIllegalTimer = windDirectionIllegalTimer;
			}

			if (windIllegalTimer > 0) {
				str += String.format("   legal (%ds)", (WIND_ILLEGAL_TIME - windIllegalTimer) / 1000);
			} else {
				str += "   legal";
			}
        }
        return str;
	}
}
