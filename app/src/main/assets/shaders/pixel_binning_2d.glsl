#version 300 es
precision highp float;

uniform sampler2D uTexture;
uniform vec2 uTexelSize;

in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 offset = uTexelSize;

    // Sample the 4 centers of the 2x2 sub-blocks (Averaging 16 pixels total via GL_LINEAR)
    vec4 c0 = texture(uTexture, vTexCoord + vec2(-offset.x, -offset.y));
    vec4 c1 = texture(uTexture, vTexCoord + vec2(offset.x, -offset.y));
    vec4 c2 = texture(uTexture, vTexCoord + vec2(-offset.x, offset.y));
    vec4 c3 = texture(uTexture, vTexCoord + vec2(offset.x, offset.y));

    // Base binned (averaged) color
    vec4 binned = (c0 + c1 + c2 + c3) * 0.25;

    // High-frequency detail sampling (the very center of the 4x4 block)
    vec4 center = texture(uTexture, vTexCoord);

    // Unsharp Mask Logic:
    // Sharpened = Binned + (Center - Binned) * Amount
    // Amount 0.6 provides a clean, natural boost to edge contrast without halos.
    float sharpenAmount = 0.6;
    outColor = binned + (center - binned) * sharpenAmount;

    // Ensure alpha is preserved
    outColor.a = 1.0;
}
