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

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.SystemClock;
import com.android.wallpaper.R;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Random;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import static android.opengl.GLES20.*;

public final class FallRenderer implements GLSurfaceView.Renderer
{
	private final Resources resources;
	private final Random random = new Random();
	private final Leaf[] leaves = new Leaf[14];
	private final Drop[] drops = new Drop[10];
	private final float[] uniforms = new float[40];
	private final FloatBuffer quad = buffer(16);
	private FloatBuffer mesh;
	private ShortBuffer indices;
	private int indexCount, waterProgram, leafProgram, pond, atlas;
	private int waterPosition, waterScreen, waterWorld, waterScroll, waterDrops, waterTexture;
	private int leafPosition, leafUV, leafScreen, leafTint, leafTexture;
	private float width, height, worldWidth, unitsPerPixel;
	private long lastFrame;
	private float ambientElapsed;
	public volatile float offset;

	private static final String WATER_VERTEX =
		"attribute vec2 aPosition;\nuniform vec2 uScreen;\nuniform float uWorld;\nuniform float uScroll;\n"
		+ "uniform vec4 uDrops[10];\nvarying vec2 vUV;\n"
		+ "vec2 ripple(vec4 d, vec2 pos)\n{\n"
		+ "\tvec2 delta = d.xy - pos;\n\tfloat dist = length(delta);\n"
		+ "\tif (d.w > 0.0 && dist < d.w)\n\t{\n"
		+ "\t\tfloat amp = d.z * dist / (d.w * d.w) * sin(d.w - dist);\n"
		+ "\t\treturn delta * amp;\n\t}\n\treturn vec2(0.0);\n}\n"
		+ "void main()\n{\n"
		+ "\tgl_Position = vec4(aPosition.x/uScreen.x*2.0-1.0, 1.0-aPosition.y/uScreen.y*2.0, 0.0, 1.0);\n"
		+ "\tvec2 world = aPosition + vec2(uScroll, 0.0);\n"
		+ "\tvUV = world / vec2(uWorld, uScreen.y);\n"
		+ "\tfor (int i = 0; i < 10; i++)\n\t{\n\t\tvUV += ripple(uDrops[i], world * 25.0);\n\t}\n}\n";
	private static final String WATER_FRAGMENT =
		"precision mediump float;\nuniform sampler2D uTexture;\nvarying vec2 vUV;\n"
		+ "void main()\n{\n\tgl_FragColor = texture2D(uTexture, vUV);\n}\n";
	private static final String LEAF_VERTEX =
		"attribute vec2 aPosition;\nattribute vec2 aUV;\nuniform vec2 uScreen;\nvarying vec2 vUV;\n"
		+ "void main()\n{\n"
		+ "\tgl_Position = vec4(aPosition.x/uScreen.x*2.0-1.0, 1.0-aPosition.y/uScreen.y*2.0, 0.0, 1.0);\n"
		+ "\tvUV = aUV;\n}\n";
	private static final String LEAF_FRAGMENT =
		"precision mediump float;\nuniform sampler2D uTexture;\nuniform vec4 uTint;\nvarying vec2 vUV;\n"
		+ "void main()\n{\n\tgl_FragColor = texture2D(uTexture, vUV) * uTint;\n}\n";

	FallRenderer(Resources resources, boolean preview)
	{
		this.resources = resources;
		for (int i = 0; i < leaves.length; i++)
			leaves[i] = new Leaf();
		for (int i = 0; i < drops.length; i++)
			drops[i] = new Drop();
	}

	@Override
	public void onSurfaceCreated(GL10 unused, EGLConfig config)
	{
		waterProgram = program(WATER_VERTEX, WATER_FRAGMENT);
		leafProgram = program(LEAF_VERTEX, LEAF_FRAGMENT);
		waterPosition = glGetAttribLocation(waterProgram, "aPosition");
		waterScreen = glGetUniformLocation(waterProgram, "uScreen");
		waterWorld = glGetUniformLocation(waterProgram, "uWorld");
		waterScroll = glGetUniformLocation(waterProgram, "uScroll");
		waterDrops = glGetUniformLocation(waterProgram, "uDrops[0]");
		waterTexture = glGetUniformLocation(waterProgram, "uTexture");
		leafPosition = glGetAttribLocation(leafProgram, "aPosition");
		leafUV = glGetAttribLocation(leafProgram, "aUV");
		leafScreen = glGetUniformLocation(leafProgram, "uScreen");
		leafTint = glGetUniformLocation(leafProgram, "uTint");
		leafTexture = glGetUniformLocation(leafProgram, "uTexture");
		pond = texture(R.drawable.pond, true);
		atlas = texture(R.drawable.leaves, true);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glClearColor(0, 0, 0, 1);
		lastFrame = 0;
	}

