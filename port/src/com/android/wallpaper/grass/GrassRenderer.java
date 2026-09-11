/*
 * Copyright (C) 2010 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * OpenGL ES adaptation of GrassRS.java, grass.rs and the Canvas port.
 */
package com.android.wallpaper.grass;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.SystemClock;
import com.android.wallpaper.R;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Calendar;
import java.util.Random;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import static android.opengl.GLES20.*;

public final class GrassRenderer implements GLSurfaceView.Renderer
{
	private static final float HEIGHT = 800f;
	private final Resources resources;
	private final boolean preview;
	private final Random random = new Random();
	private final Noise noise = new Noise(random);
	private final Blade[] blades = new Blade[200];
	private final float[] colors = new float[600];
	private final int[] skies = new int[4];
	private final Calendar calendar = Calendar.getInstance();
	private final FloatBuffer quad = buffer(16);
	private FloatBuffer vertices;
	private ShortBuffer indices;
	private int indexCount;
	private float width, pixelScale;
	private long lastFrame;
	public volatile float offset = 0.5f;
	private int skyProgram, bladeProgram;
	private int skyPosition, skyUV, skyScreen, skyTexture, skyAlpha;
	private int bladePosition, bladeSide, bladeWidth, bladeColor, bladeScreen, bladeBrightness;

	private static final String SKY_VERTEX =
		"attribute vec2 aPosition;\nattribute vec2 aUV;\nuniform vec2 uScreen;\nvarying vec2 vUV;\n"
		+ "void main()\n{\n\tgl_Position = vec4(aPosition.x/uScreen.x*2.0-1.0, 1.0-aPosition.y/uScreen.y*2.0, 0.0, 1.0);\n\tvUV = aUV;\n}\n";
	private static final String SKY_FRAGMENT =
		"precision mediump float;\nuniform sampler2D uTexture;\nuniform float uAlpha;\nvarying vec2 vUV;\n"
		+ "void main()\n{\n\tgl_FragColor = vec4(texture2D(uTexture, vUV).rgb, uAlpha);\n}\n";
	private static final String BLADE_VERTEX =
		"attribute vec2 aPosition;\nattribute float aSide;\nattribute float aWidth;\nattribute vec3 aColor;\n"
		+ "uniform vec2 uScreen;\nuniform float uBrightness;\nvarying float vSide;\nvarying float vWidth;\nvarying vec3 vColor;\n"
		+ "void main()\n{\n\tgl_Position = vec4(aPosition.x/uScreen.x*2.0-1.0, 1.0-aPosition.y/uScreen.y*2.0, 0.0, 1.0);\n"
		+ "\tvSide = aSide;\n\tvWidth = aWidth;\n\tvColor = aColor * uBrightness;\n}\n";
	private static final String BLADE_FRAGMENT =
		"precision mediump float;\nvarying float vSide;\nvarying float vWidth;\nvarying vec3 vColor;\n"
		+ "void main()\n{\n\tfloat coverage = clamp((1.0-abs(vSide))*vWidth, 0.0, 1.0);\n"
		+ "\tgl_FragColor = vec4(vColor, coverage);\n}\n";

	GrassRenderer(Resources resources, boolean preview)
	{
		this.resources = resources;
		this.preview = preview;
	}

	@Override
	public void onSurfaceCreated(GL10 unused, EGLConfig config)
	{
		skyProgram = program(SKY_VERTEX, SKY_FRAGMENT);
		bladeProgram = program(BLADE_VERTEX, BLADE_FRAGMENT);
		skyPosition = glGetAttribLocation(skyProgram, "aPosition");
		skyUV = glGetAttribLocation(skyProgram, "aUV");
		skyScreen = glGetUniformLocation(skyProgram, "uScreen");
		skyTexture = glGetUniformLocation(skyProgram, "uTexture");
		skyAlpha = glGetUniformLocation(skyProgram, "uAlpha");
		bladePosition = glGetAttribLocation(bladeProgram, "aPosition");
		bladeSide = glGetAttribLocation(bladeProgram, "aSide");
		bladeWidth = glGetAttribLocation(bladeProgram, "aWidth");
		bladeColor = glGetAttribLocation(bladeProgram, "aColor");
		bladeScreen = glGetUniformLocation(bladeProgram, "uScreen");
		bladeBrightness = glGetUniformLocation(bladeProgram, "uBrightness");
		int[] ids = {R.drawable.night, R.drawable.sunrise, R.drawable.sky, R.drawable.sunset};
		for (int i = 0; i < 4; i++) skies[i] = texture(ids[i], false);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glClearColor(0f, 0f, 0f, 1f);
		lastFrame = 0;
	}

