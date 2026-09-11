/*
 * Copyright (C) 2009 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * OpenGL ES adaptation of NexusRS.java and nexus.rs.
 */
package com.android.wallpaper.nexus;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Build;
import android.os.SystemClock;
import com.android.wallpaper.R;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import static android.opengl.GLES20.*;

public final class NexusRenderer implements GLSurfaceView.Renderer
{
	private static final float CELL = 14f, TRAIL = 560f, GLOW = 64f;
	private static final float[][] COLORS =
	{
		{1f, 0f, 0f},
		{0f, 0.8f, 0f},
		{0f, 0.4f, 0.9f},
		{1f, 0.8f, 0f}
	};
	private final Resources resources;
	private final Random random = new Random();
	private final Pulse[] normal = new Pulse[20], extra = new Pulse[40];
	private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
		.order(ByteOrder.nativeOrder()).asFloatBuffer();
	private final float[] projection = new float[16];
	private int program, position, uv, matrix, tint, sampler;
	private int background, pulseTexture, glowTexture;
	private float width, height, pixelScale;
	private long lastFrame;
	private double clock;
	private final boolean redMode;
	public volatile float offset;

	private static final String VERTEX =
		"uniform mat4 uMVP;\nattribute vec2 aPosition;\nattribute vec2 aUV;\nvarying vec2 vUV;\n"
		+ "void main()\n{\n\tgl_Position = uMVP * vec4(aPosition, 0.0, 1.0);\n\tvUV = aUV;\n}\n";
	private static final String FRAGMENT =
		"precision mediump float;\nuniform sampler2D uTexture;\nuniform vec4 uTint;\nvarying vec2 vUV;\n"
		+ "void main()\n{\n\tgl_FragColor = texture2D(uTexture, vUV) * uTint;\n}\n";

	NexusRenderer(Resources resources, boolean preview)
	{
		this.resources = resources;
		redMode = resources.getInteger(R.integer.nexus_mode) == 1;
		for (int i = 0; i < normal.length; i++) normal[i] = new Pulse();
		for (int i = 0; i < extra.length; i++) extra[i] = new Pulse();
	}

	@Override
	public void onSurfaceCreated(GL10 unused, EGLConfig config)
	{
		program = program(VERTEX, FRAGMENT);
		position = glGetAttribLocation(program, "aPosition");
		uv = glGetAttribLocation(program, "aUV");
		matrix = glGetUniformLocation(program, "uMVP");
		tint = glGetUniformLocation(program, "uTint");
		sampler = glGetUniformLocation(program, "uTexture");
		background = texture(R.drawable.pyramid_background, false);
		pulseTexture = texture(R.drawable.pulse, true);
		glowTexture = texture(R.drawable.glow, true);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glClearColor(0, 0, 0, 1);
		lastFrame = 0;
	}

	@Override
	public void onSurfaceChanged(GL10 unused, int w, int h)
	{
		glViewport(0, 0, w, h);
		pixelScale = Math.min(w, h) / 480f;
		width = w / pixelScale;
		height = h / pixelScale;
		for (Pulse p : normal) restart(p);
		for (Pulse p : extra) p.active = false;
		lastFrame = 0;
	}

	private void restart(Pulse p)
	{
		float speed = 0.7f + random.nextFloat() * 0.6f;
		p.dx = p.dy = 0f;
		if (random.nextBoolean())
		{
			p.x = random.nextInt(Math.max(1, (int) (width * 2f / CELL))) * CELL;
			boolean top = random.nextBoolean();
			p.y = top ? 0f : height;
			p.dy = top ? speed : -speed;
		}
		else
		{
			p.y = random.nextInt(Math.max(1, (int) (height / CELL))) * CELL;
			boolean left = random.nextBoolean();
			p.x = left ? 0f : width * 2f;
			p.dx = left ? speed : -speed;
		}
		p.start = clock + random.nextInt(2000);
		p.color = random.nextInt(4);
		p.active = true;
	}

	// Called on the GL thread. Convert screen pixels through the same scroll
	// transform used for rendering, replacing the old hardcoded 960px calculation.
	void addTap(int screenX, int screenY)
	{
		if (width <= 0f || pixelScale <= 0f) return;
		float x = (float) Math.floor((screenX / pixelScale + scroll()) / CELL) * CELL;
		float y = (float) Math.floor(screenY / pixelScale / CELL) * CELL;
		int available = 0;
		for (Pulse p : extra) if (!p.active) available++;
		if (available < 4) return;
		int direction = 0, color = random.nextInt(4);
		for (Pulse p : extra)
		{
			if (p.active) continue;
			p.x = x;
			p.y = y;
			p.dx = direction == 0 ? 1.5f : direction == 1 ? -1.5f : 0f;
			p.dy = direction == 2 ? 1.5f : direction == 3 ? -1.5f : 0f;
			p.color = (color + direction) % 4;
			p.start = clock;
			p.active = true;
			if (++direction == 4) break;
		}
	}

