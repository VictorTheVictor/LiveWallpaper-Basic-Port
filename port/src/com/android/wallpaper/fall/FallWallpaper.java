/*
 * Copyright (C) 2009 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * OpenGL ES adaptation of FallRS.java and fall.rs.
 */
package com.android.wallpaper.fall;

import android.opengl.GLSurfaceView;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public class FallWallpaper extends WallpaperService
{
	@Override
	public Engine onCreateEngine()
	{
		return new FallEngine();
	}

	private class FallEngine extends Engine
	{
		private WallpaperView view;
		private FallRenderer renderer;
		private com.android.wallpaper.common.WallpaperFrames frames;

		@Override
		public void onCreate(SurfaceHolder holder)
		{
			super.onCreate(holder);
			renderer = new FallRenderer(getResources(), isPreview());
			view = new WallpaperView();
			view.setEGLContextClientVersion(2);
			view.setEGLConfigChooser(8, 8, 8, 0, 0, 0);
			view.setRenderer(renderer);
			view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
			frames = new com.android.wallpaper.common.WallpaperFrames(FallWallpaper.this, holder, view);
			setTouchEventsEnabled(isPreview());
		}

		@Override
		public void onVisibilityChanged(boolean visible)
		{
			if (frames != null) frames.stop();
			if (view == null) return;
			if (visible)
			{
				view.onResume();
				frames.start();
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
			if (isVisible()) frames.start();
			frames.surfaceChanged();
		}

		@Override
		public void onSurfaceDestroyed(SurfaceHolder holder)
		{
			if (frames != null) frames.stop();
			if (view != null) view.surfaceDestroyed(holder);
			super.onSurfaceDestroyed(holder);
		}

		@Override
		public void onOffsetsChanged(float x, float y, float xs, float ys, int xp, int yp)
		{
			if (renderer != null) renderer.offset = Math.max(0f, Math.min(1f, x));
		}

		private void burst(final int x, final int y)
		{
			if (view == null) return;
			view.queueEvent(new Runnable()
			{
				@Override
				public void run()
				{
					renderer.addTap(x, y);
				}
			});
		}

		@Override
		public android.os.Bundle onCommand(String action, int x, int y, int z,
			android.os.Bundle extras, boolean resultRequested)
		{
			if (!isPreview() && (android.app.WallpaperManager.COMMAND_TAP.equals(action)
				|| android.app.WallpaperManager.COMMAND_SECONDARY_TAP.equals(action)
				|| android.app.WallpaperManager.COMMAND_DROP.equals(action)))
			{
				burst(x, y);
			}
			return super.onCommand(action, x, y, z, extras, resultRequested);
		}

		@Override
		public void onTouchEvent(android.view.MotionEvent event)
		{
			if (isPreview() && event.getActionMasked() == android.view.MotionEvent.ACTION_UP)
			{
				burst((int) event.getX(), (int) event.getY());
			}
			super.onTouchEvent(event);
		}

		@Override
		public void onDestroy()
		{
			if (frames != null) frames.stop();
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
				super(FallWallpaper.this);
				// Engine forwards callbacks explicitly; do not deliver them twice.
				getHolder().removeCallback(this);
			}

			@Override
			public SurfaceHolder getHolder()
			{
				return FallEngine.this.getSurfaceHolder();
			}

			void shutdown()
			{
				super.onDetachedFromWindow();
			}
		}
	}
}
