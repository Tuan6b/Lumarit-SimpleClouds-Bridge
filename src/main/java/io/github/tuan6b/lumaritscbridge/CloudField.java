package io.github.tuan6b.lumaritscbridge;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.logging.LogUtils;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.MultiRegionCloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.mesh.lod.LevelOfDetail;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.shader.buffer.BindingManager;
import dev.nonamecrackers2.simpleclouds.client.shader.buffer.ShaderStorageBufferObject;
import dev.nonamecrackers2.simpleclouds.client.shader.compute.ComputeShader;
import io.github.tuan6b.lumaritscbridge.mixin.CloudMeshGeneratorAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL43.*;

/**
 * The mod's own cloud density around the camera as 3D textures (contract v2 scField, v3 scFieldNear). Simple Clouds'
 * cube_mesh.comp is loaded from the installed mod at runtime and patched to store its pre-threshold noise instead of
 * emitting cubes, then dispatched over a world-aligned grid with the mod's live inputs (region texture, noise layer
 * buffers, scroll).
 */
public final class CloudField {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final ResourceLocation PATCHED = ResourceLocation.fromNamespaceAndPath(LumaritScBridge.MOD_ID, "cloud_field");
	private static final ResourceLocation PATCHED_FILE = ResourceLocation.fromNamespaceAndPath(LumaritScBridge.MOD_ID, "shaders/compute/cloud_field.comp");
	private static final ResourceLocation SOURCE_FILE = ResourceLocation.fromNamespaceAndPath("simpleclouds", "shaders/compute/cube_mesh.comp");
	// The only text of Simple Clouds' shader this jar carries: where to hook in.
	private static final String MAIN_ANCHOR = "void main()";
	private static final String NOISE_ANCHOR = "float noise = getNoiseForLayerGroup(group, x, y, z, gradient) + fade;";
	private static final String REGION_ANCHOR = "vec2 texelCoord = gl_GlobalInvocationID.xz + RegionSampleOffset;";

	/** 8-block cells, +-1024 blocks: the mod's full-detail cubes. 16-block cells, +-4096 blocks: everything to the horizon. */
	public static final CloudField NEAR = new CloudField(256, 256, 1), WIDE = new CloudField(512, 128, 2);
	/** Stored value = clamp(noise * 0.5 + 0.5); the mod makes a cell solid at noise > 0. 0 while not driving. */
	public static float cut;

	private static ComputeShader shader;
	private static boolean broken, regionsFresh, nearNext;
	private static float regionX, regionZ;
	private static int imageUnit = -1, timer, refreshes;
	private static float gpuMillis = -1;

	/** This field's uniform values; all 0 while there is no complete field. */
	public final Vector3f origin = new Vector3f(), cell = new Vector3f(), dims = new Vector3f();
	public float valid;
	/** Texels across (x, z) and up (y), and cloud units per texel. */
	private final int width, height, cellUnits;
	private int texture;

	private CloudField(int width, int height, int cellUnits) {
		this.width = width;
		this.height = height;
		this.cellUnits = cellUnits;
	}

	public int textureId() {
		return texture;
	}

	/** The mod regenerated its region texture centred on (x, z) cloud units; the fields can follow. */
	public static void onRegionsGenerated(float x, float z) {
		regionX = x;
		regionZ = z;
		regionsFresh = true;
	}

	static void update(boolean driving, float cloudBase, boolean timed) {
		if (!driving || broken) {
			cut = 0;
			NEAR.clear();
			WIDE.clear();
			return;
		}
		// Refresh only when the mod's regions changed, one whole field per dispatch before any pack program samples it.
		// The two fields take turns, so each follows at half the mod's mesh-gen rate and no frame pays for both.
		if (!regionsFresh || !(SimpleCloudsRenderer.getInstance().getMeshGenerator() instanceof MultiRegionCloudMeshGenerator generator))
			return;
		ComputeShader mesh = ((CloudMeshGeneratorAccessor) (Object) generator).lumaritScBridge$shader();
		if (mesh == null || !mesh.isValid())
			return;
		regionsFresh = false;
		refreshes++;
		nearNext = !nearNext;
		try {
			(nearNext ? NEAR : WIDE).refresh(generator, mesh, cloudBase, timed);
			cut = 0.5f;
		} catch (Exception e) {
			LOGGER.error("Cloud fields disabled; the pack falls back to the cloud regions", e);
			broken = true;
			cut = 0;
			NEAR.clear();
			WIDE.clear();
		}
	}

