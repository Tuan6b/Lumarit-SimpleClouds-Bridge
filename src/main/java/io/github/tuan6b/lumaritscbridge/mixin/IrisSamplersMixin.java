package io.github.tuan6b.lumaritscbridge.mixin;

import io.github.tuan6b.lumaritscbridge.CloudField;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.samplers.IrisSamplers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.IntSupplier;

/**
 * The pack declares scField, scFieldNear and scFieldFar as customTextures (1x1x1 placeholders, so the samplers are always bound 3D
 * textures). Every program kind binds the pack's custom textures here; while a field is valid, its entry hands Iris our
 * texture instead of the placeholder.
 */
@Mixin(value = IrisSamplers.class, remap = false)
public class IrisSamplersMixin {
	@ModifyVariable(method = "addCustomTextures", at = @At("HEAD"), argsOnly = true)
	private static Object2ObjectMap<String, TextureAccess> lumaritScBridge$swapCloudFields(Object2ObjectMap<String, TextureAccess> textures,
			SamplerHolder samplers) {
		Object2ObjectMap<String, TextureAccess> swapped = new Object2ObjectOpenHashMap<>(textures);
		lumaritScBridge$swap(swapped, "scField", CloudField.WIDE);
		lumaritScBridge$swap(swapped, "scFieldNear", CloudField.NEAR);
		lumaritScBridge$swap(swapped, "scFieldFar", CloudField.FAR);
		return swapped;
	}

	@Unique
	private static void lumaritScBridge$swap(Object2ObjectMap<String, TextureAccess> textures, String name, CloudField field) {
		TextureAccess pack = textures.get(name);
		if (pack == null || pack.getType() != TextureType.TEXTURE_3D)
			return;
		IntSupplier placeholder = pack.getTextureId();
		IntSupplier id = () -> field.valid > 0 ? field.textureId() : placeholder.getAsInt();
		textures.put(name, new TextureAccess() {
			@Override
			public TextureType getType() {
				return TextureType.TEXTURE_3D;
			}

			@Override
			public IntSupplier getTextureId() {
				return id;
			}
		});
	}
}
