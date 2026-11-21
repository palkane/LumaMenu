"""Actual 26.3 shader linkage/transform feedback. No Minecraft client control."""
from array import array
import argparse
import ctypes
import itertools
import json
import math
import os
from pathlib import Path
import re
import struct
from zipfile import ZipFile

import moderngl

ROOT = Path(__file__).resolve().parents[3]
OUTPUT = ROOT / "build/native-evidence"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--pack", type=Path, default=OUTPUT / "pack",
                    help="Export directory or merged ZIP; resolves active 26.3 overlays.")
parser.add_argument("--result", type=Path, default=OUTPUT / "enhanced-gpu-result.json")
parser.add_argument("--raster-only", action="store_true", help="Check actual fragment visibility and depth ordering.")
args = parser.parse_args()
PACK = args.pack
PACK_ZIP = ZipFile(PACK) if PACK.is_file() else None
if PACK_ZIP:
    entries = json.loads(PACK_ZIP.read("pack.mcmeta")).get("overlays", {}).get("entries", [])
    OVERLAYS = [e["directory"] for e in entries
                if tuple(e["min_format"]) <= (97, 1) <= tuple(e["max_format"])]
else:
    OVERLAYS = []
CLIENT = ZipFile(os.environ["LUMAMENU_CLIENT_JAR"])
CTX = moderngl.create_standalone_context(backend="egl", require=430)
CTX.simple_framebuffer((1, 1)).use()
IDENTITY = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
INCLUDE = re.compile(r"^\s*#(?:include|moj_import) <([^>]+)>", re.MULTILINE)
BUFFERS = {
    "DynamicTransforms": CTX.buffer(array("f", IDENTITY * 2 + [1] * 4 + [0] * 4)),
    "Projection": CTX.buffer(array("f", IDENTITY)),
    "Lighting": CTX.buffer(array("f", [0, 1, 0, 0, 1, 0, 0, 0])),
    "Fog": CTX.buffer(array("f", [0] * 4 + [1000, 2000, 1000, 2000, 2000, 2000, 0, 0])),
    "Globals": CTX.buffer(struct.pack("3if", 0, 0, 0, .25) + struct.pack("8f", 0, 0, 0, 0, 32, 32, 0, 0)),
}
LIGHTMAP = CTX.texture((16, 16), 4, bytes([255] * 1024))
OVERLAY = CTX.texture((16, 16), 4, bytes([0, 0, 0, 255] * 256))
LIGHTMAP.use(1)
OVERLAY.use(2)
COLOR = (.8, .6, 1., 1.)
NORMALS = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))


def source(path, vanilla=False):
    if not vanilla and PACK_ZIP:
        effective = path
        for overlay in OVERLAYS:
            candidate = f"{overlay}/{path}"
            if candidate in PACK_ZIP.namelist():
                effective = candidate
        if effective in PACK_ZIP.namelist():
            return PACK_ZIP.read(effective).decode()
    target = PACK / path
    if not vanilla and target.is_file():
        return target.read_text()
    return CLIENT.read(path).decode()


def expand(text, vanilla=False):
    def imported(match):
        namespace, name = match[1].split(":", 1)
        return expand(source(f"assets/{namespace}/shaders/include/{name}", vanilla), vanilla)
    return INCLUDE.sub(imported, text)


def stage(name, ext, defines, vanilla=False, fault=None, capture=False):
    text = source(f"assets/minecraft/shaders/core/{name}.{ext}", vanilla)
    if fault == "perspective":
        text = text.replace("if (menuDisplay) gl_Position = luma_menu_project(Position);", "")
    if fault == "global_light":
        text = text.replace("menuDisplay ? Color : minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color)",
                            "Color")
    if capture:
        text = text.replace("void main() {", "out vec4 lumaProbe;\nvoid main() {")
        end = text.rfind("}")
        text = text[:end] + "\n    lumaProbe = gl_Position;\n" + text[end:]
    # The pack targets shaderc/SPIR-V (gl_VertexIndex); this OpenGL harness spells it gl_VertexID.
    text = expand(text, vanilla).replace("#version 330", "#version 430").replace("gl_VertexIndex", "gl_VertexID")
    offset = text.index("\n") + 1
    while text[offset:].startswith("#extension"):
        offset = text.index("\n", offset) + 1
    return text[:offset] + defines + text[offset:]


