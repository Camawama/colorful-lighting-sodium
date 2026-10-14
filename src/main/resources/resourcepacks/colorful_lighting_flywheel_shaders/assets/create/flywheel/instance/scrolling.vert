#include "flywheel:util/quaternion.glsl"
#include "flywheel:util/matrix.glsl"
// START colorful lighting
#include "colorful_lighting:colored_light.glsl"
// END colorful lighting

void flw_instanceVertex(in FlwInstance instance) {
    flw_vertexPos = vec4(rotateByQuaternion(flw_vertexPos.xyz - .5, instance.rotation) + instance.pos + .5, 1.);

    flw_vertexNormal = rotateByQuaternion(flw_vertexNormal, instance.rotation);

    vec2 scroll = fract(instance.speed * flw_renderTicks + instance.offset) * instance.scale;

    flw_vertexTexCoord = flw_vertexTexCoord + instance.diff + scroll;
    flw_vertexOverlay = instance.overlay;
    flw_vertexLight = max(cl_vanillaVertexLight(ivec2(instance.light)), flw_vertexLight); // colorful lighting: packed light

    // START colorful lighting
    vec4 vertexPos = flw_vertexPos;
    #ifdef FLW_EMBEDDED
    vertexPos = modelMatrix * vertexPos;
    #endif
    v_lightColor.data = vertexLightColor(ivec2(instance.light), vertexPos.xyz + flw_renderOrigin.xyz);
    // END colorful lighting
}