	@Override
	public void onSurfaceChanged(GL10 unused, int w, int h)
	{
		glViewport(0, 0, w, h);
		width = HEIGHT * w / Math.max(1, h);
		pixelScale = h / HEIGHT;
		int count = 0;
		indexCount = 0;
		float[] hsv = new float[3];
		for (int i = 0; i < blades.length; i++)
		{
			Blade blade = blades[i] = new Blade(random, width);
			count += (blade.segments + 1) * 2;
			indexCount += blade.segments * 6;
			hsv[0] = blade.hue * 360f;
			hsv[1] = blade.saturation;
			hsv[2] = blade.brightness;
			int color = Color.HSVToColor(hsv);
			colors[i * 3] = Color.red(color) / 255f;
			colors[i * 3 + 1] = Color.green(color) / 255f;
			colors[i * 3 + 2] = Color.blue(color) / 255f;
		}
		vertices = buffer(count * 7);
		indices = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
		int base = 0;
		for (Blade blade : blades)
		{
			for (int j = 0; j < blade.segments; j++)
			{
				int a = base + j * 2;
				indices.put((short) a).put((short) (a+1)).put((short) (a+2));
				indices.put((short) (a+1)).put((short) (a+3)).put((short) (a+2));
			}
			base += (blade.segments + 1) * 2;
		}
		indices.position(0);
	}

