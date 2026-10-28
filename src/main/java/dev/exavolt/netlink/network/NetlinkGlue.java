package dev.exavolt.netlink.network;

import com.zurrtum.create.api.contraption.BlockMovementChecks;
import com.zurrtum.create.content.contraptions.glue.SuperGlueEntity;
import dev.exavolt.netlink.item.NetlinkGlueItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Netlink Glue: Create's Super Glue without the range limits. Spawns ordinary Create glue entities, so contraptions
 * and Wall Extenders treat it exactly like Super Glue.
 */
public final class NetlinkGlue {
	private NetlinkGlue() {
	}

	/** Above this volume a selection is glued as-is instead of being shrunk to the blocks inside. */
	private static final long SHRINK_VOLUME_LIMIT = 4_000_000L;
	public static final int AUTO_RADIUS = 24;
	public static final int AUTO_LIMIT = 8192;

	private static boolean holdingGlue(ServerPlayer player) {
		return player.getMainHandItem().getItem() instanceof NetlinkGlueItem || player.getOffhandItem().getItem() instanceof NetlinkGlueItem;
	}

	private static boolean movable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && BlockMovementChecks.isMovementNecessary(state, level, pos);
	}

	/** Selection corners may be anywhere, even in mid-air; the box is shrunk around whatever blocks it contains. */
	public static void apply(ServerPlayer player, BlockPos a, BlockPos b) {
		if (!holdingGlue(player)) {
			return;
		}
		ServerLevel level = player.level();
		int x0 = Math.min(a.getX(), b.getX()), y0 = Math.min(a.getY(), b.getY()), z0 = Math.min(a.getZ(), b.getZ());
		int x1 = Math.max(a.getX(), b.getX()), y1 = Math.max(a.getY(), b.getY()), z1 = Math.max(a.getZ(), b.getZ());
		y0 = Math.max(y0, level.getMinY());
		y1 = Math.min(y1, level.getMaxY());
		long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
		AABB box;
		if (volume <= SHRINK_VOLUME_LIMIT) {
			int mx0 = Integer.MAX_VALUE, my0 = Integer.MAX_VALUE, mz0 = Integer.MAX_VALUE;
			int mx1 = Integer.MIN_VALUE, my1 = Integer.MIN_VALUE, mz1 = Integer.MIN_VALUE;
			int found = 0;
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					if (!level.isLoaded(m.set(x, y0, z))) {
						continue;
					}
					for (int y = y0; y <= y1; y++) {
						if (movable(level, m.set(x, y, z))) {
							found++;
							mx0 = Math.min(mx0, x);
							my0 = Math.min(my0, y);
							mz0 = Math.min(mz0, z);
							mx1 = Math.max(mx1, x);
							my1 = Math.max(my1, y);
							mz1 = Math.max(mz1, z);
						}
					}
				}
			}
			if (found < 2) {
				player.sendOverlayMessage(Component.translatable("message.createnetlink.glue_nothing").withStyle(ChatFormatting.GOLD));
				return;
			}
			box = new AABB(mx0, my0, mz0, mx1 + 1, my1 + 1, mz1 + 1);
		} else {
			box = new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
		}
		spawn(player, level, box);
	}

	/** Flood fill from pos through connected blocks (natural terrain excluded) and glue the result. */
	public static void auto(ServerPlayer player, BlockPos origin) {
		if (!holdingGlue(player)) {
			return;
		}
		ServerLevel level = player.level();
		if (!movable(level, origin)) {
			return;
		}
		Set<BlockPos> visited = new HashSet<>();
		ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		visited.add(origin);
		frontier.add(origin);
		int mx0 = origin.getX(), my0 = origin.getY(), mz0 = origin.getZ(), mx1 = mx0, my1 = my0, mz1 = mz0;
		while (!frontier.isEmpty() && visited.size() < AUTO_LIMIT) {
			BlockPos cur = frontier.poll();
			mx0 = Math.min(mx0, cur.getX());
			my0 = Math.min(my0, cur.getY());
			mz0 = Math.min(mz0, cur.getZ());
			mx1 = Math.max(mx1, cur.getX());
			my1 = Math.max(my1, cur.getY());
			mz1 = Math.max(mz1, cur.getZ());
			for (Direction d : Direction.values()) {
				BlockPos next = cur.relative(d);
				if (visited.contains(next) || next.distManhattan(origin) > AUTO_RADIUS * 3
					|| Math.abs(next.getX() - origin.getX()) > AUTO_RADIUS || Math.abs(next.getY() - origin.getY()) > AUTO_RADIUS
					|| Math.abs(next.getZ() - origin.getZ()) > AUTO_RADIUS) {
					continue;
				}
				if (!movable(level, next) || isTerrain(level.getBlockState(next))) {
					continue;
				}
				visited.add(next);
				frontier.add(next);
			}
		}
		if (visited.size() < 2) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.glue_nothing").withStyle(ChatFormatting.GOLD));
			return;
		}
		spawn(player, level, new AABB(mx0, my0, mz0, mx1 + 1, my1 + 1, mz1 + 1));
		player.sendOverlayMessage(Component.translatable("message.createnetlink.glue_auto", visited.size()).withStyle(ChatFormatting.AQUA));
	}

	private static boolean isTerrain(BlockState state) {
		return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
			|| state.is(BlockTags.SAND) || state.is(BlockTags.SNOW) || state.is(BlockTags.TERRACOTTA)
			|| state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(BlockTags.ICE);
	}

	/**
	 * Create looks glue up through entity sections only ~18 blocks around a block, so a huge glue entity would be
	 * invisible to contraptions far from its centre. Big selections are therefore tiled into 16-block glue entities
	 * that overlap by one block, which keeps every neighbouring pair inside a single entity.
	 */
	private static final int TILE = 16;
	private static final int MAX_TILES = 4096;

	private static void spawn(ServerPlayer player, ServerLevel level, AABB box) {
		int x0 = (int) Math.floor(box.minX), y0 = (int) Math.floor(box.minY), z0 = (int) Math.floor(box.minZ);
		int x1 = (int) Math.round(box.maxX), y1 = (int) Math.round(box.maxY), z1 = (int) Math.round(box.maxZ);
		int[][] xs = cuts(x0, x1), ys = cuts(y0, y1), zs = cuts(z0, z1);
		long tiles = (long) xs.length * ys.length * zs.length;
		if (tiles > MAX_TILES) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.glue_too_big").withStyle(ChatFormatting.RED));
			return;
		}
		for (int[] xi : xs) {
			for (int[] yi : ys) {
				for (int[] zi : zs) {
					AABB tile = new AABB(xi[0], yi[0], zi[0], xi[1], yi[1], zi[1]);
					SuperGlueEntity entity = new SuperGlueEntity(level, tile);
					level.addFreshEntity(entity);
					if (tiles <= 64) {
						entity.spawnParticles();
					}
				}
			}
		}
		level.playSound(null, BlockPos.containing(box.getCenter()), SoundEvents.SLIME_BLOCK_PLACE, SoundSource.PLAYERS, 0.8F, 1.2F);
	}

	/** Tile spans along one axis, each at most 16 long, neighbours overlapping by one block. */
	private static int[][] cuts(int lo, int hi) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		int start = lo;
		while (true) {
			int end = Math.min(hi, start + TILE);
			out.add(new int[]{start, end});
			if (end >= hi) {
				break;
			}
			start = end - 1;
		}
		return out.toArray(new int[0][]);
	}

	/**
	 * Whether two glue boxes are neighbouring tiles of one selection: the same extent on two axes and exactly one block
	 * of overlap on the third, the way {@link #spawn} cuts them. Separate glue boxes that merely touch or overlap don't
	 * match, so removing one never takes the others with it.
	 */
	private static boolean neighbourTiles(AABB a, AABB b) {
		boolean sx = same(a.minX, b.minX) && same(a.maxX, b.maxX);
		boolean sy = same(a.minY, b.minY) && same(a.maxY, b.maxY);
		boolean sz = same(a.minZ, b.minZ) && same(a.maxZ, b.maxZ);
		boolean ox = oneBlock(a.minX, a.maxX, b.minX, b.maxX);
		boolean oy = oneBlock(a.minY, a.maxY, b.minY, b.maxY);
		boolean oz = oneBlock(a.minZ, a.maxZ, b.minZ, b.maxZ);
		return sy && sz && ox || sx && sz && oy || sx && sy && oz;
	}

	private static boolean same(double a, double b) {
		return Math.abs(a - b) < 0.01;
	}

	private static boolean oneBlock(double a0, double a1, double b0, double b1) {
		return same(a1, b0 + 1) && a0 < b0 || same(b1, a0 + 1) && b0 < a0;
	}

	public static void remove(ServerPlayer player, int entityId) {
		if (!holdingGlue(player)) {
			return;
		}
		Entity entity = player.level().getEntity(entityId);
		if (entity instanceof SuperGlueEntity glue) {
			// a tiled selection is removed as a whole, but only its own tiles, not other glue boxes nearby
			java.util.Set<SuperGlueEntity> group = new java.util.HashSet<>();
			ArrayDeque<SuperGlueEntity> q = new ArrayDeque<>();
			group.add(glue);
			q.add(glue);
			while (!q.isEmpty() && group.size() < MAX_TILES) {
				SuperGlueEntity cur = q.poll();
				AABB cb = cur.getBoundingBox();
				for (SuperGlueEntity other : player.level().getEntitiesOfClass(SuperGlueEntity.class, cb.inflate(1))) {
					boolean tile = other.getBoundingBox().getXsize() <= TILE && other.getBoundingBox().getYsize() <= TILE
						&& other.getBoundingBox().getZsize() <= TILE;
					if (tile && neighbourTiles(cb, other.getBoundingBox()) && group.add(other)) {
						q.add(other);
					}
				}
			}
			group.forEach(Entity::discard);
			player.level().playSound(null, BlockPos.containing(glue.getBoundingBox().getCenter()), SoundEvents.SLIME_BLOCK_BREAK,
				SoundSource.PLAYERS, 0.8F, 1.0F);
		}
	}
}
