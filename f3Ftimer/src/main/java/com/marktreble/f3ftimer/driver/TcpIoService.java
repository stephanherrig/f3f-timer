package com.marktreble.f3ftimer.driver;

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
import java.util.concurrent.locks.ReentrantLock;

public class TcpIoService extends Service implements DriverInterface {

	private static final String TAG = "TcpIoService";
	private static final String DEFAULT_F3FTIMER_SERVER_IP = "192.168.42.2";
	private static final int F3FTIMER_SERVER_PORT = 1234;
    
    private static final long WIND_ILLEGAL_TIME = 20000;
	private static final long RECONNECT_INTERVAL_TIME = 3000;
	private static final long RECEIVE_TIME_TIMEOUT = 1000;

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
	
	private static final DecimalFormat NUMBER_FORMATTER = new DecimalFormat("+0.00;-0.00");
	
	private static final ReentrantLock mThreadLock = new ReentrantLock();

	private static Socket mmSocket;
	private static InputStream mmInStream;
	private static OutputStream mmOutStream;
	
	private static ConnectThread connectThread;
	private static ListenThread listenThread;
	private static SendThread sendThread;
	private static AliveThread aliveThread;
	
	private static TcpIoService mInstance;
	
	private Driver mDriver;
	
	private BroadcastReceiver onBroadcast;
	
	private int mTimerStatus = 0;
	private int mState = 0;
	private boolean mConnected = false;
    
    private boolean mWindDisconnected = true;
    private boolean mWindLegal = false;
    private boolean mWindSpeedIlegal = false;
    private boolean mWindDirectionIlegal = false;
    private long mWindMeasurementReceivedTimestamp;
    private long mWindSpeedLegalTimestamp;
    private long mWindDirectionLegalTimestamp;

	private boolean mTurnA = false;
	private boolean mTurnB = false;
	private int mLeg = 0;

	private boolean timeAlreadyReceived;

	private long mDNFButtonTimestamp = 0;
	private long mLastReceiveTimestamp = 0;

	private float mSlopeOrientation = 0.0f;
	private String mF3ftimerServerIp = DEFAULT_F3FTIMER_SERVER_IP;
	

	private class ConnectThread extends Thread {
		private boolean quit = false;
		private boolean reconnecting = false;
		
		public synchronized void reconnect() {
			if (!reconnecting) {
				reconnecting = true;
				interrupt();
				Log.d(TAG, "ConnectThread interrupted");
			}
		}

		public synchronized void quit() {
			if (!quit) {
				quit = true;
				interrupt();
				Log.d(TAG, "ConnectThread quit");
			}
		}
		
		public boolean isRunning() {
			return !quit;
		}

