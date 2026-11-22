#version 330
#extension GL_ARB_separate_shader_objects : require

// Can't moj_import in things used during startup, when resource packs don't exist.
// This is a copy of dynamicimports.glsl and projection.glsl
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;

// LumaMenu: a menu Dialog body is a 462-wide plain message of max(20, floor((H - 58) / 9)) rows (9 each, plus 8),
// pinned to end 15 above the screen bottom, or 33 above it under a close footer with two rows fewer (same top). Its
// focus outline is four 1-pixel white fills whose 16 corners all lie on those lines, so they turn transparent and
// gui.fsh discards them. Anything else keeps its color.
bool luma_near(float value, vec4 lines) {
    return any(lessThan(abs(vec4(value) - lines), vec4(0.01)));
}

bool luma_menu_outline(vec2 position) {
    if (ProjMat[3][3] != 1.0) return false;
    vec2 gui = ceil(abs(vec2(2.0 / ProjMat[0][0], 2.0 / ProjMat[1][1])) - 0.001);
    float rows = max(20.0, floor((gui.y - 58.0) / 9.0));
    float left = max(10.0, floor((gui.x - 462.0) / 2.0));
    float top = gui.y - 23.0 - 9.0 * rows;
    return luma_near(position.x, vec4(left, left + 1.0, left + 461.0, left + 462.0))
        && (luma_near(position.y, vec4(top, top + 1.0, gui.y - 16.0, gui.y - 15.0))
            || luma_near(position.y, vec4(top, top + 1.0, gui.y - 34.0, gui.y - 33.0)));
}

void main() {
    vec4 position = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * position;

    vertexColor = Color;
    if (all(greaterThan(Color, vec4(0.999))) && luma_menu_outline(position.xy)) vertexColor.a = 0.0;
}
