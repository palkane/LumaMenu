package dev.lumamenu;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class MenuCache {
    private static final int FORMAT = 5;
    private static final Gson JSON = new Gson();
    private final Path file;

    record State(int format, Map<String, Layouts.Page> pages,
                 Map<String, Map<String, List<DisplayRenderer.Prepared>>> profiles,
                 int rotationHitStep, Map<String, Map<String, HitGeometry.Surface>> hitProfiles,
                 Map<String, RenderProfile> enhancedProfiles, Map<String, RenderProfile> compatibleProfiles,
                 Map<String, RenderProfile> lookDownProfiles) {
        State {
            if (format != FORMAT || pages == null || pages.isEmpty() || profiles == null)
                throw new IllegalArgumentException("不兼容的编译缓存，请执行reload");
            pages = Collections.unmodifiableMap(new LinkedHashMap<>(pages));
            Map<String, Map<String, List<DisplayRenderer.Prepared>>> copy = new LinkedHashMap<>();
            for (var entry : profiles.entrySet()) {
                Map<String, List<DisplayRenderer.Prepared>> profile = new LinkedHashMap<>();
                for (var page : pages.entrySet()) {
                    List<DisplayRenderer.Prepared> prepared = entry.getValue().get(page.getKey());
                    if (prepared == null || prepared.size() != page.getValue().visuals().size())
                        throw new IllegalArgumentException("编译缓存缺少页面变换：" + page.getKey());
                    for (int i = 0; i < prepared.size(); i++)
                        if (!prepared.get(i).visual().equals(page.getValue().visuals().get(i)))
                            throw new IllegalArgumentException("缓存布局与变换不匹配");
                    profile.put(page.getKey(), List.copyOf(prepared));
                }
                copy.put(entry.getKey(), Collections.unmodifiableMap(profile));
            }
            profiles = Collections.unmodifiableMap(copy);
            rotationHitStep = HitGeometry.step(rotationHitStep == 0 ? null : rotationHitStep);
            // Additive format-2 upgrade: derive from saved transforms, never reread YAML.
            if (hitProfiles == null) hitProfiles = prepareHits(pages, profiles, rotationHitStep);
            Map<String, Map<String, HitGeometry.Surface>> hits = new LinkedHashMap<>();
            for (var entry : profiles.entrySet()) {
                var profile = hitProfiles.get(entry.getKey());
                if (profile == null) throw new IllegalArgumentException("缓存缺少个人点击轮廓");
                Map<String, HitGeometry.Surface> checked = new LinkedHashMap<>();
                for (var page : pages.values()) {
                    if (!hasRotation(page)) continue;
                    var hit = profile.get(page.id());
                    if (hit == null || hit.regions().stream().anyMatch(r -> r.control() >= page.controls().size())
                        || hit.surface().stream().anyMatch(p -> p.control() >= page.controls().size())
                        || hit.chrome().surface().stream().anyMatch(p -> p.control() >= page.controls().size()))
                        throw new IllegalArgumentException("缓存点击轮廓无效：" + page.id());
                    checked.put(page.id(), hit);
                }
                hits.put(entry.getKey(), Collections.unmodifiableMap(checked));
            }
            hitProfiles = Collections.unmodifiableMap(hits);
            if (enhancedProfiles == null)
                enhancedProfiles = prepareMode(pages, profiles, rotationHitStep, RenderMode.ENHANCED, false);
            enhancedProfiles = validateMode(pages, profiles.keySet(), enhancedProfiles);
            if (compatibleProfiles == null)
                compatibleProfiles = prepareMode(pages, profiles, rotationHitStep, RenderMode.COMPATIBLE, false);
            compatibleProfiles = validateMode(pages, profiles.keySet(), compatibleProfiles);
            // Additive: steered models for compatible look-down pages, derived from saved transforms only.
            var downPages = lookDownPages(pages);
            if (lookDownProfiles == null)
                lookDownProfiles = prepareMode(downPages, profiles, rotationHitStep, RenderMode.COMPATIBLE, true);
            lookDownProfiles = validateMode(downPages, profiles.keySet(), lookDownProfiles);
            Layouts.validateLinks(pages);
        }
        State(int format, Map<String, Layouts.Page> pages,
              Map<String, Map<String, List<DisplayRenderer.Prepared>>> profiles,
              int rotationHitStep, Map<String, Map<String, HitGeometry.Surface>> hitProfiles) {
            this(format, pages, profiles, rotationHitStep, hitProfiles, null, null, null);
        }
        List<DisplayRenderer.Prepared> prepared(Calibration.Mapping mapping, String page, RenderMode mode) {
            return prepared(mapping, page, mode, false);
        }
        HitGeometry.Surface hit(Calibration.Mapping mapping, String page, RenderMode mode) {
            return hit(mapping, page, mode, false);
        }
        List<DisplayRenderer.Prepared> prepared(Calibration.Mapping mapping, String page, RenderMode mode, boolean down) {
            var profile = profile(mapping, page, mode, down);
            if (profile == null || !profile.prepared.containsKey(page))
                throw new IllegalStateException("渲染模式缓存缺失，请重新校准或reload");
            return profile.prepared.get(page);
        }
        HitGeometry.Surface hit(Calibration.Mapping mapping, String page, RenderMode mode, boolean down) {
            var profile = profile(mapping, page, mode, down);
            if (profile == null || !profile.hits.containsKey(page))
                throw new IllegalStateException("渲染模式点击缓存缺失，请重新校准或reload");
            return profile.hits.get(page);
        }
        private RenderProfile profile(Calibration.Mapping mapping, String page, RenderMode mode, boolean down) {
            if (mode == RenderMode.ENHANCED) return enhancedProfiles.get(mapping.key());
            var steered = down ? lookDownProfiles.get(mapping.key()) : null;
            return steered != null && steered.prepared.containsKey(page) ? steered : compatibleProfiles.get(mapping.key());
        }
        List<DisplayRenderer.Prepared> prepared(Calibration.Mapping mapping, String page) {
            return prepared(mapping, page, RenderMode.COMPATIBLE);
        }
        HitGeometry.Surface hit(Calibration.Mapping mapping, String page) {
            return hit(mapping, page, RenderMode.COMPATIBLE);
        }
    }

    record RenderProfile(Map<String, List<DisplayRenderer.Prepared>> prepared,
                           Map<String, HitGeometry.Surface> hits) {
        RenderProfile {
            var copy = new LinkedHashMap<String, List<DisplayRenderer.Prepared>>();
            prepared.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            prepared = Collections.unmodifiableMap(copy);
            hits = Collections.unmodifiableMap(new LinkedHashMap<>(hits));
        }
    }

    MenuCache(Path file) { this.file = file; }
    static State compile(Map<String, Layouts.Page> pages, Collection<Calibration.Mapping> mappings) {
        return compile(pages, mappings, 2);
    }
    static State compile(Map<String, Layouts.Page> pages, Collection<Calibration.Mapping> mappings, int step) {
        HitGeometry.step(step);
        Map<String, Map<String, List<DisplayRenderer.Prepared>>> profiles = new LinkedHashMap<>();
        for (var mapping : mappings)
            profiles.computeIfAbsent(mapping.key(), key -> prepareProfile(pages, mapping));
        return new State(FORMAT, pages, profiles, step, null);
    }
    static State withMapping(State state, Calibration.Mapping mapping) {
        if (state.profiles.containsKey(mapping.key())) return state;
        Map<String, Map<String, List<DisplayRenderer.Prepared>>> next = new LinkedHashMap<>(state.profiles);
        next.put(mapping.key(), prepareProfile(state.pages, mapping));
        var hits = new LinkedHashMap<>(state.hitProfiles);
        hits.putAll(prepareHits(state.pages, Map.of(mapping.key(), next.get(mapping.key())), state.rotationHitStep));
        var enhanced = new LinkedHashMap<>(state.enhancedProfiles);
        enhanced.putAll(prepareMode(state.pages, Map.of(mapping.key(), next.get(mapping.key())),
            state.rotationHitStep, RenderMode.ENHANCED, false));
        var compatible = new LinkedHashMap<>(state.compatibleProfiles);
        compatible.putAll(prepareMode(state.pages, Map.of(mapping.key(), next.get(mapping.key())),
            state.rotationHitStep, RenderMode.COMPATIBLE, false));
        var down = new LinkedHashMap<>(state.lookDownProfiles);
        down.putAll(prepareMode(lookDownPages(state.pages), Map.of(mapping.key(), next.get(mapping.key())),
            state.rotationHitStep, RenderMode.COMPATIBLE, true));
        return new State(FORMAT, state.pages, next, state.rotationHitStep, hits, enhanced, compatible, down);
    }
    private static Map<String, Layouts.Page> lookDownPages(Map<String, Layouts.Page> pages) {
        var result = new LinkedHashMap<String, Layouts.Page>();
        pages.forEach((id, page) -> { if (page.lookDown()) result.put(id, page); });
        return result;
    }
    private static Map<String, RenderProfile> prepareMode(Map<String, Layouts.Page> pages,
            Map<String, Map<String, List<DisplayRenderer.Prepared>>> profiles, int step, RenderMode mode,
            boolean down) {
        var result = new LinkedHashMap<String, RenderProfile>();
        profiles.forEach((key, compatible) -> {
            String[] fields = key.split("/");
            if (fields.length != 4) throw new IllegalArgumentException("缓存校准键无效");
            var mapping = new Calibration.Mapping(Double.parseDouble(fields[0]), Double.parseDouble(fields[1]),
                Double.parseDouble(fields[2]), Double.parseDouble(fields[3]));
            var prepared = new LinkedHashMap<String, List<DisplayRenderer.Prepared>>();
            var hits = new LinkedHashMap<String, HitGeometry.Surface>();
            pages.forEach((id, page) -> {
                var transforms = mode == RenderMode.ENHANCED ? DisplayRenderer.enhanced(compatible.get(id), mapping)
                    : DisplayRenderer.compatible(compatible.get(id), mapping, down);
                prepared.put(id, transforms);
                hits.put(id, HitGeometry.compile(page, mapping, transforms, step, mode));
            });
            result.put(key, new RenderProfile(prepared, hits));
        });
        return result;
    }
    private static Map<String, RenderProfile> validateMode(Map<String, Layouts.Page> pages,
            Set<String> keys, Map<String, RenderProfile> profiles) {
        var checked = new LinkedHashMap<String, RenderProfile>();
        for (String key : keys) {
            var profile = profiles.get(key);
            if (profile == null || !profile.prepared.keySet().equals(pages.keySet())
                || !profile.hits.keySet().equals(pages.keySet()))
                throw new IllegalArgumentException("缓存缺少渲染模式页面");
            for (var page : pages.values()) {
                var prepared = profile.prepared.get(page.id());
                if (prepared.size() != page.visuals().size())
                    throw new IllegalArgumentException("渲染模式布局与变换不匹配");
                for (int i = 0; i < prepared.size(); i++)
                    if (!prepared.get(i).visual().equals(page.visuals().get(i)))
                        throw new IllegalArgumentException("渲染模式布局与变换不匹配");
                var hit = profile.hits.get(page.id());
                if (hit.regions().stream().anyMatch(r -> r.control() >= page.controls().size())
                    || hit.surface().stream().anyMatch(p -> p.control() >= page.controls().size())
                    || hit.chrome().surface().stream().anyMatch(p -> p.control() >= page.controls().size()))
                    throw new IllegalArgumentException("渲染模式点击轮廓无效");
            }
            checked.put(key, profile);
        }
        return Collections.unmodifiableMap(checked);
    }
    private static boolean hasRotation(Layouts.Page page) {
        return page.visuals().stream().anyMatch(HitGeometry::transformed);
    }
    private static Map<String, Map<String, HitGeometry.Surface>> prepareHits(
            Map<String, Layouts.Page> pages,
            Map<String, Map<String, List<DisplayRenderer.Prepared>>> profiles, int step) {
        Map<String, Map<String, HitGeometry.Surface>> result = new LinkedHashMap<>();
        profiles.forEach((key, prepared) -> {
            String[] fields = key.split("/");
            if (fields.length != 4) throw new IllegalArgumentException("缓存校准键无效");
            var mapping = new Calibration.Mapping(Double.parseDouble(fields[0]), Double.parseDouble(fields[1]),
                Double.parseDouble(fields[2]), Double.parseDouble(fields[3]));
            Map<String, HitGeometry.Surface> hits = new LinkedHashMap<>();
            pages.forEach((id, page) -> {
                if (hasRotation(page)) hits.put(id, HitGeometry.compile(page, mapping, prepared.get(id), step));
            });
            result.put(key, hits);
        });
        return result;
    }
    /** One page's displays for a mode, exactly as the cached profiles build them (moved pages are built at runtime). */
    static List<DisplayRenderer.Prepared> prepare(Layouts.Page page, Calibration.Mapping mapping, RenderMode mode,
                                                  boolean down) {
        var base = DisplayRenderer.prepare(page, mapping);
        return mode == RenderMode.ENHANCED ? DisplayRenderer.enhanced(base, mapping)
            : DisplayRenderer.compatible(base, mapping, down);
    }
    private static Map<String, List<DisplayRenderer.Prepared>> prepareProfile(Map<String, Layouts.Page> pages,
                                                                            Calibration.Mapping mapping) {
        Map<String, List<DisplayRenderer.Prepared>> prepared = new LinkedHashMap<>();
        pages.forEach((id, page) -> prepared.put(id, DisplayRenderer.prepare(page, mapping)));
        return prepared;
    }
    State read() throws IOException {
        try (var input = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)) {
            JsonObject json = JSON.fromJson(input, JsonObject.class);
            if (json == null || !json.has("format"))
                throw new IOException("不兼容的编译缓存，请执行reload");
            int format = json.get("format").getAsInt();
            if (format >= 2 && format < FORMAT) {
                // Backend migration: keep saved layouts/calibrations, rebuild only derived geometry once.
                Map<String, Layouts.Page> pages = new LinkedHashMap<>();
                json.getAsJsonObject("pages").entrySet().forEach(entry ->
                    pages.put(entry.getKey(), images(JSON.fromJson(entry.getValue(), Layouts.Page.class))));
                List<Calibration.Mapping> mappings = new ArrayList<>();
                for (String key : json.getAsJsonObject("profiles").keySet()) {
                    String[] fields = key.split("/");
                    if (fields.length != 4) throw new IllegalArgumentException("缓存校准键无效");
                    mappings.add(new Calibration.Mapping(Double.parseDouble(fields[0]), Double.parseDouble(fields[1]),
                        Double.parseDouble(fields[2]), Double.parseDouble(fields[3])));
                }
                for (var page : pages.values()) for (var v : page.visuals())
                    if (v.kind().equals("item") || v.kind().equals("block"))
                        DisplayRenderer.validateSource(v.kind(), v.source(), v.itemModel());
                return compile(pages, mappings, json.has("rotationHitStep") ? json.get("rotationHitStep").getAsInt() : 2);
            }
            if (json.get("format").getAsInt() != FORMAT)
                throw new IOException("不兼容的编译缓存，请执行reload");
            State state = JSON.fromJson(json, State.class);
            if (state == null) throw new IOException("编译缓存为空");
            return state;
        } catch (JsonParseException | IllegalArgumentException error) {
            throw new IOException("无法恢复编译缓存，请检查后reload", error);
        }
    }
    /** Formats before 5 stored images as {@code plane} visuals carrying an item model or a variable source. */
    private static Layouts.Page images(Layouts.Page page) {
        List<Layouts.Visual> visuals = new ArrayList<>();
        for (var v : page.visuals())
            visuals.add(v.kind().equals("plane") && (!v.itemModel().isEmpty() || !v.source().isEmpty())
                ? new Layouts.Visual(v.id(), "image", v.x(), v.y(), v.z(), v.width(), v.height(), v.size(), v.color(),
                    v.content(), v.source(), v.itemModel(), v.rx(), v.ry(), v.rz(), v.bx(), v.by(), v.bz(),
                    v.transforms(), v.text()) : v);
        return new Layouts.Page(page.id(), page.title(), page.permission(), visuals, page.controls(), page.regions(),
            page.surface(), page.surfaceOriginX(), page.surfaceOriginY(), page.surfaceWidth(), page.surfaceHeight(),
            page.surfaceRows(), page.surfaceAdvance(), page.bodyWidth(), page.chrome(), page.lookDown(),
            page.cornerClose(), page.openEvents(), page.closeEvents(), page.file());
    }

    boolean exists() { return Files.isRegularFile(file); }
    void save(State state) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "compiled-", ".tmp");
        try {
            try (var output = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temp)), StandardCharsets.UTF_8)) {
                JSON.toJson(state, output);
            }
            // Read back the actual candidate before publishing it or replacing the last good disk cache.
            new MenuCache(temp).read();
            Calibration.atomicReplace(temp, file);
        } finally { Files.deleteIfExists(temp); }
    }
}
