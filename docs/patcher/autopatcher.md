# Autopatcher

Colorful lighting comes with an auto-patcher that will try to make shaders work with colorful lighting.

This works on a very small group of shaders, only on shaders that sample the builtin lightmap, and do not do much additional coloring to the light color.

This auto patcher will be disabled and shaders will reload when running `/cl off`

It will also be re-enabled and reload shaders when running `/cl on`

If the shader you are using does not work with colorful lighting, or has some buggy behavior, you can try using `/cl patchshader "shader name"` or use `/cl off` to load the original shader

Using `/cl patchshader "shader name"` will generally produce a shader that has colored lighting, but the colors will typically look off

# Technical Details

## Compat Status 1

Compat status 1 is the default compat status, if you do not specify one, this is what your shader is assumed to be

### Fragment Shader

Patches:
```glsl
// locate the following line; if it is not there, we give up
uniform sampler2D lightmap;
```

If it is there, look for either `texture(lightmap, *)` or `texture2D(lightmap, *)` and replace it with `colorful_lighting_blendLight(lightmap, *)`

From there, parse `internal/cl_blendLight.glsl`. Functions from that file get inserted after the lightmap is defined in the shader, everything else gets inserted before the first function.

### Vertex Shader

Patches:
Locate a reference to gl_MultiTexCoord1. If that is not found, we give up.

From there, parse `internal/cl_decodeLight.glsl`. Everything from this files before the first function in the shader.

Replace all references to `gl_MultiTexCoord1` with `colorful_lighting_decodeLight(gl_MultiTexCoord1)`.

## Shader specific patching

Colorful lighting also comes with auto-patchers for a couple of common shaders

The list of auto patchers may be expanded in the future, but the hope is truthfully for it to become redundant and only used for legacy versions of shaders

Currently the list is
- Complementary
- BSL

These are pretty minimal patches, which aren't always going to work with every configuration, and may have bugs

I could not successfully get the auto patcher to work on deferred shaders

## Complementary patcher

```properties
colorful_lighting.patcher_family=Complementary
```

Known bugs:
- with spooklementary, while advanced color tracing is on, sky light only functions in close proximity to colored lights
- Complementary's builtin dynamic lighting seems to get suppressed and instead just end up scaling the value vectors of the colored lighting
  - I'm not *exactly* sure if this is actually what's happening, but dynamic lights definitely behave weird
  - I'm probably gonna want to add in some helpers for dynamic lighting; let me know if you have pointers on how I should best do this

### Fragment Shader

Patches:
Rename any variable called `lightmap` to `complementary_lightmap`

```glsl
// After either of the following lines:
flat out vec2 lmCoord;
out vec2 lmCoord;
// Add
varying vec3 cl_lighting_value;
uniform sampler2D lightmap;
```

IF the above occurred AND the shader references `lmCoord` at any point, then replace `blocklightCol` with `cl_lighting_value`

### Vertex

Patches:
```glsl
// After either of the following lines:
flat out vec2 lmCoord;
out vec2 lmCoord;
// Add
varying vec3 cl_lighting_value;
```

IF a above occurred, locate any call to `GetLightMapCoordinates` (complementary only has one call to this method)

After the line which makes a call to `GetLightMapCoordinates`, inject `cl_lighting_value = colorful_lighting_color;`


## BSL patcher

```properties
colorful_lighting.patcher_family=BSL
```

The BSL auto patcher is a modification of the Complementary auto patcher, as the two shaders have very similar targets

I do not know of anything that would classify as a "bug" for this one

### Fragment Shader

Patches:
Rename any variable called `lightmap` to `bsl_lightmap`

```glsl
// After either of the following lines:
flat out vec2 lmCoord;
out vec2 lmCoord;
// Add
varying vec3 cl_lighting_value;
uniform sampler2D lightmap;
```

IF the above occurred AND the shader references `lmCoord` at any point, then replace `blocklightCol` with `(cl_lighting_value * 1.5)`

I'm not actually sure why I have to multiply by 1.5, but the colors are dark if I don't

### Vertex

Patches:
```glsl
// After either of the following lines:
flat out vec2 lmCoord;
out vec2 lmCoord;
// Add
varying vec3 cl_lighting_value;
```

IF the above occurred, locate any assignment to `lmCoord`

After *a* line which makes a call to `GetLightMapCoordinates`, inject `cl_lighting_value = colorful_lighting_color;`

The line is chosen arbitrarily based off whichever one iris/oculus' shader patcher returns first.

It doesn't matter which one it injects after, the result is the same.
