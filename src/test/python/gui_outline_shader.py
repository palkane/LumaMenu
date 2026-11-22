"""hide-focus-outline on a real GPU driver: runs the exported gui.vsh (and vanilla's as control) with transform feedback
over the menu body outlines that NativeFrameChecks measured in real 26.3 Dialog screens. Needs LUMAMENU_CLIENT_JAR and
the build/native-evidence output of nativeHitChecks. No Minecraft client control."""
from array import array
import argparse
import json
import os
from pathlib import Path
from zipfile import ZipFile

import moderngl

ROOT = Path(__file__).resolve().parents[3]
OUTPUT = ROOT / "build/native-evidence"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--pack", type=Path, default=OUTPUT / "pack", help="Export directory or merged ZIP.")
parser.add_argument("--cases", type=Path, default=OUTPUT / "focus-outlines.json")
args = parser.parse_args()
PACK_ZIP = ZipFile(args.pack) if args.pack.is_file() else None
CLIENT = ZipFile(os.environ["LUMAMENU_CLIENT_JAR"])
CTX = moderngl.create_standalone_context(backend="egl", require=430)
CTX.simple_framebuffer((1, 1)).use()
IDENTITY = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
GUI = "assets/minecraft/shaders/core/gui.vsh"


def source(vanilla):
    if vanilla:
        return CLIENT.read(GUI).decode()
    if PACK_ZIP:
        return PACK_ZIP.read(GUI).decode()
    return (args.pack / GUI).read_text()


def program(vanilla):
    text = source(vanilla).replace("#version 330", "#version 430")
    text = text.replace("void main() {", "out vec4 lumaProbe;\nvoid main() {")
    end = text.rfind("}")
    text = text[:end] + "\n    lumaProbe = gl_Position;\n" + text[end:]
    shader = CTX.program(vertex_shader=text, varyings=["lumaProbe", "vertexColor"])
    return shader


def ortho(width, height, near=1000.0, far=21000.0):
    return [2 / width, 0, 0, 0, 0, -2 / height, 0, 0, 0, 0, 2 / (near - far), 0,
            -1, 1, (far + near) / (near - far), 1]


def run(shader, gui_width, gui_height, vertices, color, model_view=IDENTITY):
    dynamic = CTX.buffer(array("f", model_view + IDENTITY + [1] * 4 + [0] * 4))
    projection = CTX.buffer(array("f", ortho(gui_width, gui_height)))
    for binding, (key, buffer) in enumerate((("DynamicTransforms", dynamic), ("Projection", projection))):
        if key in shader:
            shader[key].binding = binding
            buffer.bind_to_uniform_block(binding)
    count = len(vertices)
    position = CTX.buffer(array("f", (v for x, y in vertices for v in (x, y, 0.0))))
    colors = CTX.buffer(array("f", list(color) * count))
    vao = CTX.vertex_array(shader, [(position, "3f", "Position"), (colors, "4f", "Color")])
    target = CTX.buffer(reserve=count * 8 * 4)
    vao.transform(target, mode=moderngl.POINTS, vertices=count)
    floats = array("f", target.read())
    for item in (target, vao, position, colors, dynamic, projection):
        item.release()
    return [tuple(floats[i:i + 8]) for i in range(0, len(floats), 8)]


def outline(x, y, w, h):
    quads = [(x, y, x + w, y + 1), (x, y + h - 1, x + w, y + h), (x, y + 1, x + 1, y + h - 1),
             (x + w - 1, y + 1, x + w, y + h - 1)]
    return [(a, b) for x0, y0, x1, y1 in quads for a, b in ((x0, y0), (x0, y1), (x1, y1), (x1, y0))]


def quads_hidden(result):
    return [all(row[7] == 0.0 for row in result[i:i + 4]) for i in range(0, len(result), 4)]


ours, vanilla = program(False), program(True)
cases = json.loads(args.cases.read_text())["cases"]
checks = 0
WHITE, GREY = (1.0, 1.0, 1.0, 1.0), (0.99, 0.99, 0.99, 1.0)
DEPTH = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, -11000, 1]
for case in cases:
    gw, gh = case["guiWidth"], case["guiHeight"]
    where = f'{case["width"]}x{case["height"]} (GUI {gw:.2f}x{gh:.2f})'
    vertices = outline(case["x"], case["y"], case["w"], case["h"])
    for model_view in (IDENTITY, DEPTH):
        result = run(ours, gw, gh, vertices, WHITE, model_view)
        control = run(vanilla, gw, gh, vertices, WHITE, model_view)
        assert all(quads_hidden(result)), f"menu focus outline hidden at {where}"
        assert all(abs(a - b) < 1e-5 for r, c in zip(result, control) for a, b in zip(r[:4], c[:4])), \
            f"positions equal vanilla at {where}"
        assert all(row[7] == 1.0 for row in control) and any(row[0] != 0.0 for row in control), \
            f"control: vanilla keeps the outline at {where}"
        checks += 3
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        shifted = run(ours, gw, gh, outline(case["x"] + dx, case["y"] + dy, case["w"], case["h"]), WHITE)
        assert not any(quads_hidden(shifted)), f"an outline shifted by ({dx}, {dy}) stays visible at {where}"
        checks += 1
    grey = run(ours, gw, gh, vertices, GREY)
    assert all(row[7] == 1.0 for row in grey), f"a non-white outline at the same place keeps its alpha at {where}"
    checks += 1
print(f"GUI outline shader: {checks} GPU checks over {len(cases)} measured menu bodies "
      f"({CTX.info['GL_RENDERER']}); shifted, grey and vanilla controls kept visible")