def program(name, defines="", vanilla=False, fault=None):
    per_face = "PER_FACE_LIGHTING" in defines
    varyings = ["lumaProbe"] + ([] if name == "glint" else
        ["vertexPerFaceColorBack", "vertexPerFaceColorFront"] if per_face else ["vertexColor"])
    shader = CTX.program(vertex_shader=stage(name, "vsh", defines, vanilla, fault, True), varyings=varyings)
    for binding, (key, buffer) in enumerate(BUFFERS.items()):
        if key in shader:
            shader[key].binding = binding
            buffer.bind_to_uniform_block(binding)
    for key, unit in (("Sampler1", 2), ("Sampler2", 1)):
        if key in shader:
            shader[key].value = unit
    return shader, 4 if name == "glint" else 12 if per_face else 8


def draw(pipeline, vertices, normal=(0, -1, 0), fov=70, ortho=False, zero_to_one=False):
    shader, width = pipeline
    focal = 1 / math.tan(math.radians(fov / 2))
    near, far = .05, 1000
    z = near / (far - near) if zero_to_one else (far + near) / (far - near)
    offset = far * near / (far - near) * (1 if zero_to_one else 2)
    projection = IDENTITY if ortho else [focal, 0, 0, 0, 0, focal, 0, 0, 0, 0, z, -1, 0, 0, offset, 0]
    BUFFERS["Projection"].write(array("f", projection))
    count = len(vertices)
    values = {
        "Position": ("3f", array("f", (v for xyz in vertices for v in xyz))),
        "Color": ("4f", array("f", COLOR * count)),
        "Normal": ("3f", array("f", normal * count)),
        "UV0": ("2f", array("f", [0] * (count * 2))),
        "UV3": ("2f", array("f", [0] * (count * 2))),
        "UV1": ("2i", struct.pack(f"{count * 2}i", *([0] * (count * 2)))),
        "UV2": ("2i", struct.pack(f"{count * 2}i", *([240] * (count * 2)))),
    }
    inputs = [(CTX.buffer(data), fmt, name) for name, (fmt, data) in values.items() if name in shader]
    vao = CTX.vertex_array(shader, inputs)
    target = CTX.buffer(reserve=count * width * 4)
    vao.transform(target, mode=moderngl.POINTS, vertices=count)
    floats = array("f", target.read())
    result = [tuple(floats[i:i + width]) for i in range(0, len(floats), width)]
    target.release()
    vao.release()
    for buffer, _, _ in inputs:
        buffer.release()
    return result


def difference(actual, expected):
    return max(abs(a - b) for row, want in zip(actual, expected) for a, b in zip(row, want))


