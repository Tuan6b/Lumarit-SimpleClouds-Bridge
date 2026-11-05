# Lumarit Simple Clouds Bridge

A NeoForge 1.21.1 client mod that lets an Iris shader pack draw [Simple Clouds](https://modrinth.com/mod/simple-clouds)' clouds.

Simple Clouds draws its clouds itself, after the shader pack's final pass, with its own shaders and vanilla lighting. A shader pack can't light, shadow or fog them, so they never match the rest of the picture. With this mod the pack draws them instead:

- while a shader pack is in use, Simple Clouds' own cloud drawing is switched off;
- the mod's clouds are handed to the pack: where its cloud regions are, and the real 3D cloud shapes the mod generates;
- Simple Clouds' weather, rain, lightning and sounds keep running as before.

The pack has to support it. [Lumarit](https://modrinth.com/shader/lumarit) does (1.3-beta3 or later; 1.3-beta4 recommended).

## Requirements

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x (tested on 21.1.252) |
| Simple Clouds | 0.7.x. Tested with 0.7.3; the cloud fields rely on its `cube_mesh.comp`, see [Limits](#limits) |
| Iris for NeoForge | 1.8.x (tested with 1.8.14-beta.1, with Sodium 0.8.13) |
| Shader pack | one that reads the bridge's uniforms, e.g. Lumarit 1.3-beta3 or later |

Lumarit 1.3-beta3 and later read the regions and all three fields. 1.3-beta4 also fixes a large frame-rate drop in rain, so it is the one to use. Builds that don't declare `scFieldFar` (the first 1.3-beta2) draw no clouds beyond about 4 km.

Client only; it does nothing on a server.

**With a pack that doesn't support the bridge**, the mod's clouds are still switched off whenever shaders are on, so you see no Simple Clouds clouds at all (only the pack's own clouds, if it has any). Set `replaceCloudRendering = false` for such packs to get Simple Clouds' own drawing back. With shaders off, Simple Clouds draws its clouds as usual.

## Install

Put the jar in your `mods` folder next to Simple Clouds and Iris, and pick a supporting shader pack.

## Configuration

`config/lumarit_scbridge-client.toml`:

| Option | Default | |
|---|---|---|
| `replaceCloudRendering` | `true` | While a shader pack is in use, switch Simple Clouds' cloud drawing off and hand its clouds to the pack. `false` = the bridge stays idle (all uniforms 0). |
| `debugLog` | `false` | Every 5 s, log one line: the region uniforms, slot 0, and each cloud field's state, GPU time and refresh count. |

NeoForge reloads the file when it changes, and the bridge reads both options every frame, so edits apply without restarting.

## How it works

### Switching Simple Clouds' drawing off

Simple Clouds asks for its render pipeline every frame through `DetermineCloudRenderPipelineEvent`. While the bridge drives, it answers with a pipeline that draws nothing. That removes the clouds, the storm fog and the atmospheric cloud layer. Rain, lightning, weather and sounds aren't part of that pipeline and stay the mod's.

The bridge drives (`scActive = 1`) when all of these hold:

- `replaceCloudRendering` is on;
- an Iris shader pack is in use;
- Simple Clouds renders clouds in the current dimension;
- the cloud mode is `DEFAULT`.

Each frame it takes one snapshot, before any pack program runs, so every program sees the same values.

### Cloud regions

The region uniforms come from Simple Clouds' public API: each cloud region's centre, radius, rotation/stretch, cloud type, storminess and weather type, interpolated to the current partial tick. Vertical extent isn't in the API, so it's read from the cloud type's noise layers.

### Cloud fields

To give the pack the mod's actual cloud cells, the bridge runs Simple Clouds' own density test into 3D textures:

1. At runtime it loads `cube_mesh.comp` from your installed Simple Clouds and patches it in memory at three anchor lines. Instead of emitting cubes, the shader stores each cell's density value, and its region lookup can step finer than a region texel.
2. It compiles that copy under its own name, through Simple Clouds' own shader loader, so the mod's compiled shaders aren't touched.
3. It dispatches it over a grid around the camera with the mod's live inputs: its region texture, its noise-layer buffers, scroll and wiggle.

Only the anchor strings are in this jar; no Simple Clouds code ships with it. If an anchor isn't found (a Simple Clouds version that changed those lines), the fields stay off, an error is logged once, and the pack falls back to the regions.

There are three fields, all R8, LINEAR, CLAMP_TO_EDGE:

| Field | Size | Cell | Covers | Refreshes |
|---|---|---|---|---|
| `scFieldNear` | 256 × 256 × 256 | 8 blocks (the mod's full-detail cubes) | ±1024 blocks, 2048 tall | ~12 / s |
| `scField` (wide) | 512 × 128 × 512 | 16 blocks | ±4096 blocks, 2048 tall | ~9 / s |
| `scFieldFar` | 256 × 32 × 256 | sized to the mod's cloud radius: 80 blocks on the HIGH detail preset | ±10240 blocks on HIGH, 2560 tall | ~3 / s |

A refresh happens when Simple Clouds regenerates its regions, about 23–29 times a second at 70–105 fps. Of every 8, near gets 4, wide 3 and far 1. Each refresh writes one whole field in a single dispatch at the start of a frame, so a pack never samples a half-written field, and no frame refreshes two.

### Iris hooks

- `CommonUniforms.addNonDynamicUniforms` adds the uniforms below, per frame, for every program: gbuffers, shadow, composites, computes, final.
- `IrisSamplers.addCustomTextures`: while a field is valid, the pack's placeholder custom texture of the same name is swapped for the live field. Iris binds one sampler, and gets the pack's placeholder whenever the field isn't valid.

## For shader-pack authors

Everything is 0 (and every sampler shows the pack's placeholder) while the bridge isn't driving; 0 means "use your own clouds". All positions and sizes are in world blocks. All uniforms are per-frame and available in every program.

### Declare the fields

The bridge only replaces a texture the pack declares, so the samplers are always bound 3D textures, even for players without the bridge. In `shaders.properties`:

```properties
customTexture.scField     = texture/scfield.dat TEXTURE_3D R8 1 1 1 RED UNSIGNED_BYTE
customTexture.scFieldNear = texture/scfield.dat TEXTURE_3D R8 1 1 1 RED UNSIGNED_BYTE
customTexture.scFieldFar  = texture/scfield.dat TEXTURE_3D R8 1 1 1 RED UNSIGNED_BYTE
```

Here `texture/scfield.dat` is a single zero byte, with a `scfield.dat.mcmeta` of `{"texture": {"blur": true, "clamp": true}}`. A field the pack doesn't declare is never bound.

### Uniforms

**Regions**

| Uniform | Type | Meaning |
|---|---|---|
| `scActive` | float | 1 while the bridge drives this frame, else 0 |
| `scCloudBase` | float | world Y of the mod's cloud layer origin (its cloud height) |
| `scScroll` | vec3 | the mod's noise scroll, in blocks |
| `scRegionCount` | int | regions in use, 0–16 |
| `scRegion0` … `scRegion15` | vec4 | (centre x, centre z, radius, edge fade). Edge fade is the inward fade width, 1600 blocks |
| `scRegionXform0` … `15` | vec4 | the region's rotation/stretch as (m00, m01, m10, m11): `q = mat2(v.x, v.y, v.z, v.w) * (p.xz - centre)`, inside while `length(q) < radius` |
| `scRegionShape0` … `15` | vec4 | (bottom Y, top Y, storminess 0–1, weather type) |

Slots at or above `scRegionCount` are all zero. Slots are ordered nearest first by `max(0, distance(camera.xz, centre) - radius)`; ties go by Simple Clouds' order weight, ascending. When the camera is inside several regions they all tie at 0, so test every slot.

Weather type, as a float: 0 = THUNDERSTORM, 1 = THUNDER, 2 = RAIN, 3 = NONE.

**Cloud fields.** For each field `F` in `scField`, `scFieldNear`, `scFieldFar`:

| Uniform | Type | Meaning |
|---|---|---|
| `F` | sampler3D | the mod's density, encoded `clamp(noise * 0.5 + 0.5, 0, 1)` |
| `FOrigin` | vec3 | world position of texel (0,0,0)'s corner; Y is `scCloudBase` |
| `FCell` | vec3 | blocks per texel |
| `FDims` | vec3 | texel counts |
| `FValid` | float | 1 only while a complete field of this kind is bound |
| `scFieldCut` | float | shared by all three: 0.5. The mod makes a cell solid where the value is above it |
| `scCloudRadius` | float | the mod's cloud draw radius in blocks (10240 on HIGH) |

`noise` is exactly the value Simple Clouds tests (> 0 = solid, region edge fade included), so values near the cut are a real soft margin. A texel's value sits at its centre. For the near field, that is exactly the centre of the mod's 8-block cube there; the coarser fields sample the same way at their own cell size.

The origins snap to the mod's region grid, so they move in 256-block steps as you travel; the camera is within about 256 blocks of each field's centre. Right after shaders, the override or the world come back, the fields fill one at a time over a few refreshes; read each field's `Valid`.

Sampling a field:

```glsl
uniform sampler3D scFieldNear;
uniform vec3 scFieldNearOrigin, scFieldNearCell, scFieldNearDims;
uniform float scFieldNearValid, scFieldCut;

// worldPos: absolute world position in blocks, e.g. cameraPosition + (gbufferModelViewInverse * viewPos).xyz
float nearDensity(vec3 worldPos) {
    vec3 uvw = (worldPos - scFieldNearOrigin) / (scFieldNearCell * scFieldNearDims);
    if (scFieldNearValid < 0.5 || any(lessThan(uvw, vec3(0.0))) || any(greaterThan(uvw, vec3(1.0)))) return 0.0;
    return texture(scFieldNear, uvw).r; // > scFieldCut: inside the mod's cloud
}
```

A pack typically uses near inside its box, then wide, then far, and the regions beyond, capped at `scCloudRadius`. Lumarit's `shaders/lib/clouds.glsl` is the reference consumer.

## Performance

Measured in the test instance, with Lumarit and Simple Clouds on the HIGH detail preset:

- GPU time per field refresh: about 1.2–1.6 ms for near or wide, about 0.14 ms for far, with occasional readings of 2.5–5 ms under heavy pack load. With `debugLog` on, the log line shows the last measured dispatch and the refresh count.
- Video memory: about 52 MB for the three fields (16.8 + 33.5 + 2.1 MB).
- Simple Clouds keeps generating its cloud mesh in the background, because its region texture comes from that same pass; only the drawing is switched off.

## Limits

- **NeoForge only**, like Simple Clouds on 1.21.1.
- **Cloud mode `DEFAULT` only**: singleplayer, or a server that runs Simple Clouds. A client on a server without Simple Clouds uses `SINGLE`/`AMBIENT`. There the bridge stays idle, Simple Clouds draws its own clouds and all uniforms are 0.
- **The fields depend on three lines in Simple Clouds' `cube_mesh.comp`** (checked against 0.7.3). If a future version changes them, the fields switch off with one logged error and the regions keep working.
- **At most 16 regions** are passed, nearest first.
- **Untested** with Vivecraft and Distant Horizons.

## Building

JDK 21:

```
./gradlew build
```

The jar is `build/libs/lumarit-scbridge-neoforge-<version>.jar`. Simple Clouds and Iris are compile-only dependencies, fetched from Modrinth's Maven. The Simple Clouds API jar is extracted from Simple Clouds' bundled jars at build time.

## Licence

[Apache-2.0](LICENSE).

Simple Clouds is licensed under the PolyForm Perimeter License 1.0.1. This repository and its jar contain none of Simple Clouds' code or shaders, only three short anchor strings. They are used to find lines in your own installed copy of `cube_mesh.comp`, which is patched in memory while the game runs.
