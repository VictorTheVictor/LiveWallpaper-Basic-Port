/*
 * Copyright (C) 2009 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * Public camera API adaptation of WalkAroundWallpaper.java.
 */
package com.android.wallpaper.walkaround;

import android.hardware.Camera;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.os.Build;
import android.content.pm.PackageManager;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public class WalkAroundWallpaper extends WallpaperService
{
	private final ArrayList<CameraEngine> engines = new ArrayList<CameraEngine>();
	private Camera camera;
	private CameraEngine owner;
	private final Runnable sessionChanged = new Runnable()
	{
		@Override
		public void run()
		{
			for (CameraEngine engine : engines) engine.failed = false;
			updateCamera();
		}
	};

	@Override
	public void onCreate()
	{
		super.onCreate();
		CameraSessionService.listeners.add(sessionChanged);
	}

	@Override
	public Engine onCreateEngine()
	{
		CameraEngine engine = new CameraEngine();
		engines.add(engine);
		return engine;
	}

	private void releaseCamera()
	{
		Camera old = camera;
		camera = null;
		owner = null;
		if (old != null)
		{
			try
			{
				old.setErrorCallback(null);
				old.stopPreview();
			}
			catch (RuntimeException ignored)
			{
			}
			try
			{
				old.release();
			}
			catch (RuntimeException error)
			{
				Log.w("WalkAround", "Camera release failed", error);
			}
		}
	}

	private void updateCamera()
	{
		if (!CameraSessionService.active)
		{
			releaseCamera();
			return;
		}
		CameraEngine selected = null;
		for (CameraEngine engine : engines)
		{
			if (engine.visible && engine.ready && !engine.failed
				&& engine.getSurfaceHolder().getSurface().isValid())
			{
				if (selected == null || engine.isPreview()) selected = engine;
			}
		}
		if (selected == owner && camera != null) return;
		releaseCamera();
		if (selected == null)
		{
			CameraSessionService.status("Paused. Camera is released while the wallpaper is hidden or unavailable.");
			return;
		}
		final CameraEngine target = selected;
		try
		{
			if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.CAMERA)
				!= PackageManager.PERMISSION_GRANTED)
			{
				throw new SecurityException("Camera permission is missing. Open See Through setup.");
			}
			int cameraId = -1;
			Camera.CameraInfo info = new Camera.CameraInfo();
			for (int i = 0; i < Camera.getNumberOfCameras(); i++)
			{
				Camera.getCameraInfo(i, info);
				if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
				{
					cameraId = i;
					break;
				}
			}
			if (cameraId < 0) throw new IllegalStateException("No rear camera is available.");
			camera = Camera.open(cameraId);
			owner = target;
			Camera.Parameters parameters = camera.getParameters();
			int rotation = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
			int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180
				: rotation == Surface.ROTATION_270 ? 270 : 0;
			int orientation = (info.orientation - degrees + 360) % 360;
			boolean swapped = orientation == 90 || orientation == 270;
			float desiredAspect = swapped ? (float) target.height / target.width
				: (float) target.width / target.height;
			List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
			Camera.Size best = null;
			double bestScore = Double.MAX_VALUE;
			if (sizes != null)
			{
				for (Camera.Size size : sizes)
				{
					double score = Math.abs(Math.log((double) size.width / size.height / desiredAspect)) * 10.0
						+ Math.abs(Math.log((double) size.width * size.height / (1280.0 * 720.0))) * 0.25;
					if (score < bestScore)
					{
						best = size;
						bestScore = score;
					}
				}
			}
			if (best != null) parameters.setPreviewSize(best.width, best.height);
			List<String> focus = parameters.getSupportedFocusModes();
			if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
			{
				parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
			}
			List<String> flash = parameters.getSupportedFlashModes();
			if (flash != null && flash.contains(Camera.Parameters.FLASH_MODE_OFF))
			{
				parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
			}
			camera.setParameters(parameters);
			camera.setDisplayOrientation(orientation);
			camera.setErrorCallback(new Camera.ErrorCallback()
			{
				@Override
				public void onError(int error, Camera failedCamera)
				{
					if (failedCamera == camera)
					{
						fail(target, new IllegalStateException("Camera error " + error));
					}
				}
			});
			camera.setPreviewDisplay(target.getSurfaceHolder());
			camera.startPreview();
			CameraSessionService.status("Rear camera preview active. Nothing is recorded or saved.");
		}
		catch (Exception error)
		{
			fail(target, error);
		}
	}

	private void fail(CameraEngine engine, Exception error)
	{
		engine.failed = true;
		releaseCamera();
		Log.e("WalkAround", "Camera preview unavailable", error);
		CameraSessionService.status("Camera unavailable. Check permission, camera access toggle, or other camera apps.");
		android.widget.Toast.makeText(this, "See Through: camera unavailable. Open setup for status.",
			android.widget.Toast.LENGTH_LONG).show();
	}

	@Override
	public void onDestroy()
	{
		CameraSessionService.listeners.remove(sessionChanged);
		releaseCamera();
		engines.clear();
		CameraSessionService.status("Paused. Open See Through or stop the session from its notification.");
		super.onDestroy();
	}

	private class CameraEngine extends Engine
	{
		boolean visible, ready, failed;
		int width, height;

		@Override
		public void onVisibilityChanged(boolean value)
		{
			visible = value;
			if (value) failed = false;
			updateCamera();
		}

		@Override
		public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h)
		{
			super.onSurfaceChanged(holder, format, w, h);
			width = w;
			height = h;
			ready = w > 0 && h > 0;
			failed = false;
			if (owner == this) releaseCamera();
			updateCamera();
		}

		@Override
		public void onSurfaceDestroyed(SurfaceHolder holder)
		{
			ready = false;
			updateCamera();
			super.onSurfaceDestroyed(holder);
		}

		@Override
		public void onDestroy()
		{
			visible = false;
			ready = false;
			engines.remove(this);
			updateCamera();
			super.onDestroy();
		}
	}
}
