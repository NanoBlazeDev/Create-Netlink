package dev.exavolt.netlink.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import dev.exavolt.netlink.Netlink;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Faint terrain relief under the network map, sampled from the chunks the client has loaded. Rendered at half the
 * map's resolution into a dynamic texture and rebuilt a few times per second at most.
 */
final class MapTerrain implements AutoCloseable {
	private static final Identifier ID = Netlink.id("dynamic/network_map_terrain");
	private static final int UNLOADED = 0xFF0B0F16;
	private static final int TINT = 0x0E1622;
	private static final float TINT_AMOUNT = 0.58f;

	private DynamicTexture texture;
	private int texW;
	private int texH;
	private double builtX = Double.NaN;
	private double builtZ;
	private double builtScale;
	private long builtAt;

	void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, double camX, double camZ, double scale) {
		int tw = Math.max(1, w / 2);
		int th = Math.max(1, h / 2);
		ensure(tw, th);
		long now = System.currentTimeMillis();
		boolean moved = Double.isNaN(builtX) || Math.abs(builtX - camX) * scale > 1 || Math.abs(builtZ - camZ) * scale > 1
			|| Math.abs(builtScale - scale) > 1e-6;
		if ((moved && now - builtAt > 120) || now - builtAt > 2000) {
			rebuild(camX, camZ, scale, w, h);
			builtAt = now;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, ID, x, y, 0f, 0f, w, h, texW, texH, texW, texH);
	}

	private void ensure(int w, int h) {
		if (texture != null && texW == w && texH == h) {
			return;
		}
		close();
		texW = w;
		texH = h;
		texture = new DynamicTexture(() -> "Netlink map terrain", new NativeImage(w, h, true));
		Minecraft.getInstance().getTextureManager().register(ID, texture);
		builtX = Double.NaN;
	}

	private void rebuild(double camX, double camZ, double scale, int w, int h) {
		ClientLevel level = Minecraft.getInstance().level;
		NativeImage img = texture.getPixels();
		if (level == null || img == null) {
			return;
		}
		Long2IntOpenHashMap heights = new Long2IntOpenHashMap();
		Long2IntOpenHashMap colors = new Long2IntOpenHashMap();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		double cx = w / 2.0, cy = h / 2.0;
		for (int py = 0; py < texH; py++) {
			for (int px = 0; px < texW; px++) {
				int wx = (int) Math.floor(camX + (px * 2 + 1 - cx) / scale);
				int wz = (int) Math.floor(camZ + (py * 2 + 1 - cy) / scale);
				long key = BlockPos.asLong(wx, 0, wz);
				int color;
				if (colors.containsKey(key)) {
					color = colors.get(key);
				} else {
					color = sample(level, wx, wz, heights, pos);
					colors.put(key, color);
				}
				img.setPixel(px, py, color);
			}
		}
		texture.upload();
		builtX = camX;
		builtZ = camZ;
		builtScale = scale;
	}

	private static int height(ClientLevel level, int x, int z, Long2IntOpenHashMap cache) {
		long key = BlockPos.asLong(x, 0, z);
		if (cache.containsKey(key)) {
			return cache.get(key);
		}
		int h = level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) : Integer.MIN_VALUE;
		cache.put(key, h);
		return h;
	}

	private static int sample(ClientLevel level, int x, int z, Long2IntOpenHashMap heights, BlockPos.MutableBlockPos pos) {
		int h = height(level, x, z, heights);
		if (h == Integer.MIN_VALUE) {
			return UNLOADED;
		}
		MapColor mc = MapColor.NONE;
		int y = h - 1;
		for (int k = 0; k < 4 && mc == MapColor.NONE && y >= level.getMinY(); k++, y--) {
			pos.set(x, y, z);
			BlockState state = level.getBlockState(pos);
			mc = state.getMapColor(level, pos);
		}
		if (mc == MapColor.NONE) {
			return UNLOADED;
		}
		int north = height(level, x, z - 1, heights);
		MapColor.Brightness b = MapColor.Brightness.NORMAL;
		if (north != Integer.MIN_VALUE) {
			if (h > north) {
				b = MapColor.Brightness.HIGH;
			} else if (h < north) {
				b = MapColor.Brightness.LOW;
			}
		}
		if (mc == MapColor.WATER) {
			b = MapColor.Brightness.LOW;
		}
		int argb = mc.calculateARGBColor(b);
		return 0xFF000000 | mix(argb, TINT, TINT_AMOUNT);
	}

	private static int mix(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	@Override
	public void close() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(ID);
			texture = null;
		}
	}
}
