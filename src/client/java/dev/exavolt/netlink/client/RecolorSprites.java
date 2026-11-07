package dev.exavolt.netlink.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.exavolt.netlink.mixin.client.SpriteSourcesAccessor;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Atlas sprite source {@code createnetlink:recolor}: builds a sprite at load time from another mod's texture.
 * <p>
 * Some Netlink blocks are recoloured versions of Create's (the advanced link is a cyan redstone link). Create's
 * textures are All Rights Reserved, so the jar doesn't carry copies: it carries only a colour table and our own extra
 * pixels, and the game recolours Create's texture from the player's own Create install while loading.
 * <ul>
 *   <li>{@code source}: texture to start from (e.g. {@code create:block/redstone_antenna})</li>
 *   <li>{@code sprite}: id of the sprite this makes</li>
 *   <li>{@code palette} (optional): 2-row image, top row = colours to replace, bottom row = their replacements</li>
 *   <li>{@code overlay} (optional): same-size image drawn on top (our own pixels; transparent elsewhere)</li>
 * </ul>
 */
public record RecolorSprites(Identifier source, Identifier sprite, Optional<Identifier> palette, Optional<Identifier> overlay)
	implements SpriteSource {
	private static final Logger LOGGER = LoggerFactory.getLogger("Netlink");

	public static final MapCodec<RecolorSprites> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		Identifier.CODEC.fieldOf("source").forGetter(RecolorSprites::source),
		Identifier.CODEC.fieldOf("sprite").forGetter(RecolorSprites::sprite),
		Identifier.CODEC.optionalFieldOf("palette").forGetter(RecolorSprites::palette),
		Identifier.CODEC.optionalFieldOf("overlay").forGetter(RecolorSprites::overlay)
	).apply(i, RecolorSprites::new));

	public static void register() {
		SpriteSourcesAccessor.netlink$idMapper().put(Identifier.fromNamespaceAndPath("createnetlink", "recolor"), MAP_CODEC);
	}

	@Override
	public void run(ResourceManager resources, SpriteSource.Output output) {
		Optional<Resource> base = resources.getResource(TEXTURE_ID_CONVERTER.idToFile(source));
		if (base.isEmpty()) {
			LOGGER.warn("Texture {} for {} not found (is Create installed?)", source, sprite);
			return;
		}
		output.add(sprite, (SpriteSource.DiscardableLoader) loader -> build(resources, base.get()));
	}

	private SpriteContents build(ResourceManager resources, Resource base) {
		try {
			NativeImage image = read(base);
			if (palette.isPresent()) {
				try (NativeImage pal = read(resources, palette.get())) {
					Int2IntOpenHashMap map = new Int2IntOpenHashMap();
					for (int x = 0; x < pal.getWidth(); x++) {
						map.put(pal.getPixel(x, 0), pal.getPixel(x, 1));
					}
					for (int y = 0; y < image.getHeight(); y++) {
						for (int x = 0; x < image.getWidth(); x++) {
							int c = image.getPixel(x, y);
							if (ARGB.alpha(c) != 0 && map.containsKey(c)) {
								image.setPixel(x, y, map.get(c));
							}
						}
					}
				}
			}
			if (overlay.isPresent()) {
				try (NativeImage top = read(resources, overlay.get())) {
					int w = Math.min(top.getWidth(), image.getWidth());
					int h = Math.min(top.getHeight(), image.getHeight());
					for (int y = 0; y < h; y++) {
						for (int x = 0; x < w; x++) {
							int c = top.getPixel(x, y);
							if (ARGB.alpha(c) != 0) {
								image.setPixel(x, y, c);
							}
						}
					}
				}
			}
			return new SpriteContents(sprite, new FrameSize(image.getWidth(), image.getHeight()), image);
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Couldn't build sprite {} from {}", sprite, source, e);
			return null;
		}
	}

	private static NativeImage read(ResourceManager resources, Identifier id) throws IOException {
		Resource r = resources.getResource(TEXTURE_ID_CONVERTER.idToFile(id)).orElseThrow(() -> new IOException("missing " + id));
		return read(r);
	}

	private static NativeImage read(Resource r) throws IOException {
		try (InputStream in = r.open()) {
			return NativeImage.read(in);
		}
	}

	@Override
	public MapCodec<RecolorSprites> codec() {
		return MAP_CODEC;
	}
}
