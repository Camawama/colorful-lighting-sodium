# Implementing support
## Compat status
You can set a compat status based upon how you want your shader to work with colorful lighting

If your shader **does not** or **will not** work with colorful lighting, a compat status of 0 disable colorful lighting
```properties
colorful_lighting.compat_status=0
```

For simple shaders, you can add the following snippet to your shaders.properties, and the generic patcher will do the rest (no shader specific patches)
```properties
colorful_lighting.compat_status=2
```

If you do not like how this looks or it does not work for your shader, you can instead add
```properties
colorful_lighting.compat_status=3
```
This tells the patcher that you will be implementing pieces of colorful lighting support yourself

The default compat status is 1, which will run both the generic patcher and apply shader specific patches


## Vertex modifications
In order for your shader to work with this setting, in your vertex shader you must call `cl_decodeLight` at least once with `gl_MultiTexCoord1` as the parameter
You **also** must use the result of cl_decodeLight if you are using the block light brightness instead of `gl_MultiTexCoord1`, as multiTexCoord1 contains the colored lighting data instead of a usable lighting value

from there, `cl_tint` is calculated based on `gl_MultiTexCoord1`
simply add a new varying
```glsl
varying vec3 cl_lighting_color; // can have any name you want
```
and set its value to `cl_tint` somewhere after calling cl_decodeLight
```glsl
cl_lighting_color.rgb = cl_tint;
```

## Fragment modifications
The fragment shader is even simpler

for a basic support implementation, simply add your varying
```glsl
varying vec3 cl_lighting_color;
```
and a method like so
```glsl
vec4 sampleLightmap(sampler2D lm, vec2 lmcoord) {
    #ifdef COLORFUL_LIGHTING_MOD_PRESENT
    return cl_blendLight(lm, lmcoord, cl_lighting_color);
    #else
    return texture2D(lm, lmcoord);
    #endif
}
```
and use that instead of texture2D

These fragment and vertex modifications are equivalent to compat status 2
From here, you can modify the shader however you want, including using custom colored light sampling logic if you want

To see the internal light sampling logic [link to internal iris compat code]

# For more advanced usage
```properties
colorful_lighting.compat_status=4
```
a compat status of 4 will disable all internal patches from colorful lighting, allowing you to implement support entirely on your own, with no helper methods

The `COLORFUL_LIGHTING_MOD_PRESENT` define can still be used to detect if colorful lighting is installed, everything else is up to you
