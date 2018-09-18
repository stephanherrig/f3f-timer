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
	private static final String FT_ALIVE = ".";

	private static final String ICN_CONN = "on_rasp";
	private static final String ICN_DISCONN = "off_rasp";
	
	private static DecimalFormat NUMBER_FORMATTER = new DecimalFormat("+0.00;-0.00");
	
	private static TcpIoService instance;
	
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

	private static final ReentrantLock mSocketLock = new ReentrantLock();
	private static final ReentrantLock mReinstateLock = new ReentrantLock();
	
	private Socket mmSocket;
	private InputStream mmInStream;
	private OutputStream mmOutStream;
	
	private ConnectThread connectThread;
	private ListenThread listenThread;
	private SendThread sendThread;
	private AliveThread aliveThread;
	

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
			if (instance != null) {
				Thread.currentThread().setName("ConnectThread" + Thread.currentThread().getId());
				android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
				Looper.prepare();
				
				while (instance != null && instance.connectThread != null && instance.connectThread.isRunning()) {
					instance.mConnected = false;
					instance.driverDisconnected();
					try {
						mReinstateLock.lock();
						// Connect the device through the socket. This will block
						// until it succeeds or throws an exception
						InetSocketAddress rpiSocketAdr = new InetSocketAddress(instance.mF3ftimerServerIp, F3FTIMER_SERVER_PORT);
						Log.i(TAG, "connecting to " + rpiSocketAdr.getHostName() + ":" + rpiSocketAdr.getPort());
						try {
							if (instance.aliveThread != null) {
								Log.d(TAG, "joining aliveThread");
								instance.aliveThread.quit();
								instance.aliveThread.join();
								instance.aliveThread = null;
								Log.d(TAG, "joined aliveThread");
							}

							if (instance.sendThread != null) {
								Log.d(TAG, "joining sendThread");
								instance.sendThread.quit();
								instance.sendThread.join();
								instance.sendThread = null;
								Log.d(TAG, "joined sendThread");
							}
							
							if (instance.listenThread != null) {
								Log.d(TAG, "joining listenThread");
								instance.listenThread.quit();
								instance.listenThread.join();
								instance.listenThread = null;
								Log.d(TAG, "joined listenThread");
							}

							mSocketLock.lock();
							if (instance.mmSocket != null) {
								try {
									if (!instance.mmSocket.isOutputShutdown()) {
										instance.mmSocket.getOutputStream().flush();
										instance.mmSocket.shutdownOutput();
									}
									if (!instance.mmSocket.isInputShutdown()) {
										instance.mmSocket.shutdownInput();
									}
									if (!instance.mmSocket.isClosed()) {
										instance.mmSocket.close();
										instance.mmSocket = null;
									}
								} catch (IOException e1) {
									e1.printStackTrace();
								}
							}
							instance.mmSocket = new Socket();
							instance.mmSocket.setReuseAddress(true);
							instance.mmSocket.setTcpNoDelay(true);
							instance.mmSocket.setSoLinger(false, 0);
							instance.mmSocket.setSoTimeout(1000);
							instance.mmSocket.setSendBufferSize(64);
							instance.mmSocket.setReceiveBufferSize(64);
							//mmSocket.setKeepAlive(true);
							instance.mmSocket.connect(rpiSocketAdr, 5000);
							// Do work to manage the connection (in a separate thread)
							instance.mmInStream = instance.mmSocket.getInputStream();
							instance.mmOutStream = instance.mmSocket.getOutputStream();
							Log.d(TAG, "Socket created");
							mSocketLock.unlock();

							Log.d(TAG, "starting sendThread");
							instance.sendThread = new SendThread();
							instance.sendThread.start();
							
							Log.d(TAG, "starting listenThread");
							instance.listenThread = new ListenThread();
							instance.listenThread.start();
							
							Log.d(TAG, "starting aliveThread");
							instance.aliveThread = new AliveThread();
							instance.aliveThread.start();
							
							reconnecting = false;
							instance.mConnected = true;
							instance.driverConnected();
							
							Log.i(TAG, "connected to " + rpiSocketAdr.getHostName() + ":" + rpiSocketAdr.getPort());
							mReinstateLock.unlock();

							while (instance != null && instance.connectThread != null && instance.connectThread.isRunning()) {
								sleep(1000);
							}
						} catch (IOException connectException) {
							connectException.printStackTrace();
						}
					} catch (InterruptedException e) {
						// nothing to do
					} finally {
						if (mSocketLock.isHeldByCurrentThread()) {
							mSocketLock.unlock();
						}
						if (mReinstateLock.isHeldByCurrentThread()) {
							mReinstateLock.unlock();
						}
					}
					try {
						sleep(1000);
					} catch (InterruptedException e) {
						// nothing to do
					}
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
			if (instance != null) {
				Looper.prepare();
				instance.listen();
			}
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
						if (cmd.length() > 0) {
							Log.i(TAG, "send Cmd \"" + cmd + "\" (" + cmd.length() + ")");
							instance.mmOutStream.write(cmd.getBytes(), 0, cmd.length());
							instance.mmOutStream.flush();
						}
					} catch (Throwable e) {
						if (instance != null) {
							instance.handleThrowable(e);
						}
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
			
			while (instance != null && instance.aliveThread != null && instance.aliveThread.isRunning()) {
				try {
					Thread.sleep(1000);
					if (instance.mLastReceiveTimestamp < System.currentTimeMillis() - 5000) {
						Log.d(TAG, "ListenThread stalled ... reconnect");
						instance.connectThread.reconnect();
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
					if (instance != null) {
						if (!instance.mConnected) {
							instance.closeSocketAndDisconnect(false);
						} else {
							instance.driverConnected();
						}
					}
				}
				
				if (data.equals("model_launched")) {
					instance.mState = 2;
				}
				
				if (data.equals("working_time_started")) {
					instance.mState = 1;
				}
				
				if (data.equals("pref_wind_angle_offset")) {
					instance.mSlopeOrientation = Float.valueOf(intent.getExtras().getString("com.marktreble.f3ftimer.value"));
					Log.d("TcpIoService", "pref_wind_angle_offset=" + instance.mSlopeOrientation);
				}
				
				if (data.equals("pref_input_tcpio_ip")) {
					instance.mF3ftimerServerIp = intent.getExtras().getString("com.marktreble.f3ftimer.value", DEFAULT_F3FTIMER_SERVER_IP);
					Log.d("TcpIoService", "Connecting to new IP: " + instance.mF3ftimerServerIp);
					instance.stopSelf();
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

		if (instance == null) {
			instance = this;
			
			instance.mF3ftimerServerIp = PreferenceManager.getDefaultSharedPreferences(this).getString("pref_input_tcpio_ip", DEFAULT_F3FTIMER_SERVER_IP);
			instance.mSlopeOrientation = Float.parseFloat(PreferenceManager.getDefaultSharedPreferences(this).getString("pref_wind_angle_offset", "0.0f"));
			
			instance.mTimerStatus = 0;
			instance.mState = 0;
			instance.mConnected = false;
			instance.mWindDisconnected = true;
			instance.mWindLegal = false;
			instance.mWindSpeedIlegal = false;
			instance.mWindDirectionIlegal = false;
			instance.mTurnA = false;
			instance.mTurnB = false;
			instance.mLeg = 0;
			instance.mDNFButtonTimestamp = 0;
			instance.mLastReceiveTimestamp = System.currentTimeMillis();
			
			instance.mDriver = new Driver(this);
			
			instance.onBroadcast = new UIBroadcastReceiver();
			registerReceiver(instance.onBroadcast, new IntentFilter("com.marktreble.f3ftimer.onUpdateFromUI"));
		}
		Log.d(TAG, "CREATED");
	}

	@Override
	public void onDestroy() {
		Log.i(TAG, "ONDESTROY!");
		
		if (instance.mDriver != null) {
			instance.mDriver.destroy();
			instance.mDriver = null;
		}
		
		instance.unregisterReceiver(instance.onBroadcast);
		
		instance.closeSocketAndDisconnect(true);
		
		instance = null;
		
		super.onDestroy();
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

		instance.mDriver.start(intent);
		
        if (instance.connectThread != null) {
            Log.d(TAG, "Stop ConnectThread");
            instance.connectThread.interrupt();
            try {
                instance.connectThread.join();
				instance.connectThread = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.d(TAG, "ConnectThread stopped");
        }
        instance.connectThread = new ConnectThread();
        instance.connectThread.start();
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
		if (instance != null && instance.mDriver != null) instance.mDriver.driverConnected(ICN_CONN);
	}

	@Override
	public void driverDisconnected() {
		Log.d(TAG, "Disconnected");
		if (instance != null && instance.mDriver != null) instance.mDriver.driverDisconnected(ICN_DISCONN);
	}
	
	private void callbackToUI(String cmd, Bundle params) {
		Log.d(TAG , "CallBackToUI: " + cmd);
		
		Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
		i.putExtra("com.marktreble.f3ftimer.service_callback", cmd);
		if (params != null) {
			i.putExtras(params);
		}
		instance.sendBroadcast(i);
	}
	
	// Driver Interface implementations
	public void sendLaunch(){
		instance.timeAlreadyReceived = false;
		if (instance.sendThread != null && instance.sendThread.isAlive()) {
			Log.i(TAG, "sendLaunch");
			instance.sendThread.sendCmd(FT_START + " ");
		}
	}

	public void sendAbort(){
		if (instance != null) {
			cancel();
			if (instance.sendThread != null && instance.sendThread.isAlive()) {
				Log.i(TAG, "sendAbort Cancel");
				instance.sendThread.sendCmd(FT_CANCEL + " ");
			}
		}
	}

	public void sendAdditionalBuzzer(){}

	public void sendResendTime(){}

	public void baseA(){}
	public void baseB(){}
	
	public void finished(String time) {
		Log.d(TAG, "UI Flight time " + time.trim());
		if (!instance.timeAlreadyReceived) {
			try {
				/* Wait some time for receiving the time remotely. */
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			/* If the time is not received remotely, then use the time from RaceTimerActivity */
			finishedRemote(time);
		}
	}
	
	private synchronized void finishedRemote(String time){
		/* Commit the time that is received first (either by socket or from RaceTimerActivity) */
		if (!instance.timeAlreadyReceived) {
			instance.timeAlreadyReceived = true;
			Log.d(TAG, "TIME " + time.trim());
			instance.mDriver.mPilot_Time = Float.parseFloat(time.trim().replace(",", "."));
			Log.d(TAG, "TIME " + Float.toString(instance.mDriver.mPilot_Time));
			instance.mDriver.runComplete();
			instance.mState = 0;
			instance.mTimerStatus = 0;
			instance.mLeg = 0;
			instance.mDriver.ready();
		}
	}

	public void sendSpeechText(String lang, String text){
		if (instance.sendThread != null && instance.sendThread.isAlive()) {
			Log.i(TAG, "sendSpeechText \"" + lang.substring(0, 2) + "\" \"" + text + "\"");
			instance.sendThread.sendCmd(FT_SPEECH + lang.substring(0, 2) + text + " ");
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
		while(instance != null && instance.listenThread != null && instance.listenThread.isRunning()) {
			currentTime = System.currentTimeMillis();
			try {
				// Read from the InputStream
				bufferLength = instance.mmInStream.read(buffer);
				if (bufferLength > 0) {
					instance.mLastReceiveTimestamp = currentTime;
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
									if (instance.mState < 2) {
										instance.mDriver.startPressed();
										instance.mState++;
									}
									break;
								case FT_CANCEL:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									instance.cancelDialog();
									break;
								case FT_CANCEL_ZERO:
                                    Log.d(TAG, "received: \"" + strbuf + "\"");
                                    if ((System.currentTimeMillis() - instance.mDNFButtonTimestamp) < 2000) {
                                        Log.d(TAG, "scoreZeroAndCancelDialogAndNextPilot");
										instance.scoreZeroAndCancelDialogAndNextPilot();
									}
									instance.mDNFButtonTimestamp = System.currentTimeMillis();
									break;
								case FT_PENALTY:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									if (instance.mLeg >= 1) {
										instance.mDriver.incPenalty();
									}
									break;
								case FT_TIME:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									String flight_time = str.substring(1, str.length());
									Log.i(TAG, "Flight time: " + flight_time);
									instance.finishedRemote(flight_time);
									break;
								case FT_TURNB:
									// after the model has been started only accept turn A as the first signal
									// then only accept A after B after A ...
									instance.mTurnB = true;
								case FT_TURNA:
									Log.d(TAG, "received: \"" + strbuf + "\"");
									if (!instance.mTurnB) instance.mTurnA = true;
									Log.d(TAG, "mState " + instance.mState + " mTurn" + (instance.mTurnA ? "A" : (instance.mTurnB ? "B" : "")));
									if (instance.mState >= 2) {
										Log.d(TAG, "mTimerStatus " + instance.mTimerStatus);
										switch (instance.mTimerStatus) {
											case 0:
												if (instance.mTurnA) {
													instance.mDriver.offCourse();
													Log.d(TAG, "offCourse");
												}
												break;
											case 1:
												if (instance.mTurnA) {
													instance.mDriver.onCourse();
													Log.d(TAG, "onCourse");
													instance.mLeg = 1;
												}
												break;
											default:
												int turn = instance.mLeg % 2;
												Log.d(TAG, "mLeg " + instance.mLeg + " turn " + turn);
												if ((turn == 1 && instance.mTurnB) || (turn == 0 && instance.mTurnA)) {
													instance.mDriver.legComplete();
													Log.d(TAG, "legComplete");
													instance.mLeg++;
												}
												break;
										}
										if (instance.mTimerStatus <= 1 && instance.mTurnA) {
											instance.mTimerStatus = instance.mTimerStatus + 1;
											Log.d(TAG, "mTimerStatus++ " + instance.mTimerStatus);
										}
									}
									instance.mTurnA = false;
									instance.mTurnB = false;
									break;
								case FT_WIND:
									if (instance.mDriver.mWindMeasurement) {
										Log.d(TAG, "received: \"" + strbuf + "\"");
										try {
											wind_angle_str = str.substring(str.indexOf(",") + 1, str.lastIndexOf(","));
											wind_speed_str = str.substring(str.lastIndexOf(",") + 1, str.length());
											/* decode wind measurement */
											wind_angle_absolute = (Float.parseFloat(wind_angle_str)) % 360;
											wind_angle_relative = wind_angle_absolute - instance.mSlopeOrientation;
											if (wind_angle_absolute > 180 + instance.mSlopeOrientation) {
												wind_angle_relative -= 360;
											}
											wind_speed = Float.parseFloat(wind_speed_str);

											/* evaluate validity of wind values */
                                            if ((wind_speed >= 3) && (wind_speed <= 25)) {
												instance.mWindSpeedLegalTimestamp = currentTime;
												instance.mWindSpeedIlegal = false;
                                            } else {
                                                if (!instance.mWindSpeedIlegal) {
													instance.mWindSpeedLegalTimestamp = currentTime - 1;
													instance.mWindSpeedIlegal = true;
                                                }
                                            }
                                            if ((wind_angle_relative <= 45) && (wind_angle_relative >= -45)) {
												instance.mWindDirectionLegalTimestamp = currentTime;
												instance.mWindDirectionIlegal = false;
                                            } else {
                                                if (!instance.mWindDirectionIlegal) {
													instance.mWindDirectionLegalTimestamp = currentTime - 1;
													instance.mWindDirectionIlegal = true;
                                                }
                                            }
											instance.mWindMeasurementReceivedTimestamp = currentTime;
											instance.mWindDisconnected = false;

											/* compute user readable wind report */
											if (((currentTime - instance.mWindSpeedLegalTimestamp) < WIND_ILLEGAL_TIME) &&
												((currentTime - instance.mWindDirectionLegalTimestamp) < WIND_ILLEGAL_TIME)) {
												instance.mWindLegal = true;
												instance.mDriver.windLegal();
											} else {
												instance.mWindLegal = false;
												instance.mDriver.windIllegal();
											}
										} catch (NumberFormatException | IndexOutOfBoundsException e) {
											e.printStackTrace();
										}
									}
									break;
							} // switch
						}
					} // end for loop
				} else if (bufferLength == -1) {
					if (instance != null && instance.listenThread != null && instance.listenThread.isRunning()) {
						instance.closeSocketAndDisconnect(false);
					}
					break;
				}
			} catch (Throwable e) {
				if (instance != null && instance.listenThread != null && instance.listenThread.isRunning()) {
					if (1 == instance.handleThrowable(e)) {
						break;
					}
				}
			}
			
			
			if (instance != null && instance.listenThread != null && instance.listenThread.isRunning()) {
				try {
					// now update wind data
					if (instance.mDriver != null) {
						if (instance.mDriver.mWindMeasurement) {
							if (!instance.mWindDisconnected) {
								if ((currentTime - instance.mWindMeasurementReceivedTimestamp) > WIND_ILLEGAL_TIME) {
									wind_angle_absolute = 0;
									wind_angle_relative = 0;
									wind_speed = 0;
									instance.mWindLegal = false;
									instance.mDriver.windIllegal();
									instance.mWindDisconnected = true;
								}
								String wind_data = formatWindValues(mWindLegal, wind_angle_absolute, wind_angle_relative, wind_speed,
										instance.mWindMeasurementReceivedTimestamp - instance.mWindSpeedLegalTimestamp,
										instance.mWindMeasurementReceivedTimestamp - instance.mWindDirectionLegalTimestamp,
										currentTime - instance.mWindMeasurementReceivedTimestamp);
								/* send wind report to GUI */
								Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
								i.putExtra("com.marktreble.f3ftimer.value.wind_values", wind_data);
								instance.sendBroadcast(i);
							}
						}
					}
				} catch (Throwable e) {
					if (instance != null && instance.listenThread != null && instance.listenThread.isRunning()) {
						if (1 == instance.handleThrowable(e)) {
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
			if (instance != null) {
				instance.closeSocketAndDisconnect(false);
			}
			e.printStackTrace();
			return 1;
		}
		return 0;
	}

	private void closeSocketAndDisconnect(boolean quitConnectThread) {
		if (mReinstateLock.tryLock()) {
			if (instance != null) {
				if (instance.connectThread != null) {
					if (!quitConnectThread) {
						instance.connectThread.reconnect();
					} else {
						if (instance.connectThread.isRunning()) {
							instance.connectThread.quit();
							try {
								instance.connectThread.join();
								instance.connectThread = null;
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
					}
				}
			}
			mReinstateLock.unlock();
		}
	}

	private void cancel() {
		instance.mState = 0;
		instance.mTimerStatus = 0;
		instance.mLeg = 0;
	}
	
	private void cancelDialog(){
		cancel();
		instance.mDriver.cancelWorkingTime();
		instance.callbackToUI("cancel", null);
	}

	private void scoreZeroAndCancelDialogAndNextPilot(){
		cancel();
		instance.mDriver.cancelWorkingTime();
		Bundle extras = new Bundle();
		extras.putInt("com.marktreble.f3ftimer.pilot_id", instance.mDriver.mPid);
		instance.callbackToUI("score_zero_and_cancel", extras);
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
