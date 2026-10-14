#include "flywheel:util/quaternion.glsl"
// START colorful lighting
#include "colorful_lighting:colored_light.glsl"
// END colorful lighting

void flw_instanceVertex(in FlwInstance i) {
    flw_vertexPos = vec4(rotateByQuaternion(flw_vertexPos.xyz - i.pivot, i.rotation) + i.pivot + i.position, 1.0);
    flw_vertexNormal = rotateByQuaternion(flw_vertexNormal, i.rotation);
    flw_vertexColor *= i.color;
    flw_vertexOverlay = i.overlay;
    // Some drivers have a bug where uint over float division is invalid, so use an explicit cast.
    flw_vertexLight = max(cl_vanillaVertexLight(ivec2(i.light)), flw_vertexLight); // colorful lighting: packed light

    // START colorful lighting
    vec4 vertexPos = flw_vertexPos;
    #ifdef FLW_EMBEDDED
    vertexPos = modelMatrix * vertexPos;
    #endif
    v_lightColor.data = vertexLightColor(ivec2(i.light), vertexPos.xyz + flw_renderOrigin.xyz);
    // END colorful lighting
}