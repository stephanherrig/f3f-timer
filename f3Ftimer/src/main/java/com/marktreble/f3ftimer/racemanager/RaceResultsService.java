package com.marktreble.f3ftimer.racemanager;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.marktreble.f3ftimer.R;
import com.marktreble.f3ftimer.data.pilot.Pilot;
import com.marktreble.f3ftimer.data.race.Race;
import com.marktreble.f3ftimer.data.race.RaceData;
import com.marktreble.f3ftimer.data.racepilot.RacePilotData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.SimpleTimeZone;

public class RaceResultsService extends Service {
	
	static boolean DEBUG = true;
	static SimpleDateFormat HTTP_HEADER_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);

	ServerSocket mServerSocket;
	Listener mListener;
	Integer mRid;
	byte[] mOut;
	byte[] mOutLiveData;
	byte[] mOutRaceData;
	long mLastRequestTimeLiveData = 0;
	long mLastRequestTimeRaceData = 0;
	
	private String mResultsServerStyle;

	private int state = 0;
	private int currentPilotId = 0;
	private float workingTime = 0;
	private float climbOutTime = 0;
	private float flightTime = 0;
	private float estimatedTime = 0;
	private int turnNumber = 0;
	private String turnNumbersStr = "";
	private String legTimesStr = "";
	private String fastestLegTimesStr = "";
	private float fastestFlightTime = 0;
	private String fastestFlightPilot;
	private String deltaTimesStr = "";
	private int penalty = 0;
	private float windAngleAbsolute = 0;
	private float windAngleRelative = 0;
	private float windSpeed = 0;
	private String windStatus = "";
	private String raceName = "";
	private int raceRound = 0;
	private int raceStatus = 0;
	private String currentPilot = "";
	private String racetimesSerialized = "";
	private String pilotsSerialized = "";
	RacePilotData datasource2;
	
	@Override
    public void onCreate() {
        HTTP_HEADER_DATE_FORMAT.setTimeZone(new SimpleTimeZone(0, "GMT"));
        this.registerReceiver(onBroadcast, new IntentFilter("com.marktreble.f3ftimer.onLiveUpdate"));
        this.registerReceiver(onBroadcast1, new IntentFilter("com.marktreble.f3ftimer.onUpdate"));
		this.registerReceiver(onBroadcast2, new IntentFilter("com.marktreble.f3ftimer.onUpdateFromUI"));
		SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		mResultsServerStyle = sharedPref.getString("pref_results_server_style", getResources().getStringArray(R.array.options_results_server_style)[0]);
		datasource2 = new RacePilotData(RaceResultsService.this);
	}
		
	@Override
	public void onDestroy() {
		if (mListener != null)
			mListener.cancel(false);
		
		if (mServerSocket != null){
			try {
				mServerSocket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		try {
			this.unregisterReceiver(onBroadcast);
			this.unregisterReceiver(onBroadcast1);
			this.unregisterReceiver(onBroadcast2);
		} catch (IllegalArgumentException e){
			e.printStackTrace();
		}
    }

    public static boolean stop(RaceActivity context){
        if (context.isServiceRunning("com.marktreble.f3ftimer.racemanager.RaceResultsService")) {
            Intent serviceIntent = new Intent(context, RaceResultsService.class);
            context.stopService(serviceIntent);
            return true;
        }
        return false;
    }
    
    public int onStartCommand(Intent intent, int flags, int startId){
		if (intent == null) return START_REDELIVER_INTENT;
    	if (intent.hasExtra("com.marktreble.f3ftimer.race_id")){
			Bundle extras = intent.getExtras();
			mRid = extras.getInt("com.marktreble.f3ftimer.race_id");
   
			datasource2.open();
            int maxRound = datasource2.getMaxRound(mRid);
            racetimesSerialized = datasource2.getTimesSerializedExt(mRid, maxRound);
            pilotsSerialized = datasource2.getPilotsSerialized(mRid);
            datasource2.close();
			
            RaceData datasource = new RaceData(RaceResultsService.this);
			datasource.open();
			Race race = datasource.getRace(mRid);
			raceName = race.name;
			raceStatus = race.status;
			raceRound = race.round;
			datasource.close();
    	} else {
    		return START_REDELIVER_INTENT;
    	}
    	
    	mServerSocket = null;
    	mListener = null;
    	try {
    	    mServerSocket = new ServerSocket(8080, 3);
    	    mServerSocket.setReuseAddress(true);
    	} 
    	catch (IOException e) {

    	    System.out.println("Could not listen on port: 8080 " + e.getMessage() + "::" + e.getCause());
    	    return START_REDELIVER_INTENT;
    	}
    	
    	if (mServerSocket!=null){
   			mListener = new Listener();
   			mListener.execute(mServerSocket);
    	}
       	return (START_STICKY);    	
    }
       
    private class Listener extends AsyncTask<ServerSocket, Integer, Long> {

    	ServerSocket ss;
    	
		@Override
		protected Long doInBackground(ServerSocket... serverSocket) {
			Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
			ss = serverSocket[0];
    		Socket clientSocket = null;
	    	try {
	    	    clientSocket = ss.accept();
	    	    clientSocket.setTcpNoDelay(true);
	    	    clientSocket.setSoLinger(false, 0);
	    	    clientSocket.setSoTimeout(1000);
	    	} 
	    	catch (IOException e) {
	    	    return null;
	    	}
	    	if (clientSocket!=null){
				try {
					InputStream input = clientSocket.getInputStream();
					OutputStream output = clientSocket.getOutputStream();
					
					byte[] buffer = new byte[1024];
					input.read(buffer);
					String[] request_headers = parseHeaders(buffer);

					if (request_headers == null) {
						output.write(get500Page("No Request Header found"));
						output.close();
					} else {
						String[] req = request_headers[0].split(" ");
					
						if (req.length != 3){
							Log.e("F3fHTTPServerRequest", "Malformed Request");
							output.write(get500Page("Malformed Request \"" + request_headers[0] + "\""));
							output.close();
						} else {
							String request_type = req[0];
							String request_path = req[1];
							
							if (request_path.equals("/")) request_path = "/index.html"; // Default page


							String ext = "";
							String[] parts = request_path.split("\\?");
							String path = parts[0];
							String query = "";
							if (parts.length>1) query = parts[1];
							
				            int i = path.lastIndexOf('.');
				            if (i>0) ext = path.substring(i+1);
							
							if (request_path.contains("getRaceLiveData.jsp")) {
								if (0 == mLastRequestTimeLiveData || (System.currentTimeMillis() - mLastRequestTimeLiveData) > 500) {
									mLastRequestTimeLiveData = System.currentTimeMillis();
									mOutLiveData = getLivePage(request_type, path, ext, query);
									mOut = mOutLiveData;
								} else {
									mOut = mOutLiveData;
								}
							} else if (request_path.contains("getRaceData.jsp")) {
								if (0 == mLastRequestTimeRaceData || (System.currentTimeMillis() - mLastRequestTimeRaceData) > 500) {
									mLastRequestTimeRaceData = System.currentTimeMillis();
									mOutRaceData = getDynamicPage(request_type, path, ext, query);
									mOut = mOutRaceData;
								} else {
									mOut = mOutRaceData;
								}
							} else {
								mOut = getStaticPage(path, ext);
							}

				            output.write(mOut);
				            output.close();
						}
					}
					
					input.close();
					clientSocket.close();
				} catch (IOException e) {
					if (e instanceof SocketTimeoutException) {
					} else {
						e.printStackTrace();
					}
				}
				return (long) 1;
	    	}
			return null;
		}
		
		private String[] parseHeaders(byte[] buffer){
			String s = null;
			try {
				s = new String(buffer,"ASCII");
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
			if (s !=null){
				String[] lines = s.split("\n");
				String line;
				int l = 0;
				String[] headers = new String[lines.length];
				do {
					line = lines[l].trim();
					Log.d("F3fHTTPServerRequest", line);
					headers[l++] = line;
				} while (line.length()>0 && l<lines.length);
			
				return headers;
			}
			return null;
		}
		
		private byte[] getStaticPage(String path, String ext){
			byte[] response = null;
			
			String resourcefile = "public_html_"+mResultsServerStyle+path;
			Log.i("HTTP REQUEST", resourcefile);
			
			AssetManager am = getAssets();

            String mime_type = this.getMimeTypeForExtension(ext);

            try {       
                InputStream in_s = am.open(resourcefile);
                byte[] contentBytes;

                contentBytes = new byte[in_s.available()];
               	in_s.read(contentBytes);
                String len = Integer.toString(contentBytes.length);
                
               	
                String header;
    	        header = "HTTP/1.1 200 OK\n";
    	        header += "Content-Type: "+mime_type+"\n";
    	        header += "Content-Length: "+len+"\n";
				String now = HTTP_HEADER_DATE_FORMAT.format(new Date(System.currentTimeMillis())).replaceFirst("\\x2B\\d\\d:\\d\\d","");
				header += "Date: "+now+"\n";
				//String expiredate = HTTP_HEADER_DATE_FORMAT.format(new Date(System.currentTimeMillis()+30000)).replaceFirst("\\x2B\\d\\d:\\d\\d","");
				//header += "Expires: "+expiredate+"\n";
				header += "Last-Modified: "+now+"\n";
				header += "Cache-Control: public, max-age=60\n";
				//header += "Cache-Control: no-cache\n";
				//header += "Cache-Control: no-store\n";
				header += "\r\n";

    	        byte[] headerBytes = header.getBytes();
    	        response = new byte[headerBytes.length + contentBytes.length];

    	        System.arraycopy(headerBytes,0,response,0         ,headerBytes.length);
    	        System.arraycopy(contentBytes,0,response,headerBytes.length,contentBytes.length);
                                
            } catch (Exception e) {
            	return this.get404Page();
            }			
			return response;
		}
		
		private byte[] getDynamicPage(String type, String path, String ext, String query){		

			byte[] response = null;
            Method method;
            JSPPages jsp = new JSPPages();
            
            String script = path.replaceAll("/", "_").substring(0, path.length()-(ext.length()+1)).toLowerCase();
                
            try {
            	  Log.i("TRYING METHOD", script);
              	  method = jsp.getClass().getMethod(script, String.class);
              	  
                  try {
                	  response = (byte[])method.invoke(jsp, query);
                  } catch (IllegalArgumentException e) {
                   	  return this.get500Page();
                  } catch (IllegalAccessException e) {
                   	  return this.get500Page();
                  } catch (InvocationTargetException e) {		
                   	  return this.get500Page();
                  }

            } catch (SecurityException e) {
            		Log.i("ERROR", e.getMessage());
             	  return this.get500Page();
            } catch (NoSuchMethodException e) {
               	  return this.get404Page();
            }                
            
			return response;
		}
        
        private byte[] getLivePage(String type, String path, String ext, String query) {

            byte[] response = null;
            Method method;
            JSPPagesLive jsp = new JSPPagesLive();
            
            String script = path.replaceAll("/", "_").substring(0, path.length()-(ext.length()+1)).toLowerCase();

            try {
//                Log.i("TRYING METHOD", script);
                  method = jsp.getClass().getMethod(script, String.class);

                  try {
                      response = (byte[])method.invoke(jsp, query);
                  } catch (IllegalArgumentException e) {
                      return this.get500Page();
                  } catch (IllegalAccessException e) {
                      return this.get500Page();
                  } catch (InvocationTargetException e) {
                      return this.get500Page();
                  }

            } catch (SecurityException e) {
                    Log.i("ERROR", e.getMessage());
                  return this.get500Page();
            } catch (NoSuchMethodException e) {
                  return this.get404Page();
            }                
            
            return response;
        }

		private byte[] get404Page(){
			
			String html = "";
	  		
	  		html = "<!DOCTYPE html>\n";
	  		html+= "<head>\n";
	  		html+= "<title>HTTP/1.1 404 Not Found</title>";
	  		html+= "</head>\n";
	  		html+= "<body>\n";
	  		html+= "<h1>HTTP/1.1 404 Not Found</h1>";
	  		html+= "</body>\n";
	  		html+= "</html>\n";
	  		
	  		String header;
            header = "HTTP/1.1 404 Not Found\n";
            header+= "Content-Type: text/html; charset=utf-8\n";
            header+= "Content-Length: "+html.length()+"\n";
            header+= "\r\n";

            return (header + html).getBytes();
		}
		
		private byte[] get500Page(){
			
			String html = "";
	  		
	  		html = "<!DOCTYPE html>\n";
	  		html+= "<head>\n";
	  		html+= "<title>HTTP/1.1 500 Internal Server Error</title>";
	  		html+= "</head>\n";
	  		html+= "<body>\n";
	  		html+= "<h1>HTTP/1.1 500 Internal Server Error</h1>";
	  		html+= "</body>\n";
	  		html+= "</html>\n";
	  		
	  		String header;
            header = "HTTP/1.1 500 Internal Server Error\n";
            header+= "Content-Type: text/html; charset=utf-8\n";
            header+= "Content-Length: "+html.length()+"\n";
            header+= "\r\n";

            return (header + html).getBytes();
		}
	
		private byte[] get500Page(String errorMsg){
		
			String html = "";
		
			html = "<!DOCTYPE html>\n";
			html+= "<head>\n";
			html+= "<title>HTTP/1.1 500 Internal Server Error</title>";
			html+= "</head>\n";
			html+= "<body>\n";
			html+= "<h1>" + errorMsg + "</h1>";
			html+= "</body>\n";
			html+= "</html>\n";
		
			String header;
			header = "HTTP/1.1 500 Internal Server Error\n";
			header+= "Content-Type: text/html; charset=utf-8\n";
			header+= "Content-Length: "+html.length()+"\n";
			header+= "\r\n";
		
			return (header + html).getBytes();
		}

		private String getMimeTypeForExtension(String ext){
			String type = "text/plain";
			
			if (ext.equals("")){
				type = "text/html; charset=utf-8";
			}
			
			if (ext.equals("html")){
				type = "text/html; charset=utf-8";
			}
			
			if (ext.equals("css")){
				type = "text/css; charset=utf-8";
			}

			if (ext.equals("js")){
				type = "text/javascript";
			}

			if (ext.equals("jsp")){
				type = "application/javascript";
			}

			if (ext.equals("png")){
				type = "image/png";
			}

			if (ext.equals("jpg")){
				type = "image/jpeg";
			}
			
			if (ext.equals("gif")){
				type = "image/gif";
			}

			return type;
		}
		
		@Override
		protected void onPostExecute(Long result){
			super.onPostExecute(result);
            if (null != result && result == 1){
				mListener = new Listener();
				mListener.execute(ss);
			}
		}
    }
    
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

    private BroadcastReceiver onBroadcast1 = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra("com.marktreble.f3ftimer.value.wind_values")) {
            	String windValues = intent.getStringExtra("com.marktreble.f3ftimer.value.wind_values");
				String[] windValuesSplit = windValues.split(".: ");
				try {
					windAngleAbsolute = Float.parseFloat(windValuesSplit[1].substring(0, windValuesSplit[1].indexOf("°")));
					windAngleRelative = Float.parseFloat(windValuesSplit[2].substring(0, windValuesSplit[2].indexOf("°")));
					windSpeed = Float.parseFloat(windValuesSplit[3].substring(0, windValuesSplit[3].indexOf("m/s")));
					windStatus = windValuesSplit[4];
				} catch (NullPointerException | ArrayIndexOutOfBoundsException | NumberFormatException | StringIndexOutOfBoundsException e) {
					e.printStackTrace();
				}
            }
        }
    };

	private BroadcastReceiver onBroadcast2 = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.hasExtra("com.marktreble.f3ftimer.ui_callback")){
				Bundle extras = intent.getExtras();
				String data = extras.getString("com.marktreble.f3ftimer.ui_callback");
				Log.d("UI->Service", data);
				
				if (data == null) return;
				
				if (data.equals("pref_results_server_style")) {
					mResultsServerStyle = extras.getString("com.marktreble.f3ftimer.value");
				}
			}
		}
	};

    // Binding for UI->Service Communication
    private BroadcastReceiver onBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
			if (intent.hasExtra("com.marktreble.f3ftimer.value.raceName")) {
				raceName = intent.getExtras().getString("com.marktreble.f3ftimer.value.raceName");
			}
			if (intent.hasExtra("com.marktreble.f3ftimer.value.raceStatus")) {
				raceStatus = intent.getExtras().getInt("com.marktreble.f3ftimer.value.raceStatus");
			}
			if (intent.hasExtra("com.marktreble.f3ftimer.value.raceRound")) {
				raceRound = intent.getExtras().getInt("com.marktreble.f3ftimer.value.raceRound");
			}
			if (intent.hasExtra("com.marktreble.f3ftimer.value.currentPilot")) {
				currentPilot = intent.getExtras().getString("com.marktreble.f3ftimer.value.currentPilot");
			}
            if (intent.hasExtra("com.marktreble.f3ftimer.value.flightTime")) {
                flightTime = intent.getExtras().getFloat("com.marktreble.f3ftimer.value.flightTime");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.estimatedTime")) {
                estimatedTime = intent.getExtras().getFloat("com.marktreble.f3ftimer.value.estimatedTime");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.workingTime")) {
                workingTime = intent.getExtras().getFloat("com.marktreble.f3ftimer.value.workingTime");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.climbOutTime")) {
                climbOutTime = intent.getExtras().getFloat("com.marktreble.f3ftimer.value.climbOutTime");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.turnNumber")) {
                turnNumber = intent.getExtras().getInt("com.marktreble.f3ftimer.value.turnNumber");
                if (turnNumbersStr.length() > 0) turnNumbersStr += "#";
                turnNumbersStr += String.valueOf(turnNumber);
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.legTime")) {
				float legTime = intent.getExtras().getFloat("com.marktreble.f3ftimer.value.legTime");
                if (legTimesStr.length() > 0) legTimesStr += "#";
                legTimesStr += String.format("%.2f", legTime).replace(",", ".");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.fastestLegTime")) {
				float fastestLegTime = intent.getExtras().getFloat("com.marktreble.f3ftimer.value.fastestLegTime");
                if (fastestLegTimesStr.length() > 0) fastestLegTimesStr += "#";
                fastestLegTimesStr += String.format("%.2f", fastestLegTime).replace(",", ".");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.deltaTime")) {
				float deltaTime = intent.getExtras().getFloat("com.marktreble.f3ftimer.value.deltaTime");
                if (deltaTimesStr.length() > 0) deltaTimesStr += "#";
                if (deltaTime > 0) deltaTimesStr += "+";
                deltaTimesStr += String.format("%.2f", deltaTime).replace(",", ".");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.currentPilotId")) {
                currentPilotId = intent.getExtras().getInt("com.marktreble.f3ftimer.value.currentPilotId");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.fastestFlightTime")) {
                fastestFlightTime = intent.getExtras().getFloat("com.marktreble.f3ftimer.value.fastestFlightTime");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.fastestFlightPilot")) {
                fastestFlightPilot = intent.getExtras().getString("com.marktreble.f3ftimer.value.fastestFlightPilot");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.fastestLegPilot")) {
                fastestFlightPilot = intent.getExtras().getString("com.marktreble.f3ftimer.value.fastestLegPilot");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.penalty")) {
                penalty = intent.getExtras().getInt("com.marktreble.f3ftimer.value.penalty");
            }
            if (intent.hasExtra("com.marktreble.f3ftimer.value.state")) {
                state = intent.getExtras().getInt("com.marktreble.f3ftimer.value.state");
                if (state == 1) {
                    turnNumbersStr = "";
                    deltaTimesStr = "";
                    legTimesStr = "";
                    fastestLegTimesStr = "";
                    fastestFlightTime = 0;
                    fastestFlightPilot ="";
                    penalty = 0;
                }
				if (state == 6) {
					turnNumber++;
				}
				if ((state == 0) || (state == 1)) {
					datasource2.open();
					int maxRound = datasource2.getMaxRound(mRid);
					racetimesSerialized = datasource2.getTimesSerializedExt(mRid, maxRound);
					pilotsSerialized = datasource2.getPilotsSerialized(mRid);
					datasource2.close();
				}
            }
        }
    };

    public class JSPPagesLive {
        public byte[] _api_getracelivedata(String query){
            String data = "[{";
            data += this.addParam("time", String.valueOf(System.currentTimeMillis()/1000L)) + ",";
            data += this.addParam("race_name", raceName) + ",";
            data += this.addParam("race_status", String.valueOf(raceStatus)) + ",";
            data += this.addParam("current_round", String.valueOf(raceRound)) + ",";
            data += this.addParam("state", String.valueOf(state)) + ",";
            data += this.addParam("current_pilot", currentPilot) + ",";
            data += this.addParam("current_penalty", String.format("%d", penalty).replace(",", ".")) + ",";
            data += this.addParam("current_working_time", String.format("%.2f", workingTime).replace(",", ".")) + ",";
            data += this.addParam("current_climb_out_time", String.format("%.2f", climbOutTime).replace(",", ".")) + ",";
            if (turnNumber == 0 && state != 8) {
                data += this.addParam("current_flight_time", String.format("%.2f", flightTime).replace(",", ".")) + ",";
            } else {
                data += this.addParam("current_flight_time", String.format("%.2f", flightTime).replace(",", ".")) + ",";
                data += this.addParam("current_estimated_flight_time", String.format("%.2f", estimatedTime).replace(",", ".")) + ",";
                data += this.addParam("current_turn_numbers", turnNumbersStr) + ",";
                data += this.addParam("current_split_times", legTimesStr) + ",";
                data += this.addParam("fastest_times", fastestLegTimesStr) + ",";
                data += this.addParam("delta_times", deltaTimesStr) + ",";
                data += this.addParam("fastest_time", String.format("%.2f", fastestFlightTime).replace(",", ".")) + ",";
                data += this.addParam("fastest_time_pilot", fastestFlightPilot) + ",";
            }
            data += this.addParam("current_wind_angle_absolute", String.format("%.2f", windAngleAbsolute).replace(",", ".")) + ",";
            data += this.addParam("current_wind_angle_relative", String.format("%.2f", windAngleRelative).replace(",", ".")) + ",";
            data += this.addParam("current_wind_speed", String.format("%.2f", windSpeed).replace(",", ".")) + ",";
            data += this.addParam("current_wind_status", String.format("%s", windStatus).replace(",", "."));
            data += "}]           ";
            
            String header;
            header = "HTTP/1.1 200 OK\n";
            header += "Content-Type: application/json; charset=utf-8\n";
            header += "Content-Length: "+data.length()+"\n";
            String now = HTTP_HEADER_DATE_FORMAT.format(new Date(System.currentTimeMillis())).replaceFirst("\\x2B\\d\\d:\\d\\d","");
            header += "Date: "+now+"\n";
            //String expiredate = HTTP_HEADER_DATE_FORMAT.format(new Date(System.currentTimeMillis()+1000)).replaceFirst("\\x2B\\d\\d:\\d\\d","");
            //header += "Expires: "+expiredate+"\n";
			header += "Last-Modified: "+now+"\n";
			header += "Cache-Control: public, max-age=1\n";
			//header += "Cache-Control: no-cache\n";
			//header += "Cache-Control: no-store\n";
            header += "\r\n";

            return (header + data).getBytes();
        }

        private String addParam(String name, String value){
            return addParam(name, value, true);
        }

        private String addParam(String name, String value, boolean quotes){
            if (!quotes) return "\""+name+"\":"+value;
            return "\""+name+"\":\""+value+"\"";
        }
    }

	public class JSPPages {
		public byte[] _api_getracedata(String query){

			long unixTime = System.currentTimeMillis() / 1000L;
			
			String data = "[{";
			
			if (mResultsServerStyle.equals(getResources().getStringArray(R.array.options_results_server_style)[0])) {
				RacePilotData datasource2 = new RacePilotData(RaceResultsService.this);
				datasource2.open();
				ArrayList<Pilot> allPilots = datasource2.getAllPilotsForRace(mRid, 0);
				
				ArrayList<String> p_names = new ArrayList<>();
				for (Pilot p : allPilots){
					p_names.add(String.format("\"%s %s\"", p.firstname, p.lastname));
				}
				String pilots_array = p_names.toString();
				
				ArrayList<ArrayList<String>> p_times = new ArrayList<>();
				ArrayList<Integer> groups = new ArrayList<>();
				
				RaceData datasource = new RaceData(RaceResultsService.this);
				datasource.open();
				
				for (int rnd=0; rnd<raceRound; rnd++){
					RaceData.Group group = datasource.getGroups(mRid, rnd+1);
					groups.add(group.num_groups);
					
					ArrayList<String> round = new ArrayList<>();
					for (Pilot p : allPilots){
						float time = datasource2.getPilotTimeInRound(mRid, p.id, rnd+1);
						//Log.i("RRS", p.toString());
						//Log.i("RRS", Float.toString(time));
						if (time>0){
							round.add(String.format("\"%.2f\"",time).replace(",","."));
						} else {
							if (rnd == raceRound-1){ // is the round in progress
								if (!p.flown){
									// Not yet flown ("")
									round.add("\"\"");
								} else {
									// Has flown so time was a zero
									round.add("\"0\"");
								}
							} else {
								round.add("\"0\"");
							}
						}
					}
					p_times.add(round);
				}
				String times_array = p_times.toString();
				
				datasource.close();
				
				ArrayList<ArrayList<String>> p_penalties = new ArrayList<>();
				for (int rnd=0; rnd<raceRound; rnd++){
					ArrayList<Pilot> pilots_in_round = datasource2.getAllPilotsForRace(mRid, rnd+1);
					ArrayList<String> round = new ArrayList<>();
					for (int i=0; i<p_names.size(); i++){
						
						round.add(String.format("\"%d\"", pilots_in_round.get(i).penalty *100));
					}
					p_penalties.add(round);
				}
				String penalties_array = p_penalties.toString();
				String groups_array = groups.toString();
				
				datasource2.close();

				data += this.addParam("time", String.valueOf(unixTime)) + ",";
				data += this.addParam("race_name", raceName) + ",";
				data += this.addParam("race_status", String.valueOf(raceStatus)) + ",";
				data += this.addParam("current_round", String.valueOf(raceRound)) + ",";
				data += this.addParam("ftd", "{}", false) + ",";
				data += this.addParam("round_winners", "[]", false) + ",";
				data += this.addParam("pilots", pilots_array, false) + ",";
				data += this.addParam("times", times_array, false) + ",";
				data += this.addParam("penalties", penalties_array, false) + ",";
				data += this.addParam("groups", groups_array, false);
				
			} else if (mResultsServerStyle.equals(getResources().getStringArray(R.array.options_results_server_style)[1])) {

				data += this.addParam("time", String.valueOf(unixTime)) + ",";
				data += this.addParam("race_name", raceName) + ",";
				data += this.addParam("race_status", String.valueOf(raceStatus)) + ",";
				data += this.addParam("current_round", String.valueOf(raceRound)) + ",";
				data += this.addParam("ftd", "{}", false) + ",";
				data += this.addParam("round_winners", "[]", false) + ",";
				data += this.addParam("pilots", pilotsSerialized, false) + ",";
				data += this.addParam("racetimes", racetimesSerialized, false);
			}
			data += "}]           ";

            String header;
            header = "HTTP/1.1 200 OK\n";
            header += "Content-Type: application/json; charset=utf-8\n";
            header += "Content-Length: "+data.length()+"\n";
            String now = HTTP_HEADER_DATE_FORMAT.format(new Date(System.currentTimeMillis())).replaceFirst("\\x2B\\d\\d:\\d\\d","");
            header += "Date: "+now+"\n";
            //String expiredate = HTTP_HEADER_DATE_FORMAT.format(new Date(System.currentTimeMillis()+30000)).replaceFirst("\\x2B\\d\\d:\\d\\d","");
            //header += "Expires: "+expiredate+"\n";
			header += "Last-Modified: "+now+"\n";
			header += "Cache-Control: public, max-age=30\n";
			//header += "Cache-Control: no-cache\n";
			//header += "Cache-Control: no-store\n";
            header += "\r\n";

            return (header + data).getBytes();
		}
		
		private String addParam(String name, String value){
			return addParam(name, value, true);
		}
		
		private String addParam(String name, String value, boolean quotes){
			if (!quotes) return "\""+name+"\":"+value;
			return "\""+name+"\":\""+value+"\"";
		}
	}
}

