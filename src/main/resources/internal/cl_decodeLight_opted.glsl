vec3 cl_tint = vec3(1.0);

vec4 cl_decodeLight(vec4 cl_raw) {
    float cl_x = cl_raw.x;
    float cl_y = cl_raw.y;
    if (cl_x < 0.0) cl_x += 65536.0;
    if (cl_y < 0.0) cl_y += 65536.0;
    if (cl_y < 61440.0) {
        cl_tint = vec3(1.0);
        return cl_raw;
    }

    // arbitrary floating point magic?
    float cl_sky4 = mod(cl_y, 16.0);
    float cl_b = floor(mod(cl_y, 4096.0) * 0.0625);
    float cl_g = floor(cl_x * 0.00390625);
    float cl_r = cl_x - cl_g * 256.0;
    float cl_m = max(cl_r, max(cl_g, cl_b));

    cl_tint = vec3(cl_r, cl_g, cl_b) / 256.0;

    return vec4(cl_m * 0.94117647 + 8.0, cl_sky4 * 15 + 0.5, cl_raw.z, cl_raw.w);
}
