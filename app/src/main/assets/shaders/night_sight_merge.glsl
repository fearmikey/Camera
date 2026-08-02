#version 300 es
precision highp float;

layout(location = 0) out vec4 outColor;

uniform sampler2D uAccumulator;
uniform sampler2D uCurrentFrame;
uniform mat3 uHomography;
uniform float uWeight;

in vec2 vTexCoord;

void main() {
    // 1. Warp coordinates using inverse homography to fetch from current frame
    // In practice, we often pass the inverse homography from CPU
    vec3 warpedCoord = uHomography * vec3(vTexCoord, 1.0);
    vec2 targetCoord = warpedCoord.xy / warpedCoord.z;

    vec4 acc = texture(uAccumulator, vTexCoord);

    // Check if warped coordinates are within bounds
    if (targetCoord.x < 0.0 || targetCoord.x > 1.0 || targetCoord.y < 0.0 || targetCoord.y > 1.0) {
        outColor = acc;
        return;
    }

    vec4 curr = texture(uCurrentFrame, targetCoord);

    // 2. Weighted average (Streaming Merge)
    // Formula: NewAvg = (1 - Weight) * OldAvg + Weight * NewFrame
    outColor = mix(acc, curr, uWeight);
}
