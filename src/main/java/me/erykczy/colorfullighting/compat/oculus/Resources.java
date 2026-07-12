package me.erykczy.colorfullighting.compat.oculus;

import java.io.IOException;
import java.io.InputStream;

public class Resources {
	public static final String DECODE_LIGHT_INTERNAL;
	public static final String DECODE_LIGHT_FULL;
	public static final String BLEND_LIGHT_INTERNAL;
	public static final String BLEND_LIGHT_FULL;
	
	public static final String LIGHT_DATA_FULL;
	
	public static String readStream(InputStream is) throws IOException {
		String str = new String(is.readAllBytes());
		try {
			is.close();
		} catch (Throwable ignored) {
		}
		return str;
	}
	
	static {
		try {
			ClassLoader cl = Resources.class.getClassLoader();
			DECODE_LIGHT_INTERNAL = readStream(cl.getResourceAsStream("internal/cl_decodeLight.glsl"));
			DECODE_LIGHT_FULL = readStream(cl.getResourceAsStream("internal/cl_decodeLight_opted.glsl"));
			BLEND_LIGHT_INTERNAL = readStream(cl.getResourceAsStream("internal/cl_blendLight.glsl"));
			BLEND_LIGHT_FULL = readStream(cl.getResourceAsStream("internal/cl_blendLight_opted.glsl"));
			LIGHT_DATA_FULL = readStream(cl.getResourceAsStream("internal/cl_light_data.glsl"));
		} catch (Throwable err) {
			throw new RuntimeException(err);
		}
	}
}
