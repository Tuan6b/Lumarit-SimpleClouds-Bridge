package io.github.tuan6b.lumaritscbridge;

import com.mojang.logging.LogUtils;
import dev.nonamecrackers2.simpleclouds.api.SimpleCloudsAPI;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.ScAPICloudType;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.region.ScAPICloudRegion;
import dev.nonamecrackers2.simpleclouds.api.common.world.ScAPICloudManager;
import dev.nonamecrackers2.simpleclouds.client.event.impl.DetermineCloudRenderPipelineEvent;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.renderer.pipeline.CloudsRenderPipeline;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;

/**
 * Hands Simple Clouds' cloud regions (and, through {@link CloudField}, its cloud fields) to the Iris shader pack as uniforms,
 * and switches the mod's own cloud drawing off while it does, so the pack draws those clouds itself. Rain, lightning and
 * weather stay the mod's. The uniform contract is in README.md.
 */
@Mod(value = LumaritScBridge.MOD_ID, dist = Dist.CLIENT)
public final class LumaritScBridge {
	public static final String MOD_ID = "lumarit_scbridge";
	public static final int SLOTS = 16;
	static final int SCALE = SimpleCloudsConstants.CLOUD_SCALE; // blocks per cloud unit
	private static final float EDGE_FADE = SCALE / SimpleCloudsConstants.REGION_EDGE_FADE_FACTOR;
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final ModConfigSpec.BooleanValue REPLACE_CLOUD_RENDERING;
	private static final ModConfigSpec.BooleanValue DEBUG_LOG;
	private static final ModConfigSpec SPEC;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
		REPLACE_CLOUD_RENDERING = builder
				.comment("While an Iris shader pack is in use, switch Simple Clouds' own cloud drawing off and hand its clouds (regions and cloud fields) to the pack.",
						"Needs a pack that supports it, such as Lumarit; with other packs, set this to false to see Simple Clouds' own clouds.")
				.define("replaceCloudRendering", true);
		DEBUG_LOG = builder.comment("Every 5 seconds, log the region uniforms, slot 0, and each cloud field's state, GPU time and refresh count.")
				.define("debugLog", false);
		SPEC = builder.build();
	}

	/** This frame's uniform values, read by the Iris mixin. All 0 while the bridge isn't driving. */
	public static float active, cloudBase;
	public static int regionCount;
	public static final Vector3f scroll = new Vector3f();
	public static final Vector4f[] region = slots(), xform = slots(), shape = slots();

	private static final CloudsRenderPipeline NO_CLOUDS = new CloudsRenderPipeline() {
		@Override
		public void prepare(Minecraft mc, SimpleCloudsRenderer renderer, Matrix4f pose, Matrix4f projection, float partialTick, double camX, double camY, double camZ, Frustum frustum) {}

		@Override
		public void afterSky(Minecraft mc, SimpleCloudsRenderer renderer, Matrix4f pose, Matrix4f projection, float partialTick, double camX, double camY, double camZ, Frustum frustum) {}

		@Override
		public void beforeWeather(Minecraft mc, SimpleCloudsRenderer renderer, Matrix4f pose, Matrix4f projection, float partialTick, double camX, double camY, double camZ, Frustum frustum) {}

		@Override
		public void afterLevel(Minecraft mc, SimpleCloudsRenderer renderer, Matrix4f pose, Matrix4f projection, float partialTick, double camX, double camY, double camZ, Frustum frustum) {}
	};

	private static long nextLog;

	public LumaritScBridge(ModContainer container) {
		container.registerConfig(ModConfig.Type.CLIENT, SPEC);
		NeoForge.EVENT_BUS.addListener(LumaritScBridge::update);
		// Simple Clouds asks for its pipeline every frame, after RenderFrameEvent.Pre, so this follows the same frame's scActive.
		NeoForge.EVENT_BUS.addListener((DetermineCloudRenderPipelineEvent event) -> {
			if (active > 0) event.overridePipeline(NO_CLOUDS);
		});
	}

	private static void update(RenderFrameEvent.Pre event) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		// Same gate Simple Clouds puts in front of its pipeline event, so scActive is 1 exactly when the override happens.
		ScAPICloudManager manager = level != null && REPLACE_CLOUD_RENDERING.get() && IrisApi.getInstance().isShaderPackInUse()
				&& SimpleCloudsRenderer.canRenderInDimension(level) ? SimpleCloudsAPI.getApi().getCloudManager(level) : null;
		// ponytail: regions only exist in DEFAULT mode; SINGLE/AMBIENT (client on a server without the mod) keep the mod's own clouds.
		if (manager == null || manager.getCloudMode() != CloudMode.DEFAULT) {
			active = cloudBase = regionCount = 0;
			scroll.zero();
			for (int i = 0; i < SLOTS; i++) clear(i);
			CloudField.update(false, 0, false);
			return;
		}

		float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
		active = 1;
		cloudBase = manager.getCloudHeight();
		scroll.set(manager.getScrollX(pt), manager.getScrollY(pt), manager.getScrollZ(pt)).mul(SCALE);

		List<? extends ScAPICloudRegion> nearest = manager.getClouds().stream()
				.filter(r -> !r.isDead() && manager.getCloudTypeForId(r.getCloudTypeId()) != null)
				.sorted(Comparator.<ScAPICloudRegion>comparingDouble(r -> gap(r, cam, pt)).thenComparingInt(ScAPICloudRegion::getOrderWeight))
				.limit(SLOTS).toList();
		regionCount = nearest.size();
		for (int i = 0; i < SLOTS; i++) {
			if (i >= regionCount) {
				clear(i);
				continue;
			}
			ScAPICloudRegion r = nearest.get(i);
			ScAPICloudType type = manager.getCloudTypeForId(r.getCloudTypeId());
			// The API has no vertical extent; the internal record's noise knows the layers' lowest start and highest end.
			int start = 0, end = 0;
			if (type instanceof CloudType cloudType) {
				start = cloudType.noiseConfig().getStartHeight();
				end = cloudType.noiseConfig().getEndHeight();
			}
			Matrix2f t = r.createTransform(pt);
			region[i].set(r.getPosX(pt) * SCALE, r.getPosZ(pt) * SCALE, r.getRadius(pt) * SCALE, EDGE_FADE);
			xform[i].set(t.m00, t.m01, t.m10, t.m11);
			shape[i].set(cloudBase + SCALE * start, cloudBase + SCALE * end, type.storminess(), type.weatherType().ordinal());
		}
		CloudField.update(true, cloudBase, DEBUG_LOG.get());

		if (DEBUG_LOG.get() && Util.getMillis() >= nextLog) {
			nextLog = Util.getMillis() + 5000;
			LOGGER.info("scActive=1 scCloudBase={} scScroll={} scRegionCount={} slot0 {}: region={} xform={} shape={} {}", cloudBase, fmt(scroll.x, scroll.y, scroll.z),
					regionCount, regionCount > 0 ? nearest.get(0).getCloudTypeId() : "-", fmt(region[0]), fmt(xform[0]), fmt(shape[0]), CloudField.debug());
		}
	}

	/** How far the camera is outside the region's footprint, in blocks (0 inside). */
	private static double gap(ScAPICloudRegion r, Vec3 cam, float pt) {
		return Math.max(0, Math.hypot(cam.x - r.getPosX(pt) * SCALE, cam.z - r.getPosZ(pt) * SCALE) - r.getRadius(pt) * SCALE);
	}

	private static void clear(int i) {
		region[i].zero();
		xform[i].zero();
		shape[i].zero();
	}

	private static Vector4f[] slots() {
		Vector4f[] v = new Vector4f[SLOTS];
		for (int i = 0; i < SLOTS; i++) v[i] = new Vector4f();
		return v;
	}

	private static String fmt(Vector4f v) {
		return fmt(v.x, v.y, v.z, v.w);
	}

	private static String fmt(float... v) {
		StringBuilder s = new StringBuilder("(");
		for (int i = 0; i < v.length; i++) s.append(i == 0 ? "" : ", ").append(String.format("%.2f", v[i]));
		return s.append(')').toString();
	}
}