	@Override
	public void onSurfaceChanged(GL10 unused, int w, int h)
	{
		glViewport(0, 0, w, h);
		unitsPerPixel = 2f / Math.min(w, h);
		width = w * unitsPerPixel;
		height = h * unitsPerPixel;
		//worldWidth = h > w ? width * 2f : width;
		worldWidth = height * (960f / 800f);
		int columns = 50, rows = Math.max(2, Math.min(200, (int) Math.ceil(50f * h / w)));
		mesh = buffer((columns + 1) * (rows + 1) * 2);
		for (int y = 0; y <= rows; y++)
		{
			for (int x = 0; x <= columns; x++)
			{
				mesh.put(x * width / columns).put(y * height / rows);
			}
		}
		mesh.position(0);
		indexCount = columns * rows * 6;
		indices = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
		for (int y = 0; y < rows; y++)
		{
			for (int x = 0; x < columns; x++)
			{
				int a = y * (columns + 1) + x, b = a + 1, c = a + columns + 1, d = c + 1;
				if ((y & 1) == 0)
				{
					indices.put((short) a).put((short) b).put((short) c);
					indices.put((short) b).put((short) d).put((short) c);
				}
				else
				{
					indices.put((short) a).put((short) b).put((short) d);
					indices.put((short) a).put((short) d).put((short) c);
				}
			}
		}
		indices.position(0);
		for (Leaf leaf : leaves) reset(leaf, false);
		for (Drop drop : drops)
		{
			drop.strength = 0f;
			drop.spread = 1f;
		}
		addDrop(scroll() + width * 0.5f, height * 0.5f, 2f);
		lastFrame = 0;
	}

	private float scroll()
	{
		return (worldWidth - width) * offset;
	}

	void addTap(int x, int y)
	{
		if (width > 0f) addDrop(x * unitsPerPixel + scroll(), y * unitsPerPixel, 2f);
	}

	private void addDrop(float x, float y, float strength)
	{
		Drop weakest = drops[0];
		for (Drop d : drops)
		{
			if (d.strength / d.spread < weakest.strength / weakest.spread) weakest = d;
		}
		weakest.x = x * 25f;
		weakest.y = y * 25f;
		weakest.strength = strength;
		weakest.spread = 1.5f;
	}

	private void reset(Leaf leaf, boolean falling)
	{
		leaf.x = random.nextFloat() * worldWidth;
		leaf.y = random.nextFloat() * height;
		leaf.scale = 0.4f + random.nextFloat() * 0.1f;
		leaf.angle = random.nextFloat() * 360f;
		leaf.spin = (float) Math.toDegrees(random.nextFloat() * 0.04f - 0.02f) * (falling ? 0.35f : 0.25f);
		leaf.sprite = random.nextInt(8);
		leaf.altitude = falling ? 0.7f : -1f;
		leaf.dx = random.nextFloat() * 0.02f - 0.01f;
		leaf.dy = 0.036f + random.nextFloat() * 0.008f;
		leaf.landed = !falling;
	}

