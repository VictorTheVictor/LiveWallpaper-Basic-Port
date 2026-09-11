/* Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.wallpaper.walkaround;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class WalkAroundSetupActivity extends Activity
{
	private TextView message;
	private final Handler handler = new Handler();
	private final Runnable refresh = new Runnable()
	{
		@Override
		public void run()
		{
			message.setText((CameraSessionService.active ? "Session enabled.\n" : "Session disabled.\n")
				+ CameraSessionService.status);
			handler.postDelayed(this, 500);
		}
	};

	@Override
	public void onCreate(Bundle saved)
	{
		super.onCreate(saved);
		setTitle("See Through setup");
		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = (int) (20 * getResources().getDisplayMetrics().density);
		layout.setPadding(padding, padding, padding, padding);
		TextView explanation = new TextView(this);
		explanation.setText("Show the rear camera behind your home screen.\n\n"
			+ "1. Enable the camera session and allow camera access.\n"
			+ "2. Open the preview and set the wallpaper.\n\n"
			+ "The camera is released when the wallpaper is hidden. Stop the session here "
			+ "or from its notification. This wallpaper does not record or save images.\n");
		layout.addView(explanation);
		message = new TextView(this);
		layout.addView(message);
		Button enable = new Button(this);
		enable.setText("Enable camera session");
		enable.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View view)
			{
				if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.CAMERA)
					!= PackageManager.PERMISSION_GRANTED)
				{
					requestPermissions(new String[] {android.Manifest.permission.CAMERA}, 1);
				}
				else enable();
			}
		});
		layout.addView(enable);
		Button preview = new Button(this);
		preview.setText("Open wallpaper preview");
		preview.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View view)
			{
				if (!CameraSessionService.active)
				{
					CameraSessionService.status = "Enable the camera session first.";
					return;
				}
				Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
				intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
					new ComponentName(WalkAroundSetupActivity.this, WalkAroundWallpaper.class));
				try
				{
					startActivity(intent);
				}
				catch (RuntimeException error)
				{
					CameraSessionService.status = "Cannot open preview: " + error.getMessage();
				}
			}
		});
		layout.addView(preview);
		Button stop = new Button(this);
		stop.setText("Stop camera session");
		stop.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View view)
			{
				CameraSessionService.status = "Camera session stopped.";
				stopService(new Intent(WalkAroundSetupActivity.this, CameraSessionService.class));
			}
		});
		layout.addView(stop);
		Button permissions = new Button(this);
		permissions.setText("App permissions and notifications");
		permissions.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View view)
			{
				startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
					android.net.Uri.parse("package:" + getPackageName())));
			}
		});
		layout.addView(permissions);
		android.widget.ScrollView scroll = new android.widget.ScrollView(this);
		scroll.addView(layout);
		setContentView(scroll);
	}

	private void enable()
	{
		try
		{
			Intent intent = new Intent(this, CameraSessionService.class);
			if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
			else startService(intent);
		}
		catch (RuntimeException error)
		{
			android.util.Log.e("WalkAround", "Cannot enable camera session", error);
			CameraSessionService.status = "Could not enable session: " + error.getMessage();
		}
	}

	@Override
	public void onRequestPermissionsResult(int request, String[] permissions, int[] results)
	{
		super.onRequestPermissionsResult(request, permissions, results);
		if (request == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) enable();
		else CameraSessionService.status = "Camera permission denied. You can allow it in App permissions.";
	}

	@Override
	public void onResume()
	{
		super.onResume();
		handler.post(refresh);
	}

	@Override
	public void onPause()
	{
		handler.removeCallbacks(refresh);
		super.onPause();
	}
}
