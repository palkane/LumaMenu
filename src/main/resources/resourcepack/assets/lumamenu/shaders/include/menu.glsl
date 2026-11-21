// Reserved by LumaMenu: view-space Z 2048..4194304 in perspective world draws.
// Other plugins must not encode no-culling displays into this same range.
const float LUMA_MENU_SCALE = -65536.0;
// Enhanced-mode ItemDisplays with this brightness override (block 14, sky 15) are lit like the inventory
// (DisplayRenderer.LIT_LIGHT). Compatible mode never depends on these shaders: shader packs replace them.
const ivec2 LUMA_LIT_LIGHT = ivec2(14 << 4, 15 << 4);

bool luma_menu(vec3 position) {
    if (ProjMat[3][3] != 0.0) return false;
    float behind = (ModelViewMat * vec4(position, 1.0)).z;
    return behind >= 2048.0 && behind <= 4194304.0;
}

bool luma_lit(ivec2 light) {
    return light == LUMA_LIT_LIGHT;
}

vec4 luma_menu_project(vec3 position) {
    vec3 view = (ModelViewMat * vec4(position, 1.0)).xyz / LUMA_MENU_SCALE;
    // 26.3 uses reversed depth. Keep menu layers in front of the world on either backend.
    float depth = 1.0 + view.z / 128.0;
    #ifndef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    depth = depth * 2.0 - 1.0;
    #endif
    // Orthographic camera plane, current FOV retained.
    return vec4(ProjMat[0][0] * view.x, ProjMat[1][1] * view.y,
                depth, 1.0);
}

// Displayed view position for the fragment's face normal (see menu_light.glsl), carried in the overlay varying.
vec4 luma_menu_view(vec3 position) {
    return vec4((ModelViewMat * vec4(position, 1.0)).xyz / LUMA_MENU_SCALE, -2.0);
}