		@Override
		public void run() {
			Thread.currentThread().setName("ConnectThread" + Thread.currentThread().getId());
			android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
			Looper.prepare();
			
			while (connectThread != null && connectThread.isRunning()) {
				mConnected = false;
				driverDisconnected();
				try {
					mThreadLock.lock();
					// Connect the device through the socket. This will block
					// until it succeeds or throws an exception
					InetSocketAddress rpiSocketAdr = new InetSocketAddress(mF3ftimerServerIp, F3FTIMER_SERVER_PORT);
					Log.i(TAG, "connecting to " + rpiSocketAdr.getHostName() + ":" + rpiSocketAdr.getPort());
					try {
						if (aliveThread != null) {
							Log.d(TAG, "joining aliveThread");
							aliveThread.quit();
							aliveThread.join();
							aliveThread = null;
							Log.d(TAG, "joined aliveThread");
						}

						if (sendThread != null) {
							Log.d(TAG, "joining sendThread");
							sendThread.quit();
							sendThread.join();
							sendThread = null;
							Log.d(TAG, "joined sendThread");
						}
						
						if (listenThread != null) {
							Log.d(TAG, "joining listenThread");
							listenThread.quit();
							listenThread.join();
							listenThread = null;
							Log.d(TAG, "joined listenThread");
						}

						if (mmSocket != null) {
							try {
								if (!mmSocket.isOutputShutdown()) {
									mmSocket.getOutputStream().flush();
									mmSocket.shutdownOutput();
									mmOutStream = null;
								}
								if (!mmSocket.isInputShutdown()) {
									mmSocket.shutdownInput();
									mmInStream = null;
								}
								if (!mmSocket.isClosed()) {
									mmSocket.close();
									mmSocket = null;
								}
							} catch (IOException e1) {
								e1.printStackTrace();
							}
						}
						/* now wait until the peer socket times out */
						sleep(RECONNECT_INTERVAL_TIME);
						/* reinitialize the socket */
						mmSocket = new Socket();
						mmSocket.setReuseAddress(true);
						mmSocket.setTcpNoDelay(true);
						mmSocket.setSoLinger(false, 0);
						mmSocket.setSoTimeout(1000);
						mmSocket.setSendBufferSize(64);
						mmSocket.setReceiveBufferSize(64);
						//mmSocket.setKeepAlive(true);
						mmSocket.connect(rpiSocketAdr, 5000);
						// Do work to manage the connection (in a separate thread)
						mmInStream = mmSocket.getInputStream();
						mmOutStream = mmSocket.getOutputStream();
						Log.d(TAG, "Socket created");

						Log.d(TAG, "starting sendThread");
						sendThread = new SendThread();
						sendThread.start();
						
						Log.d(TAG, "starting listenThread");
						listenThread = new ListenThread();
						listenThread.start();
						
						Log.d(TAG, "starting aliveThread");
						aliveThread = new AliveThread();
						aliveThread.start();
						
						reconnecting = false;
						mConnected = true;
						driverConnected();
						
						Log.i(TAG, "connected to " + rpiSocketAdr.getHostName() + ":" + rpiSocketAdr.getPort());
						mThreadLock.unlock();

						while (connectThread != null && connectThread.isRunning()) {
							sleep(1000);
						}
					} catch (IOException connectException) {
						connectException.printStackTrace();
					}
				} catch (InterruptedException e) {
					// nothing to do
				} finally {
					if (mThreadLock.isHeldByCurrentThread()) {
						mThreadLock.unlock();
					}
				}
			}
			
			/* before quitting cleanup the resources */
			if (aliveThread != null) {
				Log.d(TAG, "joining aliveThread");
				aliveThread.quit();
				try {
					aliveThread.join();
				} catch (InterruptedException e) {
					// nothing to do
				}
				aliveThread = null;
				Log.d(TAG, "joined aliveThread");
			}
			
			if (sendThread != null) {
				Log.d(TAG, "joining sendThread");
				sendThread.quit();
				try {
					sendThread.join();
				} catch (InterruptedException e) {
					// nothing to do
				}
				sendThread = null;
				Log.d(TAG, "joined sendThread");
			}
			
			if (listenThread != null) {
				Log.d(TAG, "joining listenThread");
				listenThread.quit();
				try {
					listenThread.join();
				} catch (InterruptedException e) {
					// nothing to do
				}
				listenThread = null;
				Log.d(TAG, "joined listenThread");
			}
			if (mmSocket != null) {
				try {
					if (!mmSocket.isOutputShutdown()) {
						mmSocket.getOutputStream().flush();
						mmSocket.shutdownOutput();
						mmOutStream = null;
					}
					if (!mmSocket.isInputShutdown()) {
						mmSocket.shutdownInput();
						mmInStream = null;
					}
					if (!mmSocket.isClosed()) {
						mmSocket.close();
						mmSocket = null;
					}
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}
		}
	}
	
	private class ListenThread extends Thread {
		private boolean quit = false;
		
		public synchronized void quit() {
			if (!quit) {
				quit = true;
				interrupt();
				Log.d(TAG, "ListenThread quit");
			}
		}
		
