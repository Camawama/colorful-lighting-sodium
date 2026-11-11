#version 330 core
// Bloom (2.11.0): one triangle over the whole target, from the vertex's number alone.
out vec2 v_Uv;

void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    v_Uv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
