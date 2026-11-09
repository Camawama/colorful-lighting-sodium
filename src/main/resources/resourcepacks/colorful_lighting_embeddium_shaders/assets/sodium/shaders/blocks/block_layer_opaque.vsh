#version 330 core

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

out float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif

#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;

uniform sampler2D u_LightTex; // The light map texture sampler

#ifdef COLORFUL_LIGHTING_MOD_PRESENT
#import <sodium:include/light.glsl>
#else
#import <sodium:include/light_default.glsl>
#endif

uvec3 _get_relative_chunk_coord(uint pos) {
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

#if defined(COLORFUL_LIGHTING_MOD_PRESENT) && defined(CL_DYNAMIC_LIGHTS)
    // the vertex relative to the camera along the world's axes: through eye space, so a Valkyrien Skies ship drawn in its
    // own frame lands where it is in the world
    vec3 cl_wrel = cl_dynInvView * (u_ModelViewMatrix * vec4(position, 1.0)).xyz;
    // the vertex's face (in its light's top nibble) turned the same way, so a ship's faces point where the ship does
    mat3 cl_toWorld = cl_dynInvView * mat3(u_ModelViewMatrix);
    v_Color = _vert_color * _sample_lightmap_dyn(u_LightTex, _vert_tex_light_coord, cl_wrel, cl_toWorld);
#else
    v_Color = _vert_color * _sample_lightmap(u_LightTex, _vert_tex_light_coord);
#endif
    v_TexCoord = _vert_tex_diffuse_coord;

    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}