		public boolean isRunning() {
			return !quit;
		}
		
		@Override
		public void run() {
			Thread.currentThread().setName("ListenThread" + this.getId());
			Looper.prepare();
			listen();
		}
	}

	private class SendThread extends Thread {
		private Handler handler;
		private Looper myLooper;
		private boolean quit = false;
		
		public synchronized void quit() {
			if (!quit) {
				quit = true;
				if (handler != null) {
					handler.removeCallbacksAndMessages(null);
					handler = null;
				}
				if (myLooper != null) {
					myLooper.quit();
					myLooper = null;
				}
				interrupt();
				Log.d(TAG, "SendThread quit");
			}
		}
		
		public boolean isRunning() {
			return !quit;
		}
		
		void sendCmd(String cmd) {
			if (handler != null) {
				Message msg = new Message();
				msg.obj = cmd;
				handler.sendMessage(msg);
			} else {
				Log.d(TAG, "not ready for sendCmd");
			}
		}
		
		@Override
		public void run() {
			Thread.currentThread().setName("SendThread" + this.getId());
			
			Looper.prepare();
			myLooper = Looper.myLooper();
			
			handler = new Handler(myLooper) {
				public void handleMessage(Message msg) {
					try {
						String cmd = ((String)msg.obj);
						if (cmd.length() > 0 && mmOutStream != null) {
							Log.i(TAG, "send Cmd \"" + cmd + "\" (" + cmd.length() + ")");
							mmOutStream.write(cmd.getBytes(), 0, cmd.length());
							mmOutStream.flush();
						}
					} catch (Throwable e) {
						handleThrowable(e);
					}
				}
			};
			
			// send leg count to fly per flight
			sendCmd(FT_LEGS + Driver.LEGS_PER_FLIGHT + " ");
			
			Looper.loop();
		}
	}
	
	private class AliveThread extends Thread {
		private boolean quit = false;
		
		public synchronized void quit() {
			if (!quit) {
				quit = true;
				interrupt();
				Log.d(TAG, "AliveThread quit");
			}
		}
		
		public boolean isRunning() {
			return !quit;
		}
		
		@Override
		public void run() {
			Thread.currentThread().setName("AliveThread" + this.getId());
			Looper.prepare();
			
			while (aliveThread != null && aliveThread.isRunning()) {
				try {
					Thread.sleep(RECONNECT_INTERVAL_TIME);
					if (mLastReceiveTimestamp < System.currentTimeMillis() - 5000) {
						Log.d(TAG, "ListenThread stalled ... reconnect");
						connectThread.reconnect();
					}
				}
				catch (InterruptedException e) {
					// nothing to do
				}
			}
		}
	}

	private class UIBroadcastReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.hasExtra("com.marktreble.f3ftimer.ui_callback")) {
				Bundle extras = intent.getExtras();
				String data = extras.getString("com.marktreble.f3ftimer.ui_callback");
				Log.d(TAG, data);
				
				if (data == null) return;
				
