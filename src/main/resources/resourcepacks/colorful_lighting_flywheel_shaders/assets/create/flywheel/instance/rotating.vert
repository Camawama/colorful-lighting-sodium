#include "flywheel:util/quaternion.glsl"
// START colorful lighting
#include "colorful_lighting:colored_light.glsl"
// END colorful lighting

void flw_instanceVertex(in FlwInstance instance) {
    float degrees = instance.offset + flw_renderSeconds * instance.speed;

    vec4 kineticRot = quaternionDegrees(instance.axis, degrees);
    vec3 rotated = rotateByQuaternion(flw_vertexPos.xyz - .5, instance.rotation);

    flw_vertexPos.xyz = rotateByQuaternion(rotated, kineticRot) + instance.pos + .5;
    flw_vertexNormal = rotateByQuaternion(rotateByQuaternion(flw_vertexNormal, instance.rotation), kineticRot);
    flw_vertexColor *= instance.color;
    flw_vertexLight = max(vec2(instance.light) / 256., flw_vertexLight);
    flw_vertexOverlay = instance.overlay;

    // START colorful lighting
    vec4 vertexPos = flw_vertexPos;
    #ifdef FLW_EMBEDDED
    vertexPos = modelMatrix * vertexPos;
    #endif
    v_lightColor.data = vertexLightColor(ivec2(instance.light), vertexPos.xyz + flw_renderOrigin.xyz);
    // END colorful lighting
}