def raster_checks():
    from PIL import Image

    # The actual 26.3 Projection swaps near/far and GlDevice enables clip control when available.
    clip_control = ctypes.CDLL("libGL.so.1").glClipControl
    clip_control.argtypes = (ctypes.c_uint, ctypes.c_uint)
    white = CTX.texture((1, 1), 4, bytes([255] * 4))
    target = CTX.framebuffer([CTX.texture((64, 64), 4)], CTX.depth_renderbuffer((64, 64)))
    checks = 0
    try:
        for zero_to_one in (True, False):
            clip_control(0x8CA1, 0x935F if zero_to_one else 0x935E)  # LOWER_LEFT; ZERO_TO_ONE / NEGATIVE_ONE_TO_ONE
            defines = "#define RENDERPEARL_DEPTH_IS_ZERO_TO_ONE\n" if zero_to_one else ""
            near, far = .05, 1000
            focal = 1 / math.tan(math.radians(70 / 2))
            z = near / (far - near) if zero_to_one else (far + near) / (far - near)
            offset = far * near / (far - near) * (1 if zero_to_one else 2)
            BUFFERS["Projection"].write(array("f", [focal, 0, 0, 0, 0, focal, 0, 0,
                                                   0, 0, z, -1, 0, 0, offset, 0]))
            white.use(0)
            LIGHTMAP.use(1)
            OVERLAY.use(2)
            for name in ("position_color", "text", "item", "entity"):
                shader = CTX.program(vertex_shader=stage(name, "vsh", defines),
                                     fragment_shader=stage(name, "fsh", defines))
                for binding, (key, buffer) in enumerate(BUFFERS.items()):
                    if key in shader:
                        shader[key].binding = binding
                        buffer.bind_to_uniform_block(binding)
                for key, unit in (("Sampler0", 0), ("Sampler1", 2), ("Sampler2", 1)):
                    if key in shader:
                        shader[key].value = unit

                def quad(color, depth, menu=True):
                    white.use(0)
                    LIGHTMAP.use(1)
                    OVERLAY.use(2)
                    vertices = [(x / focal, y / focal, -depth)
                                for x, y in ((-.7, -.7), (.7, -.7), (.7, .7),
                                             (-.7, -.7), (.7, .7), (-.7, .7))]
                    if menu:
                        vertices = [tuple(v * -65536 for v in xyz) for xyz in vertices]
                    values = {
                        "Position": ("3f", array("f", (v for xyz in vertices for v in xyz))),
                        "Color": ("4f", array("f", color * 6)),
                        "Normal": ("3f", array("f", [0, 1, 0] * 6)),
                        "UV0": ("2f", array("f", [0] * 12)),
                        "UV1": ("2i", struct.pack("12i", *([0] * 12))),
                        "UV2": ("2i", struct.pack("12i", *([240] * 12))),
                    }
                    inputs = [(CTX.buffer(data), fmt, key) for key, (fmt, data) in values.items() if key in shader]
                    vao = CTX.vertex_array(shader, inputs)
                    vao.render(moderngl.TRIANGLES, vertices=6)
                    vao.release()
                    for buffer, _, _ in inputs:
                        buffer.release()

                target.use()
                CTX.enable_only(moderngl.DEPTH_TEST)
                CTX.depth_func = ">="
                # A world surface must not hide the menu; front menu layers must win in either draw order.
                for front_first in (False, True):
                    target.clear(0, 0, 0, 1, depth=0)
                    # Native position_color world shader, without menu marker or directional lighting.
                    world = shader
                    shader = CTX.program(vertex_shader=stage("position_color", "vsh", defines, True),
                                         fragment_shader=stage("position_color", "fsh", defines, True))
                    for binding, (key, buffer) in enumerate(BUFFERS.items()):
                        if key in shader:
                            shader[key].binding = binding
                            buffer.bind_to_uniform_block(binding)
                    quad((0, 0, 1, 1), 1, menu=False)
                    shader.release()
                    shader = world
                    for color, depth in (((1, 0, 0, 1), 1), ((0, 1, 0, 1), 8)) if front_first else (
                            ((0, 1, 0, 1), 8), ((1, 0, 0, 1), 1)):
                        quad(color, depth)
                    image = Image.frombytes("RGB", target.size, target.read(components=3))
                    image_path = args.result.with_name(
                        f"{args.result.stem}-{name}-{'zero-one' if zero_to_one else 'legacy'}-{front_first}.png")
                    image.save(image_path)
                    center = image.getpixel((32, 32))
                    assert center == (255, 0, 0), (
                        "Enhanced menu invisible or behind world/back layer", name, zero_to_one, front_first, center, image_path)
                    assert image.getpixel((0, 0)) == (0, 0, 0), "Menu quad escaped its screen bounds"
                    checks += 1
                shader.release()
    finally:
        clip_control(0x8CA1, 0x935E)
        CTX.disable(moderngl.DEPTH_TEST)
        white.release()
        target.release()
        OVERLAY.use(2)
    return checks