	@Override
	public void onDrawFrame(GL10 unused)
	{
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0.05f : Math.min(0.2f, (now - lastFrame) * 0.000000001f);
		lastFrame = now;
		for (Leaf leaf : leaves)
		{
			if (leaf.altitude > 0f)
			{
				leaf.altitude -= 0.15f * dt;
				leaf.angle += leaf.spin * 2f * dt / 0.05f;
			}
			else
			{
				if (!leaf.landed)
				{
					addDrop(leaf.x, leaf.y, 1.5f);
					leaf.spin *= 0.25f;
					leaf.landed = true;
				}
				leaf.x += leaf.dx * dt;
				leaf.y += leaf.dy * dt;
				leaf.angle += leaf.spin * dt / 0.05f;
			}
			leaf.angle %= 360f;
			float radius = leaf.scale * 0.55f;
			if (leaf.x + radius < 0f || leaf.x - radius > worldWidth || leaf.y - radius > height)
			{
				reset(leaf, true);
			}
		}
		ambientElapsed += dt;
		if (ambientElapsed >= 0.05f)
		{
			ambientElapsed %= 0.05f;
			for (Drop d : drops)
			{
				if (d.strength / d.spread < 0.005f)
				{
					Leaf leaf = leaves[random.nextInt(leaves.length)];
					addDrop(leaf.x, leaf.y, 0.1f + random.nextFloat() * 0.3f);
					break;
				}
			}
		}
		for (int i = 0; i < drops.length; i++)
		{
			Drop d = drops[i];
			d.spread += 30f * dt;
			uniforms[i * 4] = d.x;
			uniforms[i * 4 + 1] = d.y;
			uniforms[i * 4 + 2] = d.strength / d.spread * 0.12f;
			uniforms[i * 4 + 3] = d.spread;
		}
		glClear(GL_COLOR_BUFFER_BIT);
		glActiveTexture(GL_TEXTURE0);
		glDisable(GL_BLEND);
		glUseProgram(waterProgram);
		glUniform2f(waterScreen, width, height);
		glUniform1f(waterWorld, worldWidth);
		glUniform1f(waterScroll, scroll());
		glUniform4fv(waterDrops, drops.length, uniforms, 0);
		glUniform1i(waterTexture, 0);
		glBindTexture(GL_TEXTURE_2D, pond);
		mesh.position(0);
		glEnableVertexAttribArray(waterPosition);
		glVertexAttribPointer(waterPosition, 2, GL_FLOAT, false, 8, mesh);
		indices.position(0);
		glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_SHORT, indices);
		glDisableVertexAttribArray(waterPosition);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glUseProgram(leafProgram);
		glUniform2f(leafScreen, width, height);
		glUniform1i(leafTexture, 0);
		glBindTexture(GL_TEXTURE_2D, atlas);
		// Floating leaves first; falling leaves and their shadows above them.
		for (Leaf leaf : leaves) if (leaf.altitude <= 0f) drawLeaf(leaf);
		for (Leaf leaf : leaves) if (leaf.altitude > 0f) drawLeaf(leaf);
		glDisableVertexAttribArray(leafPosition);
		glDisableVertexAttribArray(leafUV);
	}

	private void drawLeaf(Leaf leaf)
	{
		float alpha = leaf.altitude >= 0.4f ? Math.max(0f, 1f - (leaf.altitude - 0.4f) / 0.1f) : 1f;
		if (alpha <= 0f) return;
		if (leaf.altitude > 0f)
		{
			glUniform4f(leafTint, 0f, 0f, 0f, alpha * 0.15f);
			leafQuad(leaf, 1f);
		}
		glUniform4f(leafTint, 1f, 1f, 1f, alpha);
		leafQuad(leaf, 2f / (2f - Math.max(0f, leaf.altitude)));
	}

	private void leafQuad(Leaf leaf, float altitudeScale)
	{
		float radius = 0.55f * leaf.scale * altitudeScale;
		float angle = (float) Math.toRadians(leaf.angle);
		float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
		quad.position(0);
		corner(leaf, -radius, -radius, c, s, leaf.sprite / 8f, 0f);
		corner(leaf, radius, -radius, c, s, (leaf.sprite + 1) / 8f, 0f);
		corner(leaf, -radius, radius, c, s, leaf.sprite / 8f, 1f);
		corner(leaf, radius, radius, c, s, (leaf.sprite + 1) / 8f, 1f);
		quad.position(0);
		glEnableVertexAttribArray(leafPosition);
		glVertexAttribPointer(leafPosition, 2, GL_FLOAT, false, 16, quad);
		quad.position(2);
		glEnableVertexAttribArray(leafUV);
		glVertexAttribPointer(leafUV, 2, GL_FLOAT, false, 16, quad);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	}

	private void corner(Leaf leaf, float x, float y, float c, float s, float u, float v)
	{
		quad.put(leaf.x - scroll() + x * c - y * s).put(leaf.y + x * s + y * c).put(u).put(v);
	}

	private static class Leaf
	{
		float x, y, scale, angle, spin, altitude, dx, dy;
		int sprite;
		boolean landed;
	}

	private static class Drop
	{
		float x, y, strength, spread = 1f;
	}

	private static FloatBuffer buffer(int count)
	{
		return ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
	}

	private int texture(int resource, boolean linear)
	{
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inScaled = false;
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;
		if (Build.VERSION.SDK_INT >= 19) options.inPremultiplied = false;
		Bitmap bitmap = BitmapFactory.decodeResource(resources, resource, options);
		if (bitmap == null) throw new IllegalStateException("Cannot decode Fall texture " + resource);
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
			throw new IllegalStateException("Fall shader: " + log);
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
			throw new IllegalStateException("Fall program: " + log);
		}
		return program;
	}

}
