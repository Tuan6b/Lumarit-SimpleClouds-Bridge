package io.github.tuan6b.lumaritscbridge.mixin;

import dev.nonamecrackers2.simpleclouds.client.mesh.generator.MultiRegionCloudMeshGenerator;
import io.github.tuan6b.lumaritscbridge.CloudField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The region texture is only ever rewritten here; its centre (cloud units) is what maps field texels onto it. */
@Mixin(value = MultiRegionCloudMeshGenerator.class, remap = false)
public class MultiRegionCloudMeshGeneratorMixin {
	@Inject(method = "runRegionGenerator", at = @At("HEAD"))
	private void lumaritScBridge$regionsGenerated(float x, float z, float partialTick, CallbackInfo ci) {
		CloudField.onRegionsGenerated(x, z);
	}
}
