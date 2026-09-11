/*
 * Copyright (C) 2009 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * OpenGL ES adaptation of GalaxyRS.java and galaxy.rs.
 */
package com.android.wallpaper.galaxy;

import android.opengl.GLSurfaceView;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public class GalaxyWallpaper extends WallpaperService
{
	@Override
	public Engine onCreateEngine()
	{
		return new GalaxyEngine();
	}

	private class GalaxyEngine extends Engine
	{
		private WallpaperView view;
		private GalaxyRenderer renderer;

		@Override
		public void onCreate(SurfaceHolder holder)
		{
			super.onCreate(holder);
			renderer = new GalaxyRenderer(getResources(), isPreview());
			view = new WallpaperView();
			view.setEGLContextClientVersion(2);
			view.setEGLConfigChooser(8, 8, 8, 0, 0, 0);
			view.setRenderer(renderer);
			view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
		}

		private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
		private final Runnable frame = new Runnable()
		{
			@Override
			public void run()
			{
				if (view != null && isVisible())
				{
					view.requestRender();
					handler.postDelayed(this, 45);
				}
			}
		};

		@Override
		public void onVisibilityChanged(boolean visible)
		{
			handler.removeCallbacks(frame);
			if (view == null) return;
			if (visible)
			{
				view.onResume();
				handler.post(frame);
			}
			else
			{
				view.onPause();
			}
		}

		@Override
		public void onSurfaceCreated(SurfaceHolder holder)
		{
			super.onSurfaceCreated(holder);
			view.surfaceCreated(holder);
		}

		@Override
		public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height)
		{
			super.onSurfaceChanged(holder, format, width, height);
			view.surfaceChanged(holder, format, width, height);
		}

		@Override
		public void onSurfaceDestroyed(SurfaceHolder holder)
		{
			if (view != null) view.surfaceDestroyed(holder);
			super.onSurfaceDestroyed(holder);
		}

		@Override
		public void onOffsetsChanged(float x, float y, float xs, float ys, int xp, int yp)
		{
			if (renderer != null) renderer.offset = Math.max(0f, Math.min(1f, x));
		}

		@Override
		public void onDestroy()
		{
			handler.removeCallbacks(frame);
			if (view != null)
			{
				view.shutdown();
				view = null;
			}
			super.onDestroy();
		}

		private class WallpaperView extends GLSurfaceView
		{
			WallpaperView()
			{
				super(GalaxyWallpaper.this);
				// Engine forwards callbacks explicitly; do not deliver them twice.
				getHolder().removeCallback(this);
			}

			@Override
			public SurfaceHolder getHolder()
			{
				return GalaxyEngine.this.getSurfaceHolder();
			}

			void shutdown()
			{
				super.onDetachedFromWindow();
			}
		}
	}
}
