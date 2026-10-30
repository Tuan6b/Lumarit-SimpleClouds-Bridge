package io.github.tuan6b.lumaritscbridge.mixin;

import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.shader.compute.ComputeShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The mod's cube_mesh program: the cloud field copies its uniforms and storage buffers. */
@Mixin(value = CloudMeshGenerator.class, remap = false)
public interface CloudMeshGeneratorAccessor {
	@Accessor("shader")
	ComputeShader lumaritScBridge$shader();
}