def rotation(ax, ay):
    """Quaternionf.rotationXYZ(ax, ay, 0) as rows: ItemTransform's GUI rotation."""
    cx, sx, cy, sy = math.cos(ax), math.sin(ax), math.cos(ay), math.sin(ay)
    rx = ((1, 0, 0), (0, cx, -sx), (0, sx, cx))
    ry = ((cy, 0, sy), (0, 1, 0), (-sy, 0, cy))
    return tuple(tuple(sum(rx[i][k] * ry[k][j] for k in range(3)) for j in range(3)) for i in range(3))


def apply(m, v):
    return tuple(sum(m[i][k] * v[k] for k in range(3)) for i in range(3))


def inventory_light(normal):
    """Vanilla inventory: Lighting.ITEMS_3D in y-up view space, light.glsl power 0.6 and ambient 0.4."""
    def unit(v):
        length = math.sqrt(sum(x * x for x in v))
        return tuple(x / length for x in v)

    def ry(a):
        return ((math.cos(a), 0, math.sin(a)), (0, 1, 0), (-math.sin(a), 0, math.cos(a)))

    def rx(a):
        return ((1, 0, 0), (0, math.cos(a), -math.sin(a)), (0, math.sin(a), math.cos(a)))

    def mul(a, b):
        return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)) for i in range(3))
    # scaling(1,-1,1).rotateYXZ(...) lights meet GUI normals that the pose's scale(16,-16,16) flipped the same way.
    lights = mul(mul(ry(1.0821041), rx(3.2375858)), mul(ry(-0.3926991), rx(2.3561945)))
    total = sum(max(0, sum(a * b for a, b in zip(apply(lights, unit(d)), normal)))
                for d in ((.2, 1, -.7), (-.2, 1, .7)))
    return min(1, total * .6 + .4)


