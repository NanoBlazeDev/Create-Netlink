package dev.exavolt.netlink.mixin.client;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSources;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Netlink add its own atlas sprite source type ({@code createnetlink:recolor}). */
@Mixin(SpriteSources.class)
public interface SpriteSourcesAccessor {
	@Accessor("ID_MAPPER")
	static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends SpriteSource>> netlink$idMapper() {
		throw new AssertionError();
	}
}
