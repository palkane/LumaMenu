#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <lumamenu:menu.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

#ifdef IS_GUI
// LumaMenu crosshair: GUI text in colour #FC04F8 (its shadow is #3F013E) is a 15x15 glyph that a HUD plugin sends in
// the action bar. It is drawn where the vanilla crosshair sprite would be: ((guiWidth - 15) / 2, (guiHeight - 15) / 2).
const ivec3 LUMA_CROSSHAIR = ivec3(252, 4, 248);
const ivec3 LUMA_CROSSHAIR_SHADOW = ivec3(63, 1, 62);
// Glyph quads are emitted top-left, bottom-left, bottom-right, top-right; every GUI vertex belongs to a quad.
const vec2 LUMA_CORNERS[4] = vec2[](vec2(0.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0), vec2(1.0, 0.0));
#endif

void main() {
    bool menuDisplay = false;
    #ifndef IS_GUI
    menuDisplay = luma_menu(Position);
    #endif
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    if (menuDisplay) gl_Position = luma_menu_project(Position);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    if (menuDisplay) { sphericalVertexDistance = 0.0; cylindricalVertexDistance = 0.0; }
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;

#ifdef IS_GUI
    ivec3 rgb = ivec3(round(Color.rgb * 255.0));
    if (rgb == LUMA_CROSSHAIR_SHADOW) vertexColor.a = 0.0;
    if (rgb == LUMA_CROSSHAIR) {
        // The GUI orthographic projection spans width/scale x height/scale; the game rounds those sizes up.
        vec2 gui = ceil(abs(vec2(2.0 / ProjMat[0][0], 2.0 / ProjMat[1][1])) - 0.001);
        vec2 corner = floor((gui - 15.0) / 2.0) + LUMA_CORNERS[gl_VertexIndex % 4] * 15.0;
        gl_Position = ProjMat * ModelViewMat * vec4(corner, Position.z, 1.0);
        vertexColor = vec4(1.0, 1.0, 1.0, Color.a);
    }
#endif
}