	private void clear() {
		valid = 0;
		origin.zero();
		cell.zero();
		dims.zero();
	}

	private void refresh(MultiRegionCloudMeshGenerator generator, ComputeShader mesh, float cloudBase, boolean timed) throws IOException {
		if (shader == null || !shader.isValid())
			shader = loadPatched(generator.fadeNearOriginEnabled());
		if (texture == 0)
			texture = createTexture(width, height);
		if (imageUnit < 0)
			imageUnit = BindingManager.getAvailableImageUnit();
		int src = mesh.getId(), dst = shader.getId();

		// Same inputs the mod's last mesh pass used, then our grid: centred on the region texture, on the mod's cube grid.
		copyUniform(src, dst, "Scroll", 3);
		copyUniform(src, dst, "Origin", 3);
		copyUniform(src, dst, "Wiggle", 1);
		copyUniform(src, dst, "FadeStart", 1);
		copyUniform(src, dst, "FadeEnd", 1);
		int regionsTexSize = glGetUniformi(src, glGetUniformLocation(src, "RegionsTexSize"));
		glProgramUniform1i(dst, glGetUniformLocation(dst, "RegionsTexSize"), regionsTexSize);
		// The region texture has one layer per LOD scale (1, then each LOD's chunk scale); use the finest that spans the field.
		LevelOfDetail[] lods = generator.getLodConfig().getLods();
		int layer = 0;
		float regionScale = 1;
		while (regionScale * regionsTexSize < width * cellUnits) {
			if (layer == lods.length)
				throw new IllegalStateException("Simple Clouds' region texture (" + regionsTexSize + " texels) can't span the cloud field");
			regionScale = lods[layer++].chunkScale();
		}
		// Field corner on the mod's grid. Each texel samples its cube's min corner + half a cloud unit, i.e. the mod's own
		// rule for its 8-block cubes (value at the corner, cube centred half a unit further), so silhouettes line up.
		float x0 = regionX - width * cellUnits / 2f, z0 = regionZ - width * cellUnits / 2f, shift = cellUnits / 2f - 0.5f;
		glProgramUniform3f(dst, glGetUniformLocation(dst, "RenderOffset"), x0 + shift, shift, z0 + shift);
		glProgramUniform1f(dst, glGetUniformLocation(dst, "Scale"), cellUnits);
		glProgramUniform1i(dst, glGetUniformLocation(dst, "LodLevel"), layer);
		glProgramUniform1f(dst, glGetUniformLocation(dst, "BridgeTexelStep"), cellUnits / regionScale);
		glProgramUniform2f(dst, glGetUniformLocation(dst, "RegionSampleOffset"), (x0 + shift - regionX) / regionScale + regionsTexSize / 2f,
				(z0 + shift - regionZ) / regionScale + regionsTexSize / 2f);
		glProgramUniform1i(dst, glGetUniformLocation(dst, "BridgeField"), imageUnit);
		shader.setSampler2DArray("RegionsSampler", generator.getCloudRegionTextureId(), 0);
		bindBlock(dst, mesh, "NoiseLayers");
		bindBlock(dst, mesh, "LayerGroupings");
		glBindImageTexture(imageUnit, texture, 0, true, 0, GL_WRITE_ONLY, GL_R8);

		if (timed) {
			if (timer == 0) timer = glGenQueries();
			else if (glGetQueryObjecti(timer, GL_QUERY_RESULT_AVAILABLE) != 0) gpuMillis = glGetQueryObjecti64(timer, GL_QUERY_RESULT) / 1e6f;
			glBeginQuery(GL_TIME_ELAPSED, timer);
		}
		ProgramManager.glUseProgram(dst);
		glDispatchCompute(width / 8, height / 8, width / 8);
		ProgramManager.glUseProgram(0);
		if (timed) glEndQuery(GL_TIME_ELAPSED);
		glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);

