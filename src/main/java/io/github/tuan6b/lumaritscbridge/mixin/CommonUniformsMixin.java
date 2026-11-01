package io.github.tuan6b.lumaritscbridge.mixin;

import io.github.tuan6b.lumaritscbridge.CloudField;
import io.github.tuan6b.lumaritscbridge.LumaritScBridge;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

/**
 * Iris has no API for extra uniforms. addNonDynamicUniforms builds the input set every program of a pack gets, so adding
 * to it reaches gbuffers, composites, final and computes. Values are computed once per frame in LumaritScBridge.
 */
@Mixin(value = CommonUniforms.class, remap = false)
public class CommonUniformsMixin {
	@Inject(method = "addNonDynamicUniforms", at = @At("TAIL"))
	private static void lumaritScBridge$addCloudUniforms(UniformHolder uniforms, IdMap idMap, PackDirectives directives,
			FrameUpdateNotifier updateNotifier, CallbackInfo ci) {
		uniforms.uniform1f(PER_FRAME, "scActive", (FloatSupplier) () -> LumaritScBridge.active)
				.uniform1f(PER_FRAME, "scCloudBase", (FloatSupplier) () -> LumaritScBridge.cloudBase)
				.uniform3f(PER_FRAME, "scScroll", () -> LumaritScBridge.scroll)
				.uniform1i(PER_FRAME, "scRegionCount", () -> LumaritScBridge.regionCount)
				.uniform1f(PER_FRAME, "scFieldCut", (FloatSupplier) () -> CloudField.cut)
				.uniform3f(PER_FRAME, "scFieldOrigin", () -> CloudField.WIDE.origin)
				.uniform3f(PER_FRAME, "scFieldCell", () -> CloudField.WIDE.cell)
				.uniform3f(PER_FRAME, "scFieldDims", () -> CloudField.WIDE.dims)
				.uniform1f(PER_FRAME, "scFieldValid", (FloatSupplier) () -> CloudField.WIDE.valid)
				.uniform3f(PER_FRAME, "scFieldNearOrigin", () -> CloudField.NEAR.origin)
				.uniform3f(PER_FRAME, "scFieldNearCell", () -> CloudField.NEAR.cell)
				.uniform3f(PER_FRAME, "scFieldNearDims", () -> CloudField.NEAR.dims)
				.uniform1f(PER_FRAME, "scFieldNearValid", (FloatSupplier) () -> CloudField.NEAR.valid)
				.uniform3f(PER_FRAME, "scFieldFarOrigin", () -> CloudField.FAR.origin)
				.uniform3f(PER_FRAME, "scFieldFarCell", () -> CloudField.FAR.cell)
				.uniform3f(PER_FRAME, "scFieldFarDims", () -> CloudField.FAR.dims)
				.uniform1f(PER_FRAME, "scFieldFarValid", (FloatSupplier) () -> CloudField.FAR.valid)
				.uniform1f(PER_FRAME, "scCloudRadius", (FloatSupplier) () -> CloudField.cloudRadius);
		for (int i = 0; i < LumaritScBridge.SLOTS; i++) {
			Vector4f region = LumaritScBridge.region[i], xform = LumaritScBridge.xform[i], shape = LumaritScBridge.shape[i];
			uniforms.uniform4f(PER_FRAME, "scRegion" + i, () -> region)
					.uniform4f(PER_FRAME, "scRegionXform" + i, () -> xform)
					.uniform4f(PER_FRAME, "scRegionShape" + i, () -> shape);
		}
	}
}
