/*
 * Copyright (C) 2010 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * Canvas adaptation of GrassRS.java and grass.rs.
 */
package com.android.wallpaper.grass;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import com.android.wallpaper.R;
import java.util.Calendar;
import java.util.Random;

public class GrassWallpaper extends WallpaperService
{
	@Override
	public Engine onCreateEngine()
	{
		return new GrassEngine();
	}

	private class GrassEngine extends Engine
	{
		private final Handler handler = new Handler(Looper.getMainLooper());
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path path = new Path();
		private final RectF rect = new RectF();
		private final Random random = new Random();
		private final Noise noise = new Noise(random);
		private final Blade[] blades = new Blade[200];
		private final Bitmap[] skies = new Bitmap[4];
		private final Calendar calendar = Calendar.getInstance();
		private final float[] hsv = new float[3];
		private final float[] xs = new float[17], ys = new float[17];
		private boolean visible, surfaceReady;
		private float width, offset = 0.5f;
		private static final float HEIGHT = 800f;
		private final Runnable frame = new Runnable()
		{
			@Override
			public void run()
			{
				drawFrame();
			}
		};

		@Override
		public void onCreate(SurfaceHolder holder)
		{
			super.onCreate(holder);
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inScaled = false;
			int[] resources = {R.drawable.night, R.drawable.sunrise, R.drawable.sky, R.drawable.sunset};
			for (int i = 0; i < skies.length; i++)
			{
				skies[i] = BitmapFactory.decodeResource(getResources(), resources[i], options);
			}
		}

		@Override
		public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h)
		{
			super.onSurfaceChanged(holder, format, w, h);
			width = HEIGHT * w / Math.max(1, h);
			for (int i = 0; i < blades.length; i++)
			{
				blades[i] = new Blade(random, width);
			}
			surfaceReady = true;
			drawFrame();
		}

		@Override
		public void onVisibilityChanged(boolean value)
		{
			visible = value;
			handler.removeCallbacks(frame);
			if (visible && surfaceReady) drawFrame();
		}

		@Override
		public void onOffsetsChanged(float x, float y, float xs, float ys, int xp, int yp)
		{
			offset = isPreview() ? 0.5f : Math.max(0f, Math.min(1f, x));
		}

		@Override
		public void onSurfaceDestroyed(SurfaceHolder holder)
		{
			surfaceReady = false;
			handler.removeCallbacks(frame);
			super.onSurfaceDestroyed(holder);
		}

		@Override
		public void onDestroy()
		{
			visible = false;
			surfaceReady = false;
			handler.removeCallbacks(frame);
			for (Bitmap bitmap : skies)
			{
				if (bitmap != null) bitmap.recycle();
			}
			super.onDestroy();
		}

		private void drawFrame()
		{
			handler.removeCallbacks(frame);
			if (!surfaceReady || !visible) return;
			SurfaceHolder holder = getSurfaceHolder();
			Canvas canvas = null;
			try
			{
				canvas = holder.lockCanvas();
				if (canvas != null)
				{
					canvas.save();
					canvas.scale(canvas.getWidth() / width, canvas.getHeight() / HEIGHT);
					canvas.drawColor(Color.BLACK);
					float brightness = drawSky(canvas);
					float time = SystemClock.uptimeMillis() * 0.00004f;
					for (Blade blade : blades) drawBlade(canvas, blade, brightness, time);
					canvas.restore();
				}
			}
			finally
			{
				if (canvas != null) holder.unlockCanvasAndPost(canvas);
			}
			if (visible && surfaceReady) handler.postDelayed(frame, 50);
		}