		int scale = LumaritScBridge.SCALE;
		origin.set(x0 * scale, cloudBase, z0 * scale);
		cell.set(cellUnits * scale);
		dims.set(width, height, width);
		valid = 1;
	}

	private static ComputeShader loadPatched(boolean fadeNearOrigin) throws IOException {
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		ResourceProvider provider = location -> !location.equals(PATCHED_FILE) ? resources.getResource(location)
				: resources.getResource(SOURCE_FILE).map(original -> new Resource(original.source(), () -> patch(original.open())));
		// TRANSPARENCY 0 and FIXED_SECTION_SIZE 1 drop output buffers we never write; FADE_NEAR_ORIGIN changes the noise, so it follows the mod.
		return ComputeShader.loadShader(PATCHED, provider, 8, 8, 8, ImmutableMap.of("TYPE", "0", "FADE_NEAR_ORIGIN", fadeNearOrigin ? "1" : "0",
				"STYLE", "0", "TRANSPARENCY", "0", "FIXED_SECTION_SIZE", "1"));
	}

	/** Store the cell's noise into the field instead of building its cube; everything after it is the mesh output. */
	static InputStream patch(InputStream in) throws IOException {
		String source;
		try (in) {
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		if (!source.contains(MAIN_ANCHOR) || !source.contains(NOISE_ANCHOR) || !source.contains(REGION_ANCHOR))
			throw new IOException("Simple Clouds' cube_mesh.comp has changed; the cloud field's anchors are missing");
		// Field cells may be finer than the region layer's texels, so the region lookup steps BridgeTexelStep texels per cell.
		source = source.replace(MAIN_ANCHOR, "layout(r8) uniform writeonly image3D BridgeField;\nuniform float BridgeTexelStep;\n\n" + MAIN_ANCHOR)
				.replace(REGION_ANCHOR, "vec2 texelCoord = vec2(gl_GlobalInvocationID.xz) * BridgeTexelStep + RegionSampleOffset;")
				.replace(NOISE_ANCHOR, NOISE_ANCHOR + "\n\timageStore(BridgeField, ivec3(gl_GlobalInvocationID), vec4(clamp(noise * 0.5 + 0.5, 0.0, 1.0)));\n\treturn;");
		return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
	}

	private static int createTexture(int width, int height) {
		int id = GlStateManager._genTexture();
		glBindTexture(GL_TEXTURE_3D, id);
		glTexStorage3D(GL_TEXTURE_3D, 1, GL_R8, width, height, width);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_3D, 0);
		return id;
	}

	private static void copyUniform(int src, int dst, String name, int components) {
		int from = glGetUniformLocation(src, name), to = glGetUniformLocation(dst, name);
		if (from < 0 || to < 0) return;
		float[] value = new float[components];
		glGetUniformfv(src, from, value);
		if (components == 3) glProgramUniform3fv(dst, to, value);
		else glProgramUniform1fv(dst, to, value);
	}

	/** Point our copy of a storage block at the mod's buffer (bindings are unique per buffer in Simple Clouds). */
	private static void bindBlock(int program, ComputeShader mesh, String name) {
		ShaderStorageBufferObject buffer = mesh.getShaderStorageBuffer(name);
		glShaderStorageBlockBinding(program, glGetProgramResourceIndex(program, GL_SHADER_STORAGE_BLOCK, name), buffer.getBinding());
		glBindBufferBase(GL_SHADER_STORAGE_BUFFER, buffer.getBinding(), buffer.getId());
	}

	/** One line for the 5-second debug log; resets the refresh count. */
	static String debug() {
		String line = String.format("near valid=%.0f origin=(%.0f, %.0f, %.0f) wide valid=%.0f origin=(%.0f, %.0f, %.0f) gpu=%.2fms refreshes=%d",
				NEAR.valid, NEAR.origin.x, NEAR.origin.y, NEAR.origin.z, WIDE.valid, WIDE.origin.x, WIDE.origin.y, WIDE.origin.z, gpuMillis, refreshes);
		refreshes = 0;
		return line;
	}
}
