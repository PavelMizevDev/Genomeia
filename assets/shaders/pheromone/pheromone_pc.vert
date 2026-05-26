#version 320 es
precision highp float;

layout (location = 0) in vec2 a_position;

uniform mat4 u_projTrans;
uniform float u_K;
uniform float u_P;

// Структура точно соответствует тому, что ты кладёшь в буфер
struct Pheromone {
    vec2 pos;     // x, y
    float A;      // A (интенсивность)
    uint  color;  // цвет как uint (точно как putInt)
};

layout(std430, binding = 1) buffer PheromoneData {
    Pheromone pheromones[];
} pheromoneBuffer;

out vec2 v_localUV;
out float v_A;
out float v_radius;
flat out vec3 ex_Color;

void main() {
    Pheromone ph = pheromoneBuffer.pheromones[gl_InstanceID];

    vec2 worldPos = ph.pos;
    v_A = ph.A;

    float squaredRadius = max((v_A / u_P - 1.0) / u_K, 0.0);
    float radius = sqrt(squaredRadius);

    // ← Теперь без всяких floatBitsToUint!
    ex_Color = unpackUnorm4x8(ph.color).rgb;

    vec2 offset = a_position * radius;

    gl_Position = u_projTrans * vec4(worldPos + offset, 0.0, 1.0);

    v_localUV = a_position;
    v_radius  = squaredRadius;
}
