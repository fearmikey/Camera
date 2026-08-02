#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

uniform samplerExternalOES uTexture;
uniform vec2 uTexelSize;

in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 offset = uTexelSize;

    vec4 c0 = texture(uTexture, vTexCoord + vec2(-offset.x, -offset.y));
    vec4 c1 = texture(uTexture, vTexCoord + vec2(offset.x, -offset.y));
    vec4 c2 = texture(uTexture, vTexCoord + vec2(-offset.x, offset.y));
    vec4 c3 = texture(uTexture, vTexCoord + vec2(offset.x, offset.y));

    vec4 binned = (c0 + c1 + c2 + c3) * 0.25;
    vec4 center = texture(uTexture, vTexCoord);

    float sharpenAmount = 0.6;
    outColor = binned + (center - binned) * sharpenAmount;
    outColor.a = 1.0;
}