	private float scroll()
	{
		return height > width ? offset * width : 0f;
	}

	@Override
	public void onDrawFrame(GL10 unused)
	{
		long now = System.nanoTime();
		clock += lastFrame == 0 ? 0 : Math.min(100.0, (now - lastFrame) / 1000000.0);
		lastFrame = now;
		glClear(GL_COLOR_BUFFER_BIT);
		Matrix.orthoM(projection, 0, 0f, width, height, 0f, -1f, 1f);
		Matrix.translateM(projection, 0, -scroll(), 0f, 0f);
		glUseProgram(program);
		glUniformMatrix4fv(matrix, 1, false, projection, 0);
		glActiveTexture(GL_TEXTURE0);
		glUniform1i(sampler, 0);
		glDisable(GL_BLEND);
		glUniform4f(tint, 1f, 1f, 1f, 1f);
		draw(background, 0f, 0f, width * 2f, height, 0);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE);
		for (Pulse p : normal) drawPulse(p, false);
		for (Pulse p : extra) drawPulse(p, true);
		glDisableVertexAttribArray(position);
		glDisableVertexAttribArray(uv);
	}

	private void drawPulse(Pulse p, boolean isExtra)
	{
		if (!p.active || clock < p.start) return;
		float delta = (float) (clock - p.start) * 0.2f;
		float x = p.x + p.dx * delta, y = p.y + p.dy * delta;
		boolean finished = p.dx < 0f ? x + TRAIL <= 0f
			: p.dx > 0f ? x + CELL - TRAIL >= width * 2f
			: p.dy < 0f ? y + TRAIL <= 0f : y + CELL - TRAIL >= height;
		if (finished)
		{
			if (isExtra) p.active = false;
			else restart(p);
			return;
		}
		if (redMode) glUniform4f(tint, 0.9f, 0.1f, 0.1f, 0.8f);
		else
		{
			float[] color = COLORS[p.color];
			glUniform4f(tint, color[0], color[1], color[2], 0.8f);
		}
		if (p.dx < 0f) draw(pulseTexture, x, y, x + TRAIL, y + CELL, 0);
		else if (p.dx > 0f) draw(pulseTexture, x + CELL - TRAIL, y, x + CELL, y + CELL, 2);
		else if (p.dy < 0f) draw(pulseTexture, x, y, x + CELL, y + TRAIL, 3);
		else draw(pulseTexture, x, y + CELL - TRAIL, x + CELL, y + CELL, 1);
		float cx = x + CELL / 2f, cy = y + CELL / 2f;
		draw(glowTexture, cx - GLOW / 2f, cy - GLOW / 2f,
			cx + GLOW / 2f, cy + GLOW / 2f, 0);
	}

	private void vertex(float x, float y, float u, float v, int turn)
	{
		quad.put(x).put(y);
		if (turn == 0) quad.put(u).put(v);
		else if (turn == 1) quad.put(1f - v).put(u);
		else if (turn == 2) quad.put(1f - u).put(1f - v);
		else quad.put(v).put(1f - u);
	}

	private void draw(int texture, float left, float top, float right, float bottom, int turn)
	{
		quad.position(0);
		vertex(left, top, 0f, 0f, turn);
		vertex(right, top, 1f, 0f, turn);
		vertex(left, bottom, 0f, 1f, turn);
		vertex(right, bottom, 1f, 1f, turn);
		glBindTexture(GL_TEXTURE_2D, texture);
		quad.position(0);
		glEnableVertexAttribArray(position);
		glVertexAttribPointer(position, 2, GL_FLOAT, false, 16, quad);
		quad.position(2);
		glEnableVertexAttribArray(uv);
		glVertexAttribPointer(uv, 2, GL_FLOAT, false, 16, quad);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	}

	private static class Pulse
	{
		float x, y, dx, dy;
		double start;
		int color;
		boolean active;
	}

	private int texture(int resource, boolean linear)
	{
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inScaled = false;
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;
		if (Build.VERSION.SDK_INT >= 19) options.inPremultiplied = false;
		Bitmap bitmap = BitmapFactory.decodeResource(resources, resource, options);
		if (bitmap == null) throw new IllegalStateException("Cannot decode Nexus texture " + resource);
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
			throw new IllegalStateException("Nexus shader: " + log);
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
			throw new IllegalStateException("Nexus program: " + log);
		}
		return program;
	}

}
