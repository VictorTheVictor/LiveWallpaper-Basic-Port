/* Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.wallpaper.common;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import java.lang.reflect.Method;

/** Main-thread display pacing; requestRender coalesces requests if GL is busy. */
public final class WallpaperFrames implements Choreographer.FrameCallback
{
	private final Context context;
	private final SurfaceHolder holder;
	private final Runnable request;
	private final Choreographer choreographer;
	private boolean running;
	private boolean warned;

	public WallpaperFrames(Context context, SurfaceHolder holder, final GLSurfaceView view)
	{
		this(context, holder, new Runnable()
		{
			@Override
			public void run()
			{
				view.requestRender();
			}
		});
	}

	public WallpaperFrames(Context context, SurfaceHolder holder, Runnable request)
	{
		this.context = context;
		this.holder = holder;
		this.request = request;
		choreographer = Choreographer.getInstance();
	}

	public void start()
	{
		if (running) return;
		running = true;
		requestDisplayRate(true);
		choreographer.postFrameCallback(this);
	}

	public void surfaceChanged()
	{
		if (running) requestDisplayRate(true);
	}

	public void stop()
	{
		boolean wasRunning = running;
		running = false;
		choreographer.removeFrameCallback(this);
		if (wasRunning) requestDisplayRate(false);
	}

	@Override
	public void doFrame(long frameTimeNanos)
	{
		if (!running) return;
		if (holder.getSurface().isValid()) request.run();
		if (running) choreographer.postFrameCallback(this);
	}

	private void requestDisplayRate(boolean high)
	{
		if (Build.VERSION.SDK_INT < 30) return;
		Surface surface = holder.getSurface();
		if (!surface.isValid()) return;
		try
		{
			float preferred = 0f;
			if (high)
			{
				Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
				Display.Mode current = display.getMode();
				preferred = current.getRefreshRate();
				for (Display.Mode mode : display.getSupportedModes())
				{
					if (mode.getPhysicalWidth() == current.getPhysicalWidth()
						&& mode.getPhysicalHeight() == current.getPhysicalHeight())
					{
						preferred = Math.max(preferred, mode.getRefreshRate());
					}
				}
			}
			// Public API introduced in API 30, reflected only to keep compileSdk 29.
			// Compatibility 0 = FRAME_RATE_COMPATIBILITY_DEFAULT (non-video).
			Method method = Surface.class.getMethod("setFrameRate", float.class, int.class);
			method.invoke(surface, preferred, 0);
			if (high) Log.i("WallpaperFrames", "Requested " + preferred + " Hz; actual rate is chosen by Android.");
		}
		catch (Exception error)
		{
			if (!warned)
			{
				warned = true;
				Log.w("WallpaperFrames", "Display-rate request unavailable; using display-paced rendering", error);
			}
		}
	}
}
