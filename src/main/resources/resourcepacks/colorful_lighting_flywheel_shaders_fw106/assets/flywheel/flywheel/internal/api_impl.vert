#include "flywheel:internal/material.glsl"
#include "flywheel:internal/api_impl.glsl"
#include "flywheel:internal/uniforms/uniforms.glsl"

out vec4 flw_vertexPos;
out vec4 flw_vertexColor;
out vec2 flw_vertexTexCoord;
flat out ivec2 flw_vertexOverlay;
out vec2 flw_vertexLight;
out vec3 flw_vertexNormal;

out float flw_distance;

// START colorful lighting
#include "colorful_lighting:colored_light_types.glsl"

out VertexLightData {
    ColoredLightFloatData data;
} v_lightColor;

#ifdef FLW_EMBEDDED
// the contraption's model matrix, set at the top of _flw_main (common.vert) for instance shaders that need world
// positions before _flw_main applies it; declared here, ahead of every instance shader, so that every instance
// type compiles in the embedded (contraption) context, not only the ones that read it
mat4 modelMatrix;
#endif
// END colorful lighting

FlwMaterial flw_material;

uint flw_vertexId;
