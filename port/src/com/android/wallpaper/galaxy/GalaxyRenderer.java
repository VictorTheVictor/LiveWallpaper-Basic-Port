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

public final class GalaxyRenderer implements GLSurfaceView.Renderer
{
	private static final int COUNT = 12000;
	// angle, radius, height, red, green, blue, size (in original pixels)
	private final FloatBuffer particles = buffer(COUNT * 7);
	private final float[] speeds = new float[COUNT];
	private final FloatBuffer quad = buffer(16);
	private final Resources resources;
	private final boolean preview;
	private final float[] projection = new float[16], model = new float[16], mvp = new float[16];
	private final float[] identity = new float[16];
	private int starProgram, quadProgram, space, flares, light;
	private int starPosition, starColor, starSize, starMatrix, starScale, starTexture;
	private int quadPosition, quadUV, quadMatrix, quadTexture;
	private int width, height;
	private float pixelScale;
	private long lastFrame;
	public volatile float offset = 0.5f;

	private static final String STAR_VERTEX =
		"uniform mat4 uMVP;\nuniform float uScale;\n"
		+ "attribute vec3 aPosition;\nattribute vec3 aColor;\nattribute float aSize;\n"
		+ "varying vec3 vColor;\nvoid main()\n{\n"
		+ "\tfloat dist = aPosition.y;\n\tfloat angle = aPosition.x;\n"
		+ "\tfloat x = dist * sin(angle);\n\tfloat y = dist * cos(angle) * 0.892;\n"
		+ "\tfloat p = dist * 5.5;\n\tfloat s = cos(p);\n\tfloat t = sin(p);\n"
		+ "\tgl_Position = uMVP * vec4(t*x+s*y, s*x-t*y, aPosition.z, 1.0);\n"
		+ "\tgl_PointSize = aSize * uScale;\n\tvColor = aColor;\n}\n";
	private static final String STAR_FRAGMENT =
		"precision mediump float;\nuniform sampler2D uTexture;\nvarying vec3 vColor;\n"
		+ "void main()\n{\n\tgl_FragColor = texture2D(uTexture, gl_PointCoord) * vec4(vColor, 1.0);\n}\n";
	private static final String QUAD_VERTEX =
		"uniform mat4 uMVP;\nattribute vec2 aPosition;\nattribute vec2 aUV;\nvarying vec2 vUV;\n"
		+ "void main()\n{\n\tgl_Position = uMVP * vec4(aPosition, 0.0, 1.0);\n\tvUV = aUV;\n}\n";
	private static final String QUAD_FRAGMENT =
		"precision mediump float;\nuniform sampler2D uTexture;\nvarying vec2 vUV;\n"
		+ "void main()\n{\n\tgl_FragColor = vec4(texture2D(uTexture, vUV).rgb, 1.0);\n}\n";

	GalaxyRenderer(Resources resources, boolean preview)
	{
		this.resources = resources;
		this.preview = preview;
		Matrix.setIdentityM(identity, 0);
	}

	@Override
	public void onSurfaceCreated(GL10 unused, EGLConfig config)
	{
		starProgram = program(STAR_VERTEX, STAR_FRAGMENT);
		quadProgram = program(QUAD_VERTEX, QUAD_FRAGMENT);
		starPosition = glGetAttribLocation(starProgram, "aPosition");
		starColor = glGetAttribLocation(starProgram, "aColor");
		starSize = glGetAttribLocation(starProgram, "aSize");
		starMatrix = glGetUniformLocation(starProgram, "uMVP");
		starScale = glGetUniformLocation(starProgram, "uScale");
		starTexture = glGetUniformLocation(starProgram, "uTexture");
		quadPosition = glGetAttribLocation(quadProgram, "aPosition");
		quadUV = glGetAttribLocation(quadProgram, "aUV");
		quadMatrix = glGetUniformLocation(quadProgram, "uMVP");
		quadTexture = glGetUniformLocation(quadProgram, "uTexture");
		space = texture(R.drawable.space, false);
		flares = texture(R.drawable.flares, true);
		light = texture(R.drawable.light1, false);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glClearColor(0, 0, 0, 1);
		lastFrame = 0;
	}

	@Override
	public void onSurfaceChanged(GL10 unused, int width, int height)
	{
		this.width = width;
		this.height = height;
		glViewport(0, 0, width, height);
		pixelScale = Math.min(width, height) / 480f;
		float aspect = (float) Math.max(width, height) / Math.min(width, height);
		Matrix.frustumM(projection, 0, width > height ? -aspect : -1f,
			width > height ? aspect : 1f, width > height ? -1f : -aspect,
			width > height ? 1f : aspect, 1f, 100f);
		Matrix.rotateM(projection, 0, 180f, 0f, 1f, 0f);
		Matrix.scaleM(projection, 0, -2f, 2f, 1f);
		Matrix.translateM(projection, 0, 0f, 0f, 2f);
		createParticles();
	}