	@Override
	public void onDrawFrame(GL10 unused)
	{
		glClear(GL_COLOR_BUFFER_BIT);
		glActiveTexture(GL_TEXTURE0);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		float brightness = drawSky();
		vertices.position(0);
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0.05f : Math.min(0.1f, (now - lastFrame) * 0.000000001f);
		lastFrame = now;
		float smoothing = 1f - (float) Math.pow(0.85, dt / 0.05f);
		// Noise repeats every 256 units; reduce in double precision for long uptimes.
		float time = (float) ((SystemClock.uptimeMillis() * 0.00004) % 256.0);
		float xOffset = width * (1f - (preview ? 0.5f : offset));
		for (int i = 0; i < blades.length; i++)
		{
			Blade blade = blades[i];
			float target = (noise.turbulence(blade.x * 0.006f, time) - 0.5f) * 0.5f;
			blade.angle = Math.max(-0.09f, Math.min(0.09f,
				blade.angle + (target + blade.offset - blade.angle) * smoothing));
			float angle = (float) Math.PI / 2f;
			float x = blade.x + xOffset, y = HEIGHT;
			pair(x, y + 0.25f, blade.segments * blade.scale, i);
			for (int j = 1; j <= blade.segments; j++)
			{
				x -= (float) Math.cos(angle) * blade.lengthX;
				y -= (float) Math.sin(angle) * blade.lengthY;
				pair(x, y, (blade.segments-j) * blade.scale, i);
				angle += blade.angle * blade.hardness;
			}
		}
		glUseProgram(bladeProgram);
		glUniform2f(bladeScreen, width, HEIGHT);
		glUniform1f(bladeBrightness, brightness);
		attribute(bladePosition, 2, 28, vertices, 0);
		attribute(bladeSide, 1, 28, vertices, 2);
		attribute(bladeWidth, 1, 28, vertices, 3);
		attribute(bladeColor, 3, 28, vertices, 4);
		indices.position(0);
		glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_SHORT, indices);
		glDisableVertexAttribArray(bladePosition);
		glDisableVertexAttribArray(bladeSide);
		glDisableVertexAttribArray(bladeWidth);
		glDisableVertexAttribArray(bladeColor);
	}

	private void pair(float x, float y, float halfWidth, int color)
	{
		for (int side = -1; side <= 1; side += 2)
		{
			vertices.put(x + side * halfWidth).put(y).put(side).put(halfWidth * pixelScale);
			vertices.put(colors[color*3]).put(colors[color*3+1]).put(colors[color*3+2]);
		}
	}

	private void blend(int first, int second, float fraction)
	{
		background(first, 255);
		background(second, Math.round(255f * Math.max(0f, Math.min(1f, fraction))));
	}

	private void background(int index, int alpha)
	{
		glUseProgram(skyProgram);
		glUniform2f(skyScreen, width, HEIGHT);
		glUniform1i(skyTexture, 0);
		glUniform1f(skyAlpha, alpha / 255f);
		glBindTexture(GL_TEXTURE_2D, skies[index]);
		int tiles = index == 0 ? 2 : 1;
		for (int i = 0; i < tiles; i++)
		{
			float left = i * width / tiles, right = (i+1) * width / tiles;
			float top = index == 0 ? -32f : 0f, bottom = index == 0 ? 992f : HEIGHT;
			float vTop = index == 0 ? 1f : 0f, vBottom = 1f - vTop;
			quad.position(0);
			quad.put(left).put(top).put(0f).put(vTop);
			quad.put(right).put(top).put(1f).put(vTop);
			quad.put(left).put(bottom).put(0f).put(vBottom);
			quad.put(right).put(bottom).put(1f).put(vBottom);
			attribute(skyPosition, 2, 16, quad, 0);
			attribute(skyUV, 2, 16, quad, 2);
			glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		}
		glDisableVertexAttribArray(skyPosition);
		glDisableVertexAttribArray(skyUV);
	}

	private static void attribute(int location, int size, int stride, FloatBuffer data, int start)
	{
		data.position(start);
		glEnableVertexAttribArray(location);
		glVertexAttribPointer(location, size, GL_FLOAT, false, stride, data);
	}

	private static FloatBuffer buffer(int count)
	{
		return ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
	}

	private float drawSky()
	{
		calendar.setTimeZone(java.util.TimeZone.getDefault());
		calendar.setTimeInMillis(System.currentTimeMillis());
		float t = (calendar.get(Calendar.HOUR_OF_DAY) * 3600
			+ calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND)) / 86400f;
		if (preview) t = (SystemClock.uptimeMillis() % 30000L) / 30000f;
		// Original no-location fallback: dawn 07:12, dusk 18:00.
		float dawn = 0.3f, morning = dawn + 1f / 12f;
		float dusk = 0.75f, afternoon = dusk - 1f / 12f;
		if (t < dawn || t > dusk)
		{
			background(0, 255);
			return 0f;
		}
		if (t < morning)
		{
			float progress = (t - dawn) / (morning - dawn) * 2f;
			blend(progress <= 1f ? 0 : 1, progress <= 1f ? 1 : 2,
				progress <= 1f ? progress : progress - 1f);
			return Math.min(1f, progress);
		}
		if (t < afternoon)
		{
			background(2, 255);
			return 1f;
		}
		float progress = (t - afternoon) / (dusk - afternoon) * 2f;
		blend(progress <= 1f ? 2 : 3, progress <= 1f ? 3 : 0,
			progress <= 1f ? progress : progress - 1f);
		return Math.max(0f, 1f - progress);
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

	private int texture(int resource, boolean linear)
	{
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inScaled = false;
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;
		if (Build.VERSION.SDK_INT >= 19) options.inPremultiplied = false;
		Bitmap bitmap = BitmapFactory.decodeResource(resources, resource, options);
		if (bitmap == null) throw new IllegalStateException("Cannot decode Grass texture " + resource);
		int[] ids = new int[1];
		glGenTextures(1, ids, 0);
		glBindTexture(GL_TEXTURE_2D, ids[0]);
		int filter = linear ? GL_LINEAR : GL_NEAREST;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0);
		bitmap.recycle();
		return ids[0];
	}

	private static int shader(int type, String source)
	{
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		int[] status = new int[1];
		glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
		if (status[0] == 0)
		{
			String log = glGetShaderInfoLog(shader);
			glDeleteShader(shader);
			throw new IllegalStateException("Grass shader: " + log);
		}
		return shader;
	}

	private static int program(String vertex, String fragment)
	{
		int vs = shader(GL_VERTEX_SHADER, vertex), fs = shader(GL_FRAGMENT_SHADER, fragment);
		int program = glCreateProgram();
		glAttachShader(program, vs);
		glAttachShader(program, fs);
		glLinkProgram(program);
		glDeleteShader(vs);
		glDeleteShader(fs);
		int[] status = new int[1];
		glGetProgramiv(program, GL_LINK_STATUS, status, 0);
		if (status[0] == 0)
		{
			String log = glGetProgramInfoLog(program);
			glDeleteProgram(program);
			throw new IllegalStateException("Grass program: " + log);
		}
		return program;
	}

}
