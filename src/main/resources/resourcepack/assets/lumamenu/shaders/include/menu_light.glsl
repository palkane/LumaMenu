// Inventory lighting for enhanced-mode LumaMenu models. The vertex shader stores the displayed view position in the
// overlay varying with w = -2; other draws keep w >= 0. The face normal comes from screen derivatives of that position
// (the vertex normal belongs to the encoded, not the displayed, geometry).

// 26.3 Lighting.ITEMS_3D directions in view space (y up, z toward the viewer).
const vec3 LUMA_LIGHT0 = vec3(-0.933439, 0.262695, -0.244300);
const vec3 LUMA_LIGHT1 = vec3(-0.103571, 0.976607, 0.188446);

bool luma_menu_fragment(vec4 menu) {
    return menu.w < -0.5;
}

float luma_menu_shade(vec4 menu) {
    vec3 normal = cross(dFdx(menu.xyz), dFdy(menu.xyz));
    if (!luma_menu_fragment(menu) || dot(normal, normal) == 0.0) return 1.0;
    // Visible faces face the viewer.
    if (normal.z < 0.0) normal = -normal;
    normal = normalize(normal);
    // Flat items (ITEMS_FLAT in the inventory) face the screen fully lit.
    if (normal.z > 0.99) return 1.0;
    vec2 light = max(vec2(0.0), vec2(dot(LUMA_LIGHT0, normal), dot(LUMA_LIGHT1, normal)));
    return min(1.0, (light.x + light.y) * 0.6 + 0.4);
}
