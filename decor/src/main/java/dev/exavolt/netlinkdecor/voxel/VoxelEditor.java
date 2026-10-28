package dev.exavolt.netlinkdecor.voxel;

import dev.exavolt.netlinkdecor.DecorRegistry;
import dev.exavolt.netlinkdecor.item.ChiselSettings;
import dev.exavolt.netlinkdecor.item.VoxelChiselItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server side of the Voxel Chisel: applies a brush stroke to every block it touches. */
public final class VoxelEditor {
	private VoxelEditor() {
	}

	public static final int CARVE = 0;
	public static final int ADD = 1;
	public static final int PAINT = 2;
	public static final int PICK = 3;

	private static final int MAX_BLOCKS = 64;

	public static ItemStack chisel(ServerPlayer player) {
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack s = player.getItemInHand(hand);
			if (s.getItem() instanceof VoxelChiselItem) {
				return s;
			}
		}
		return ItemStack.EMPTY;
	}

	/** Brush offsets (in voxels) around the target voxel. */
	static List<int[]> brush(int size, int shape) {
		List<int[]> out = new ArrayList<>();
		int lo = -(size - 1) / 2, hi = size / 2;
		double c = (lo + hi) / 2.0, r = size / 2.0;
		for (int y = lo; y <= hi; y++) {
			for (int z = lo; z <= hi; z++) {
				for (int x = lo; x <= hi; x++) {
					if (shape == ChiselSettings.SPHERE && size > 2) {
						double dx = x - c, dy = y - c, dz = z - c;
						if (dx * dx + dy * dy + dz * dz > r * r) {
							continue;
						}
					}
					out.add(new int[]{x, y, z});
				}
			}
		}
		return out;
	}

	/** Voxels of a normal block, following its shape (stairs keep their steps). */
	static VoxelData fromBlock(ServerLevel level, BlockPos pos, BlockState state, int res) {
		VoxelShape shape = state.getShape(level, pos, CollisionContext.empty());
		List<AABB> boxes = shape.toAabbs();
		VoxelData d = VoxelData.empty(res);
		int m = d.indexOf(state);
		for (int y = 0; y < res; y++) {
			for (int z = 0; z < res; z++) {
				for (int x = 0; x < res; x++) {
					double cx = (x + 0.5) / res, cy = (y + 0.5) / res, cz = (z + 0.5) / res;
					for (AABB b : boxes) {
						if (cx >= b.minX && cx <= b.maxX && cy >= b.minY && cy <= b.maxY && cz >= b.minZ && cz <= b.maxZ) {
							d.set(x, y, z, m);
							break;
						}
					}
				}
			}
		}
		return d;
	}

	static boolean sculptable(ServerLevel level, BlockPos pos, BlockState state) {
		return !state.isAir() && !state.hasBlockEntity() && state.getFluidState().isEmpty() && state.getDestroySpeed(level, pos) >= 0
			&& !state.getShape(level, pos, CollisionContext.empty()).isEmpty();
	}

	public static void use(ServerPlayer player, long gx, long gy, long gz, int res, int action) {
		ServerLevel level = player.level();
		ItemStack chisel = chisel(player);
		if (chisel.isEmpty() || !player.mayBuild()) {
			return;
		}
		ChiselSettings s = VoxelChiselItem.settings(chisel);
		if (res != s.res()) {
			return;
		}
		BlockPos center = BlockPos.containing(Math.floorDiv(gx, res), Math.floorDiv(gy, res), Math.floorDiv(gz, res));
		if (player.distanceToSqr(center.getCenter()) > 10 * 10) {
			return;
		}

		if (action == PICK) {
			pick(player, chisel, level, center, (int) Math.floorMod(gx, res), (int) Math.floorMod(gy, res), (int) Math.floorMod(gz, res), res);
			return;
		}
		BlockState material = null;
		int mode = action;
		if (action == ADD || action == PAINT) {
			material = VoxelChiselItem.material(player, chisel);
			if (material == null || material.getBlock() instanceof VoxelBlock) {
				player.sendOverlayMessage(Component.translatable("message.netlinkdecor.no_material").withStyle(ChatFormatting.GOLD));
				return;
			}
		}

		// group the brush voxels by block
		Map<BlockPos, List<int[]>> perBlock = new LinkedHashMap<>();
		for (int[] o : brush(s.brush(), s.shape())) {
			long x = gx + o[0], y = gy + o[1], z = gz + o[2];
			BlockPos bp = BlockPos.containing(Math.floorDiv(x, res), Math.floorDiv(y, res), Math.floorDiv(z, res));
			perBlock.computeIfAbsent(bp, k -> new ArrayList<>())
				.add(new int[]{(int) Math.floorMod(x, res), (int) Math.floorMod(y, res), (int) Math.floorMod(z, res)});
			if (perBlock.size() > MAX_BLOCKS) {
				return;
			}
		}

		boolean changed = false;
		BlockState soundOf = null;
		for (Map.Entry<BlockPos, List<int[]>> e : perBlock.entrySet()) {
			BlockPos pos = e.getKey();
			if (!level.isLoaded(pos) || !level.mayInteract(player, pos) || !level.getWorldBorder().isWithinBounds(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			VoxelData data;
			boolean wasVoxel = state.getBlock() instanceof VoxelBlock;
			if (wasVoxel && level.getBlockEntity(pos) instanceof VoxelBlockEntity be) {
				data = be.data().upsample(Math.max(res, be.data().res));
			} else if (mode == ADD && state.canBeReplaced()) {
				data = VoxelData.empty(res);
				state = DecorRegistry.VOXEL_BLOCK.defaultBlockState();
			} else if ((mode == CARVE || mode == PAINT) && sculptable(level, pos, state)) {
				soundOf = state;
				data = fromBlock(level, pos, state, res);
				state = DecorRegistry.VOXEL_BLOCK.defaultBlockState();
			} else {
				continue;
			}
			int steps = VoxelBlock.steps(state);
			boolean mirrored = state.getValue(VoxelBlock.MIRRORED);
			int mat = material == null ? 0 : data.indexOf(material);
			if (mat < 0) {
				player.sendOverlayMessage(Component.translatable("message.netlinkdecor.too_many_materials").withStyle(ChatFormatting.RED));
				continue;
			}
			int k = data.res / res;
			boolean touched = false;
			for (int[] v : e.getValue()) {
				for (int i = 0; i < k; i++) {
					for (int j = 0; j < k; j++) {
						for (int l = 0; l < k; l++) {
							int[] c = VoxelData.toCanonical(v[0] * k + i, v[1] * k + j, v[2] * k + l, data.res, steps, mirrored);
							int cur = data.get(c[0], c[1], c[2]);
							int next = switch (mode) {
								case CARVE -> 0;
								case ADD -> cur == 0 ? mat : cur;
								default -> cur == 0 ? 0 : mat;
							};
							if (next != cur) {
								if (soundOf == null && cur != 0) {
									soundOf = data.material(cur);
								}
								data.set(c[0], c[1], c[2], next);
								touched = true;
							}
						}
					}
				}
			}
			if (!touched) {
				continue;
			}
			changed = true;
			data.compact();
			commit(level, pos, state, wasVoxel, data);
		}
		if (changed) {
			BlockState s2 = mode == CARVE ? soundOf : material;
			if (s2 != null) {
				var sound = s2.getSoundType();
				level.playSound(null, center, mode == CARVE ? sound.getBreakSound() : sound.getPlaceSound(), SoundSource.BLOCKS,
					0.45F, sound.getPitch() * 1.25F);
			}
		}
	}

	/** Writes the new voxels: empty blocks vanish, a solid single-material block becomes that block again. */
	private static void commit(ServerLevel level, BlockPos pos, BlockState state, boolean wasVoxel, VoxelData data) {
		if (data.isEmpty()) {
			level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			return;
		}
		BlockState uniform = data.uniform();
		if (uniform != null && Block.isShapeFullBlock(uniform.getShape(level, pos, CollisionContext.empty()))) {
			level.setBlock(pos, uniform, Block.UPDATE_ALL);
			return;
		}
		if (!wasVoxel) {
			level.setBlock(pos, state, Block.UPDATE_ALL);
		}
		if (level.getBlockEntity(pos) instanceof VoxelBlockEntity be) {
			be.setData(data);
		}
	}

	private static void pick(ServerPlayer player, ItemStack chisel, ServerLevel level, BlockPos pos, int x, int y, int z, int res) {
		BlockState state = level.getBlockState(pos);
		BlockState picked = null;
		if (state.getBlock() instanceof VoxelBlock && level.getBlockEntity(pos) instanceof VoxelBlockEntity be) {
			VoxelData d = be.data();
			int k = Math.max(1, d.res / res);
			int f = Math.max(1, res / d.res);
			int[] c = VoxelData.toCanonical(x * k / f, y * k / f, z * k / f, d.res, VoxelBlock.steps(state), state.getValue(VoxelBlock.MIRRORED));
			int i = d.get(c[0], c[1], c[2]);
			if (i != 0) {
				picked = d.material(i);
			}
		} else if (!state.isAir() && !state.hasBlockEntity()) {
			picked = state;
		}
		if (picked == null) {
			return;
		}
		chisel.set(DecorRegistry.CHISEL_SETTINGS, VoxelChiselItem.settings(chisel).withMaterial(picked));
		player.sendOverlayMessage(Component.translatable("message.netlinkdecor.picked", picked.getBlock().getName()).withStyle(ChatFormatting.AQUA));
	}

	public static void applySettings(ServerPlayer player, ChiselSettings settings) {
		ItemStack chisel = chisel(player);
		if (!chisel.isEmpty()) {
			ChiselSettings cur = VoxelChiselItem.settings(chisel);
			ChiselSettings next = new ChiselSettings(settings.res(), settings.brush(), settings.shape(), settings.mode(), cur.material()).sanitized();
			chisel.set(DecorRegistry.CHISEL_SETTINGS, next);
		}
	}
}