		private float drawSky(Canvas canvas)
		{
			calendar.setTimeZone(java.util.TimeZone.getDefault());
			calendar.setTimeInMillis(System.currentTimeMillis());
			float t = (calendar.get(Calendar.HOUR_OF_DAY) * 3600
				+ calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND)) / 86400f;
			if (isPreview()) t = (SystemClock.uptimeMillis() % 30000L) / 30000f;
			// Original no-location fallback: dawn 07:12, dusk 18:00.
			float dawn = 0.3f, morning = dawn + 1f / 12f;
			float dusk = 0.75f, afternoon = dusk - 1f / 12f;
			if (t < dawn || t > dusk)
			{
				background(canvas, 0, 255);
				return 0f;
			}
			if (t < morning)
			{
				float progress = (t - dawn) / (morning - dawn) * 2f;
				blend(canvas, progress <= 1f ? 0 : 1, progress <= 1f ? 1 : 2,
					progress <= 1f ? progress : progress - 1f);
				return Math.min(1f, progress);
			}
			if (t < afternoon)
			{
				background(canvas, 2, 255);
				return 1f;
			}
			float progress = (t - afternoon) / (dusk - afternoon) * 2f;
			blend(canvas, progress <= 1f ? 2 : 3, progress <= 1f ? 3 : 0,
				progress <= 1f ? progress : progress - 1f);
			return Math.max(0f, 1f - progress);
		}

		private void blend(Canvas canvas, int first, int second, float fraction)
		{
			background(canvas, first, 255);
			background(canvas, second, Math.round(255f * Math.max(0f, Math.min(1f, fraction))));
		}

		private void background(Canvas canvas, int index, int alpha)
		{
			paint.setColor(Color.WHITE);
			paint.setAlpha(alpha);
			paint.setFilterBitmap(false);
			if (index == 0)
			{
				// Match the original two horizontal star tiles and reversed V coordinates.
				canvas.save();
				canvas.translate(0, 960f);
				canvas.scale(1f, -1f);
				for (int i = 0; i < 2; i++)
				{
					rect.set(i * width / 2f, -32f, (i + 1) * width / 2f, 992f);
					canvas.drawBitmap(skies[index], null, rect, paint);
				}
				canvas.restore();
			}
			else
			{
				rect.set(0, 0, width, HEIGHT);
				canvas.drawBitmap(skies[index], null, rect, paint);
			}
			paint.setAlpha(255);
		}

		private void drawBlade(Canvas canvas, Blade blade, float brightness, float time)
		{
			float target = (noise.turbulence(blade.x * 0.006f, time) - 0.5f) * 0.5f;
			blade.angle = Math.max(-0.09f, Math.min(0.09f,
				blade.angle + (target + blade.offset - blade.angle) * 0.15f));
			float angle = (float) Math.PI / 2f;
			xs[0] = blade.x + width * (1f - offset);
			ys[0] = HEIGHT + 0.25f;
			float x = xs[0], y = HEIGHT;
			for (int i = 1; i <= blade.segments; i++)
			{
				x -= (float) Math.cos(angle) * blade.lengthX;
				y -= (float) Math.sin(angle) * blade.lengthY;
				xs[i] = x;
				ys[i] = y;
				angle += blade.angle * blade.hardness;
			}
			path.rewind();
			path.moveTo(xs[0] - blade.segments * blade.scale, ys[0]);
			for (int i = 1; i <= blade.segments; i++)
			{
				path.lineTo(xs[i] - (blade.segments - i) * blade.scale, ys[i]);
			}
			for (int i = blade.segments - 1; i >= 0; i--)
			{
				path.lineTo(xs[i] + (blade.segments - i) * blade.scale, ys[i]);
			}
			path.close();
			hsv[0] = blade.hue * 360f;
			hsv[1] = blade.saturation;
			hsv[2] = blade.brightness * brightness;
			paint.setColor(Color.HSVToColor(hsv));
			canvas.drawPath(path, paint);
		}
	}

	private static class Blade
	{
		final int segments;
		final float x, offset, scale, lengthX, lengthY, hardness, hue, saturation, brightness;
		float angle;
		Blade(Random r, float width)
		{
			float size = r.nextFloat() * 4f + 4f;
			segments = (int) (size / 0.5f);
			x = (r.nextFloat() * 2f - 1f) * width;
			offset = r.nextFloat() * 0.2f - 0.1f;
			scale = 4f / (size / 0.5f) + (r.nextFloat() * 0.6f + 0.2f) * 0.5f;
			lengthX = (r.nextFloat() * 4.5f + 3f) * 0.5f * size;
			lengthY = (r.nextFloat() * 5.5f + 2f) * 0.5f * size;
			hardness = (r.nextFloat() + 0.2f) * 0.5f;
			hue = r.nextFloat() * 0.02f + 0.2f;
			saturation = r.nextFloat() * 0.22f + 0.78f;
			brightness = r.nextFloat() * 0.65f + 0.35f;
		}
	}

	private static class Noise
	{
		private final int[] permutation = new int[512];
		private final float[] gx = new float[256], gy = new float[256];
		Noise(Random random)
		{
			for (int i = 0; i < 256; i++)
			{
				permutation[i] = i;
				float x, y, length;
				do
				{
					x = random.nextFloat() * 2f - 1f;
					y = random.nextFloat() * 2f - 1f;
					length = (float) Math.sqrt(x * x + y * y);
				} while (length == 0f);
				gx[i] = x / length;
				gy[i] = y / length;
			}
			for (int i = 255; i >= 0; i--)
			{
				int j = random.nextInt(256), old = permutation[i];
				permutation[i] = permutation[j];
				permutation[j] = old;
			}
			System.arraycopy(permutation, 0, permutation, 256, 256);
		}

		float turbulence(float x, float y)
		{
			float sum = 0f;
			for (int frequency = 1; frequency <= 4; frequency *= 2)
			{
				sum += Math.abs(sample(x * frequency, y * frequency)) / frequency;
			}
			return sum;
		}

		private float sample(float x, float y)
		{
			int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
			x -= ix;
			y -= iy;
			int a = permutation[ix & 255], b = permutation[(ix + 1) & 255];
			int aa = permutation[a + (iy & 255)], ab = permutation[a + ((iy + 1) & 255)];
			int ba = permutation[b + (iy & 255)], bb = permutation[b + ((iy + 1) & 255)];
			float sx = x * x * (3f - 2f * x), sy = y * y * (3f - 2f * y);
			float top = mix(gx[aa] * x + gy[aa] * y, gx[ba] * (x - 1f) + gy[ba] * y, sx);
			float bottom = mix(gx[ab] * x + gy[ab] * (y - 1f), gx[bb] * (x - 1f) + gy[bb] * (y - 1f), sx);
			return 1.5f * mix(top, bottom, sy);
		}

		private float mix(float a, float b, float t)
		{
			return a + (b - a) * t;
		}
	}
}