				if (data.equals("get_connection_status")) {
					if (!mConnected) {
						closeSocketAndDisconnect(false);
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
					stopSelf();
				}
			}
		}
	}

	/*
	 * General life-cycle function overrides
	 */

	@Override
    public void onCreate() {
		Log.d(TAG, "CREATE");
		super.onCreate();

		if (mInstance == null) {
			mInstance = this;
			
			mF3ftimerServerIp = PreferenceManager.getDefaultSharedPreferences(this).getString("pref_input_tcpio_ip", DEFAULT_F3FTIMER_SERVER_IP);
			mSlopeOrientation = Float.parseFloat(PreferenceManager.getDefaultSharedPreferences(this).getString("pref_wind_angle_offset", "0.0f"));
			
			mTimerStatus = 0;
			mState = 0;
			mConnected = false;
			mWindDisconnected = true;
			mWindLegal = false;
			mWindSpeedIlegal = false;
			mWindDirectionIlegal = false;
			mTurnA = false;
			mTurnB = false;
			mLeg = 0;
			mDNFButtonTimestamp = 0;
			mLastReceiveTimestamp = System.currentTimeMillis();
			
			mDriver = new Driver(this);
			
			onBroadcast = new UIBroadcastReceiver();
			registerReceiver(onBroadcast, new IntentFilter("com.marktreble.f3ftimer.onUpdateFromUI"));
		}
		Log.d(TAG, "CREATED");
	}

	@Override
	public void onDestroy() {
		Log.i(TAG, "ONDESTROY!");
		if (mDriver != null) {
			mDriver.destroy();
			mDriver = null;
		}
		unregisterReceiver(onBroadcast);
		closeSocketAndDisconnect(true);
		super.onDestroy();
		mInstance = null;
		Log.d(TAG, "DESTROYED");
	}
	
	public static void startDriver(RaceActivity context, String inputSource, Integer race_id, Bundle params){
		if (inputSource.equals(context.getString(R.string.TCP_IO))){
			Log.d(TAG, "STARTING TCP DRIVER");
			Intent serviceIntent = new Intent(context, TcpIoService.class);
			serviceIntent.putExtras(params);
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

	@Override
    public int onStartCommand(Intent intent, int flags, int startId){
    	super.onStartCommand(intent, flags, startId);
		
		Log.d(TAG, "onStartCommand");
		
		if (mDriver != null) mDriver.start(intent);
		
        if (connectThread != null) {
            Log.d(TAG, "Stop ConnectThread");
            connectThread.interrupt();
            try {
                connectThread.join();
				connectThread = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.d(TAG, "ConnectThread stopped");
        }
        connectThread = new ConnectThread();
        connectThread.start();
        Log.d(TAG, "ConnectThread started");
        
		return (START_STICKY);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void driverConnected() {
		Log.d(TAG, "driverConnected");
		if (mDriver != null) mDriver.driverConnected(ICN_CONN);
	}

	@Override
	public void driverDisconnected() {
		Log.d(TAG, "Disconnected");
		if (mDriver != null) mDriver.driverDisconnected(ICN_DISCONN);
	}
	
	private void callbackToUI(String cmd, Bundle params) {
		Log.d(TAG , "CallBackToUI: " + cmd);
		
		Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
		i.putExtra("com.marktreble.f3ftimer.service_callback", cmd);
		if (params != null) {
			i.putExtras(params);
		}
		sendBroadcast(i);
	}
	
	// Driver Interface implementations
	public void sendLaunch(){
		timeAlreadyReceived = false;
		if (sendThread != null && sendThread.isAlive()) {
			Log.i(TAG, "sendLaunch");
			sendThread.sendCmd(FT_START + " ");
		}
	}

	public void sendAbort(){
		cancel();
		if (sendThread != null && sendThread.isAlive()) {
			Log.i(TAG, "sendAbort Cancel");
			sendThread.sendCmd(FT_CANCEL + " ");
		}
	}

	public void sendAdditionalBuzzer(){}

	public void sendResendTime(){}

	public void baseA(){}
	public void baseB(){}
	
	public void finished(String time) {
		Log.d(TAG, "UI Flight time " + time.trim());
		if (!timeAlreadyReceived) {
			try {
				/* Wait some time for receiving the time remotely. */
				Thread.sleep(RECEIVE_TIME_TIMEOUT);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			/* If the time is not received remotely, then use the time from RaceTimerActivity */
			finishedRemote(time);
		}
	}
	
	private synchronized void finishedRemote(String time){
		/* Commit the time that is received first (either by socket or from RaceTimerActivity) */
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
		if (sendThread != null && sendThread.isAlive()) {
			Log.i(TAG, "sendSpeechText \"" + lang.substring(0, 2) + "\" \"" + text + "\"");
			sendThread.sendCmd(FT_SPEECH + lang.substring(0, 2) + text + " ");
		}
	}
	
	private void listen() {
		byte[] buffer = new byte[1024];  // 1K buffer store for the stream
		int bufferLength; // bytes returned from read()
		String wind_angle_str;
		String wind_speed_str;
		float wind_angle_absolute = 0;
		float wind_angle_relative = 0;
		float wind_speed = 0;
		long currentTime;

		//noinspection InfiniteLoopStatement
		while(mmInStream != null && listenThread != null && listenThread.isRunning()) {
			currentTime = System.currentTimeMillis();
			try {
				// Read from the InputStream
				bufferLength = mmInStream.read(buffer);
				if (bufferLength > 0) {
					mLastReceiveTimestamp = currentTime;
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
									break;
								case FT_CANCEL_ZERO:
                                    Log.d(TAG, "received: \"" + strbuf + "\"");
                                    if ((System.currentTimeMillis() - mDNFButtonTimestamp) < 2000) {
                                        Log.d(TAG, "scoreZeroAndCancelDialogAndNextPilot");
										scoreZeroAndCancelDialogAndNextPilot();
									}
									mDNFButtonTimestamp = System.currentTimeMillis();
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
									finishedRemote(flight_time);
									break;
								case FT_TURNB:
									// after the model has been started only accept turn A as the first signal
									// then only accept A after B after A ...
									mTurnB = true;
								case FT_TURNA:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									if (!mTurnB) mTurnA = true;
									Log.d(TAG, "mState " + mState + " mTurn" + (mTurnA ? "A" : (mTurnB ? "B" : "")));
									if (mState >= 2) {
										Log.d(TAG, "mTimerStatus " + mTimerStatus);
										switch (mTimerStatus) {
											case 0:
												if (mTurnA) {
													mDriver.offCourse();
													Log.d(TAG, "offCourse");
												}
												break;
											case 1:
												if (mTurnA) {
													mDriver.onCourse();
													Log.d(TAG, "onCourse");
													mLeg = 1;
												}
												break;
											default:
												int turn = mLeg % 2;
												Log.d(TAG, "mLeg " + mLeg + " turn " + turn);
												if ((turn == 1 && mTurnB) || (turn == 0 && mTurnA)) {
													mDriver.legComplete();
													Log.d(TAG, "legComplete");
													mLeg++;
												}
												break;
										}
										if (mTimerStatus <= 1 && mTurnA) {
											mTimerStatus = mTimerStatus + 1;
											Log.d(TAG, "mTimerStatus++ " + mTimerStatus);
										}
									}
									mTurnA = false;
									mTurnB = false;
									break;
								case FT_WIND:
									if (mDriver != null) {
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
												mWindMeasurementReceivedTimestamp = currentTime;
												mWindDisconnected = false;
												
												/* compute user readable wind report */
												if (((currentTime - mWindSpeedLegalTimestamp) < WIND_ILLEGAL_TIME) &&
														((currentTime - mWindDirectionLegalTimestamp) < WIND_ILLEGAL_TIME)) {
													mWindLegal = true;
													mDriver.windLegal();
												} else {
													mWindLegal = false;
													mDriver.windIllegal();
												}
											} catch (NumberFormatException | IndexOutOfBoundsException e) {
												e.printStackTrace();
											}
										}
									}
									break;
							} // switch
						}
					} // end for loop
				} else if (bufferLength == -1) {
					if (listenThread != null && listenThread.isRunning()) {
						closeSocketAndDisconnect(false);
					}
					break;
				}
			} catch (Throwable e) {
				if (listenThread != null && listenThread.isRunning()) {
					if (1 == handleThrowable(e)) {
						break;
					}
				}
			}
			
			
			if (listenThread != null && listenThread.isRunning()) {
				try {
					// now update wind data
					if (mDriver != null) {
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
								String wind_data = formatWindValues(mWindLegal, wind_angle_absolute, wind_angle_relative, wind_speed,
										mWindMeasurementReceivedTimestamp - mWindSpeedLegalTimestamp,
										mWindMeasurementReceivedTimestamp - mWindDirectionLegalTimestamp,
										currentTime - mWindMeasurementReceivedTimestamp);
								/* send wind report to GUI */
								Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
								i.putExtra("com.marktreble.f3ftimer.value.wind_values", wind_data);
								sendBroadcast(i);
							}
						}
					}
				} catch (Throwable e) {
					if (listenThread != null && listenThread.isRunning()) {
						if (1 == handleThrowable(e)) {
							break;
						}
					}
				}
			}
		}
	}

	private int handleThrowable(Throwable e) {
		if (!(e instanceof IOException)) {
			e.printStackTrace();
		} else if (!(e instanceof SocketTimeoutException)) {
			closeSocketAndDisconnect(false);
			e.printStackTrace();
			return 1;
		}
		return 0;
	}

	private void closeSocketAndDisconnect(boolean quitConnectThread) {
		if (mThreadLock.tryLock()) {
			if (connectThread != null) {
				if (!quitConnectThread) {
					connectThread.reconnect();
				} else {
					if (connectThread.isRunning()) {
						connectThread.quit();
						try {
							connectThread.join();
							connectThread = null;
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
			}
			mThreadLock.unlock();
		}
	}

	private void cancel() {
		mState = 0;
		mTimerStatus = 0;
		mLeg = 0;
	}
	
	private void cancelDialog(){
		cancel();
		if (mDriver != null) mDriver.cancelWorkingTime();
		callbackToUI("cancel", null);
	}

	private void scoreZeroAndCancelDialogAndNextPilot(){
		cancel();
		if (mDriver != null) {
			mDriver.cancelWorkingTime();
			Bundle extras = new Bundle();
			extras.putInt("com.marktreble.f3ftimer.pilot_id", mDriver.mPid);
			callbackToUI("score_zero_and_cancel", extras);
		}
	}

	public String formatWindValues(boolean windLegal, float windAngleAbsolute, float windAngleRelative, float windSpeed, long windSpeedIllegalTimer, long windDirectionIllegalTimer, long windDisconnectedTimer) {
		String str = String.format("a: %s°", StringUtils.leftPad(NUMBER_FORMATTER.format(windAngleAbsolute),7)).replace(",",".")
				   + String.format(" r: %s°", StringUtils.leftPad(NUMBER_FORMATTER.format(windAngleRelative),7)).replace(",",".")
				   + String.format(" v: %sm/s", StringUtils.leftPad(NUMBER_FORMATTER.format(windSpeed),7)).replace(",",".");
		
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
				str += " s: illegal (no data)";
			} else if ((windSpeedIllegalTimer >= WIND_ILLEGAL_TIME) && (windDirectionIllegalTimer >= WIND_ILLEGAL_TIME)) {
				str += " s: illegal (bad speed and direction)";
			} else if (windSpeedIllegalTimer >= WIND_ILLEGAL_TIME) {
				str += " s: illegal (bad speed)";
			} else if (windDirectionIllegalTimer >= WIND_ILLEGAL_TIME) {
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
				str += String.format(" s:  legal (%ds - bad speed and direction)", (WIND_ILLEGAL_TIME - windIllegalTimer) / 1000);
			} else if (windSpeedIllegalTimer > 0) {
				str += String.format(" s:  legal (%ds - bad speed)", (WIND_ILLEGAL_TIME - windIllegalTimer) / 1000);
			} else if (windDirectionIllegalTimer > 0) {
				str += String.format(" s:  legal (%ds - bad direction)", (WIND_ILLEGAL_TIME - windIllegalTimer) / 1000);
			} else {
				str += " s:  legal";
			}
        }
        return str;
	}
}