	private void createParticles()
	{
		Random random = new Random();
		float logicalWidth = width / pixelScale;
		float scale = 300f / (logicalWidth * 0.5f);
		particles.position(0);
		for (int i = 0; i < COUNT; i++)
		{
			float distance = (float) Math.abs(random.nextGaussian()) * 150f + random.nextFloat() * 64f;
			float fraction = distance / 300f;
			float z = (float) random.nextGaussian() * 0.4f * (1f - fraction);
			float red = distance < 99f ? (int) (220f + fraction * 35f) : 180f;
			float green = distance < 99f ? 220f : 180f;
			float blue = distance < 99f ? 220f : Math.min(255f, Math.max(140f, 140f + fraction * 115f));
			float size = (int) ((1.2f + random.nextFloat() * 0.9f) * 60f) / 255f * 10f;
			z *= distance > 45f ? 0.6f * (1f - fraction) : 0.72f;
			// Preserve the original mapf sign; radius is intentionally negative.
			distance = -scale * ((distance + 4f) / 308f);
			speeds[i] = (0.0015f + random.nextFloat() * 0.001f) * (0.5f + scale / distance) * 0.8f;
			particles.put(random.nextFloat() * 6.283f).put(distance).put(z / 5f);
			particles.put(red / 255f).put(green / 255f).put(blue / 255f).put(size);
		}
		particles.position(0);
		lastFrame = 0;
	}

	@Override
	public void onDrawFrame(GL10 unused)
	{
		long now = SystemClock.uptimeMillis();
		float step = lastFrame == 0 ? 1f : Math.min(3f, (now - lastFrame) / 45f);
		lastFrame = now;
		glClear(GL_COLOR_BUFFER_BIT);
		glActiveTexture(GL_TEXTURE0);
		glDisable(GL_BLEND);
		// Original background: two horizontal repetitions, vertically reversed.
		drawQuad(space, identity, -1f, -1f, 1f, 1f, 2f, false);
		float angle = preview ? 0f : (offset * 2f - 1f) * 50f;
		Matrix.setIdentityM(model, 0);
		Matrix.translateM(model, 0, 0f, 0f, 10f - 6f * Math.abs(angle) / 50f);
		Matrix.scaleM(model, 0, height > width ? 6.6f : 12.6f, height > width ? 6f : 12f, 1f);
		Matrix.rotateM(model, 0, Math.abs(angle), 1f, 0f, 0f);
		Matrix.rotateM(model, 0, angle, 0f, 0.4f, 0.1f);
		Matrix.multiplyMM(mvp, 0, projection, 0, model, 0);
		for (int i = 0; i < COUNT; i++)
		{
			int index = i * 7;
			particles.put(index, (particles.get(index) + speeds[i] * step) % 6.2831853f);
		}
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE);
		glUseProgram(starProgram);
		glUniformMatrix4fv(starMatrix, 1, false, mvp, 0);
		glUniform1f(starScale, pixelScale);
		glUniform1i(starTexture, 0);
		glBindTexture(GL_TEXTURE_2D, flares);
		attribute(starPosition, 3, 28, particles, 0);
		attribute(starColor, 3, 28, particles, 3);
		attribute(starSize, 1, 28, particles, 6);
		glDrawArrays(GL_POINTS, 0, COUNT);
		glDisableVertexAttribArray(starPosition);
		glDisableVertexAttribArray(starColor);
		glDisableVertexAttribArray(starSize);
		float scale = 512f / (width / pixelScale);
		drawQuad(light, projection, -scale * 1.05f, -scale, scale * 1.15f, scale, 1f, true);
	}

	private void drawQuad(int texture, float[] matrix, float left, float bottom,
		float right, float top, float repeat, boolean flip)
	{
		float lowV = flip ? 1f : 0f, highV = flip ? 0f : 1f;
		quad.position(0);
		quad.put(left).put(bottom).put(0f).put(lowV);
		quad.put(right).put(bottom).put(repeat).put(lowV);
		quad.put(left).put(top).put(0f).put(highV);
		quad.put(right).put(top).put(repeat).put(highV);
		glUseProgram(quadProgram);
		glUniformMatrix4fv(quadMatrix, 1, false, matrix, 0);
		glUniform1i(quadTexture, 0);
		glBindTexture(GL_TEXTURE_2D, texture);
		attribute(quadPosition, 2, 16, quad, 0);
		attribute(quadUV, 2, 16, quad, 2);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		glDisableVertexAttribArray(quadPosition);
		glDisableVertexAttribArray(quadUV);
	}

	private static void attribute(int location, int size, int stride, FloatBuffer data, int offset)
	{
		data.position(offset);
		glEnableVertexAttribArray(location);
		glVertexAttribPointer(location, size, GL_FLOAT, false, stride, data);
	}

	private int texture(int resource, boolean linear)
	{
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inScaled = false;
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;
		if (Build.VERSION.SDK_INT >= 19) options.inPremultiplied = false;
		Bitmap bitmap = BitmapFactory.decodeResource(resources, resource, options);
		if (bitmap == null) throw new IllegalStateException("Cannot decode Galaxy texture " + resource);
		int[] ids = new int[1];
		glGenTextures(1, ids, 0);
		glBindTexture(GL_TEXTURE_2D, ids[0]);
		int filter = linear ? GL_LINEAR : GL_NEAREST;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
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
			throw new IllegalStateException("Galaxy shader: " + log);
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
			throw new IllegalStateException("Galaxy program: " + log);
		}
		return program;
	}

	private static FloatBuffer buffer(int count)
	{
		return ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
	}
}
