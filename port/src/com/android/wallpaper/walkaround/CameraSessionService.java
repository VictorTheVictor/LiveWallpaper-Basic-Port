/* Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.wallpaper.walkaround;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import java.util.ArrayList;

public class CameraSessionService extends Service
{
	static boolean active;
	static String status = "Camera session is off.";
	static final ArrayList<Runnable> listeners = new ArrayList<Runnable>();
	private static CameraSessionService instance;
	private static final String CHANNEL = "walkaround_camera";
	private static final int ID = 73;
	private static final String STOP = "com.android.wallpaper.walkaround.STOP";

	static void notifyListeners()
	{
		for (Runnable listener : new ArrayList<Runnable>(listeners)) listener.run();
	}

	static void status(String message)
	{
		status = message;
		if (instance != null && active)
		{
			NotificationManager manager = (NotificationManager) instance.getSystemService(NOTIFICATION_SERVICE);
			manager.notify(ID, instance.notification());
		}
	}

	@Override
	public void onCreate()
	{
		super.onCreate();
		instance = this;
		if (Build.VERSION.SDK_INT >= 26)
		{
			NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			manager.createNotificationChannel(new NotificationChannel(CHANNEL,
				"See Through camera", NotificationManager.IMPORTANCE_LOW));
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		if (intent == null || STOP.equals(intent.getAction()))
		{
			stopSelf();
			return START_NOT_STICKY;
		}
		if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.CAMERA)
			!= PackageManager.PERMISSION_GRANTED)
		{
			status = "Camera permission is required. Open See Through setup.";
			stopSelf();
			return START_NOT_STICKY;
		}
		try
		{
			status = "Ready. Camera opens only while See Through is visible.";
			if (Build.VERSION.SDK_INT >= 30)
			{
				// API 30 public camera type (64), inlined to compile against SDK 29.
				startForeground(ID, notification(), 0x00000040);
			}
			else startForeground(ID, notification());
			active = true;
			notifyListeners();
		}
		catch (RuntimeException error)
		{
			android.util.Log.e("WalkAround", "Cannot start camera foreground session", error);
			status = "Cannot start camera session. Open setup and try again.";
			stopSelf();
		}
		return START_NOT_STICKY;
	}

	private Notification notification()
	{
		int flags = PendingIntent.FLAG_UPDATE_CURRENT;
		if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
		PendingIntent setup = PendingIntent.getActivity(this, 0,
			new Intent(this, WalkAroundSetupActivity.class), flags);
		PendingIntent stop = PendingIntent.getService(this, 1,
			new Intent(this, CameraSessionService.class).setAction(STOP), flags);
		Notification.Builder builder = Build.VERSION.SDK_INT >= 26
			? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
		return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
			.setContentTitle("See Through camera session")
			.setContentText(status).setContentIntent(setup).setOngoing(true)
			.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop camera", stop)
			.build();
	}

	@Override
	public void onDestroy()
	{
		active = false;
		instance = null;
		notifyListeners();
		stopForeground(true);
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent)
	{
		return null;
	}
}
