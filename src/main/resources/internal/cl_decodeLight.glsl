varying vec3 colorful_lighting_color;

vec4 colorful_lighting_decodeLight(vec4 cl_raw) {
    ivec4 uv = ivec4(cl_raw);

    int mostSignificantShort = uv.y;
    int alpha4 = (mostSignificantShort >> 12) & 0xF;
    if(alpha4 != 0xF) {
        colorful_lighting_color = vec3(1.0);
        return cl_raw;
    }

    int leastSignificantShort = uv.x;
    int red8 = (leastSignificantShort >> 0) & 0xFF;
    int green8 = (leastSignificantShort >> 8) & 0xFF;
    int skyLight4 = (mostSignificantShort >> 0) & 0xF;
    int blue8 = (mostSignificantShort >> 4) & 0xFF;

    float cl_m = max(red8, max(green8, blue8));
    colorful_lighting_color = vec3(red8, green8, blue8) / 256.0;

    return vec4(cl_m * 0.94117647 + 8.0, skyLight4 * 15 + 0.5, cl_raw.z, cl_raw.w);
}