def lighting_raster():
    """Inventory-pose block faces through the pack's item/entity shaders: enhanced lit like the inventory, others untouched."""
    from PIL import Image
    size = 96
    white = CTX.texture((1, 1), 4, bytes([255] * 4))
    target = CTX.framebuffer([CTX.texture((size, size), 4)])
    focal = 1 / math.tan(math.radians(70 / 2))
    near, far = .05, 1000
    BUFFERS["Projection"].write(array("f", [focal, 0, 0, 0, 0, focal, 0, 0, 0, 0, (far + near) / (far - near), -1,
                                            0, 0, far * near / (far - near) * 2, 0]))
    gui = rotation(math.radians(30), math.radians(225))
    faces = [f for f in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))
             if apply(gui, f)[2] > .05]
    assert len(faces) == 3 and (0, 1, 0) in faces, faces
    worst = 0
    cases = 0
    try:
        for name, defines in (("item", ""), ("entity", ""), ("entity", "#define PER_FACE_LIGHTING\n")):
            shader = CTX.program(vertex_shader=stage(name, "vsh", defines), fragment_shader=stage(name, "fsh", defines))
            for binding, (key, buffer) in enumerate(BUFFERS.items()):
                if key in shader:
                    shader[key].binding = binding
                    buffer.bind_to_uniform_block(binding)
            for key, unit in (("Sampler0", 0), ("Sampler1", 2), ("Sampler2", 1)):
                if key in shader:
                    shader[key].value = unit
            for mode in ("enhanced", "near", "plain"):
                white.use(0); LIGHTMAP.use(1); OVERLAY.use(2)
                target.use()
                target.clear(0, 0, 0, 1)

                def place(local):
                    x, y, z = apply(gui, local)
                    if mode == "enhanced":
                        return tuple(v * -65536 for v in (x * .3 / focal, y * .3 / focal, z * .3 / focal - 2))
                    # Compatible mode has no shader path: a near flattened sheet, even with the enhanced lit marker,
                    # is lit like any world item.
                    return (x * .03 / focal, y * .03 / focal, -.07 + z * .03 / focal * (.005 if mode == "near" else 1))
                vertices, normals = [], []
                for f in faces:
                    u = (0, 1, 0) if f[0] else (1, 0, 0)
                    w = (f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0])
                    corners = [tuple(f[i] * .5 + a * u[i] * .5 + b * w[i] * .5 for i in range(3))
                               for a, b in ((-1, -1), (1, -1), (1, 1), (-1, -1), (1, 1), (-1, 1))]
                    vertices += [place(c) for c in corners]
                    normals += list(apply(gui, f)) * 6
                count = len(vertices)
                # DisplayRenderer brightness: block 14 marks enhanced inventory-pose models.
                light = 240 if mode == "plain" else 224
                values = {
                    "Position": ("3f", array("f", (v for xyz in vertices for v in xyz))),
                    "Color": ("4f", array("f", (1, 1, 1, 1) * count)),
                    "Normal": ("3f", array("f", normals)),
                    "UV0": ("2f", array("f", [0] * (count * 2))),
                    "UV1": ("2i", struct.pack(f"{count * 2}i", *([0, 10] * count))),
                    "UV2": ("2i", struct.pack(f"{count * 2}i", *([light, 240] * count))),
                }
                inputs = [(CTX.buffer(data), fmt, key) for key, (fmt, data) in values.items() if key in shader]
                vao = CTX.vertex_array(shader, inputs)
                vao.render(moderngl.TRIANGLES, vertices=count)
                vao.release()
                for buffer, _, _ in inputs:
                    buffer.release()
                image = Image.frombytes("RGB", target.size, target.read(components=3))
                image.save(args.result.with_name(f"{args.result.stem}-light-{name}-{bool(defines)}-{mode}.png"))
                seen = []
                for f in faces:
                    cx, cy, cz = place(tuple(v * .5 for v in f))
                    if mode == "enhanced":
                        cx, cy = cx / -65536 * focal, cy / -65536 * focal
                    else:
                        cx, cy = cx * focal / -cz, cy * focal / -cz
                    # Framebuffer rows start at the bottom.
                    pixel = image.getpixel((int((cx + 1) / 2 * size), int((cy + 1) / 2 * size)))[0] / 255
                    seen.append(pixel)
                    if mode == "enhanced":
                        expected = inventory_light(apply(gui, f))
                        worst = max(worst, abs(pixel - expected))
                        assert abs(pixel - expected) < .03, (name, defines, mode, f, pixel, expected)
                if mode != "enhanced":
                    # Outside enhanced mode the world light (identity Lighting buffer: straight up) applies.
                    assert max(abs(a - inventory_light(apply(gui, f))) for a, f in zip(seen, faces)) > .05, seen
                cases += 1
            shader.release()
    finally:
        white.release()
        target.release()
    return {"lighting_raster_cases": cases, "lighting_max_error": worst}


if args.raster_only:
    print(json.dumps({"raster_cases": raster_checks(), **lighting_raster(), "failed": 0}))
    raise SystemExit


oit = "#define OIT\n#define OIT_COEFF_COUNT 4\n#define OIT_COEFF_ATTACHMENT_COUNT 1\n#define OIT_WAVELET_RANK 1\n"
VARIANTS = {
    "item": ("", "#define ALPHA_CUTOUT 0.1\n", "#define GLINT\n", "#define GLINT\n#define GLINT_SPECIAL\n",
             oit + "#define OIT_ALPHA_ONLY\n#define OIT_DEPTH_BOUNDS\n",
             oit + "#define OIT_ALPHA_ONLY\n#define OIT_TRANSMITTANCE\n", oit),
    "entity": ("", "#define PER_FACE_LIGHTING\n", "#define NO_CARDINAL_LIGHTING\n",
               "#define EMISSIVE\n#define NO_OVERLAY\n", "#define APPLY_TEXTURE_MATRIX\n#define GLINT\n",
               oit + "#define OIT_ALPHA_ONLY\n#define OIT_DEPTH_BOUNDS\n", oit),
    "text": ("", "#define IS_GUI\n", "#define IS_SEE_THROUGH\n"),
    "position_color": ("",),
    "glint": ("",),
}
fixtures = json.loads((OUTPUT / "enhanced-native-gpu.json").read_text())
encoded = [entry["position"] for entry in fixtures]
normal_checks = enhanced_checks = linkage = 0
maximum = 0
for name, variants in VARIANTS.items():
    for zero_to_one, defines in itertools.product((False, True), variants):
        if zero_to_one:
            defines += "#define RENDERPEARL_DEPTH_IS_ZERO_TO_ONE\n"
        # Link to the unchanged official fragment shader, including actual OIT/glint variants.
        linked = CTX.program(vertex_shader=stage(name, "vsh", defines), fragment_shader=stage(name, "fsh", defines))
        linked.release()
        linkage += 1
        candidate, original = program(name, defines), program(name, defines, True)
        for ortho, depth, normal in itertools.product((False, True), (.1, 2, 100, 10000), NORMALS):
            vertices = [(x, y, z - depth) for x, y, z in itertools.product((-.02, .02), (-.03, .03), (-.025, .025))]
            actual = draw(candidate, vertices, normal, ortho=ortho, zero_to_one=zero_to_one)
            expected = draw(original, vertices, normal, ortho=ortho, zero_to_one=zero_to_one)
            assert difference(actual, expected) == 0, (name, defines, ortho, depth, normal)
            normal_checks += 1
        if "IS_GUI" not in defines:
            for fov in (50, 70, 110):
                focal = 1 / math.tan(math.radians(fov / 2))
                actual = draw(candidate, encoded, fov=fov, zero_to_one=zero_to_one)
                for row, entry in zip(actual, fixtures):
                    x, y, z = entry["expected"]
                    device_depth = row[2] if zero_to_one else row[2] * .5 + .5
                    assert .5 <= device_depth < 1, ("Menu clipped or behind world", name, defines, row)
                    expected = (focal * x, focal * y, 1) + COLOR
                    if len(row) == 12:
                        expected += COLOR
                    actual_without_depth = row[:2] + row[3:]
                    error = max(abs(a - b) for a, b in zip(actual_without_depth, expected))
                    maximum = max(maximum, error)
                    assert error < 3e-6, (name, defines, error, row, expected)
                    enhanced_checks += 1
        candidate[0].release()
        original[0].release()

faults = []
for fault in ("perspective", "global_light"):
    faulty = program("item", fault=fault)
    good = program("item")
    vertices = encoded if fault == "perspective" else [(0, 0, -1), (.1, 0, -1), (0, .1, -1)]
    assert difference(draw(faulty, vertices), draw(good, vertices)) > .01, fault
    faults.append(fault)
    faulty[0].release(); good[0].release()
raster_cases = raster_checks()
lighting = lighting_raster()
result = {
    **lighting,
    "pack": str(PACK), "active_overlays": OVERLAYS,
    "gpu": CTX.info["GL_RENDERER"], "linked_actual_pipelines": linkage,
    "ordinary_path_cases": normal_checks, "enhanced_native_vertices": enhanced_checks,
    "maximum_output_error": maximum, "raster_cases": raster_cases, "faults_detected": faults, "failed": 0,
    "scope": "Effective pack shaders, official26.3 fragment linkage, native transformed bounds and reversed-depth rasterization on both clip ranges; not a live client or complete OIT composition.",
}
args.result.write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
