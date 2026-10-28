package dev.exavolt.netlink.network;

import com.zurrtum.create.api.contraption.BlockMovementChecks;
import com.zurrtum.create.content.contraptions.glue.SuperGlueEntity;
import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.block.ExtenderData;
import dev.exavolt.netlink.block.ExtenderGhostBlock;
import dev.exavolt.netlink.mixin.ServerGamePacketListenerAccessor;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;
import dev.exavolt.netlink.block.WallExtenderBlock;
import dev.exavolt.netlink.block.WallExtenderBlockEntity;
import dev.exavolt.netlink.item.WallExtenderItem;
import dev.exavolt.netlink.net.NetlinkPackets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assembly, movement and disassembly of Wall Extenders. */
public final class WallExtenders {
	private WallExtenders() {
	}

	public static final int MAX_BLOCKS = 4096;
	/** Moving blocks: update clients, skip drops / shape updates / onPlace / BE side effects; neighbours are notified after. */
	private static final int MOVE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	private static final Set<BlockPos> disassembling = new HashSet<>();
	private static final List<Runnable> pending = new ArrayList<>();

	static void tick() {
		if (pending.isEmpty()) {
			return;
		}
		List<Runnable> jobs = new ArrayList<>(pending);
		pending.clear();
		jobs.forEach(Runnable::run);
	}

	private static boolean movable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && !(state.getBlock() instanceof WallExtenderBlock) && BlockMovementChecks.isMovementNecessary(state, level, pos);
	}

	/** Blocks connected to start through glue (Create Super Glue or Netlink Glue). Null + message on failure. */
	static @Nullable Set<BlockPos> gather(ServerPlayer player, ServerLevel level, BlockPos start) {
		if (!movable(level, start)) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.extender_not_glued").withStyle(ChatFormatting.GOLD));
			return null;
		}
		Set<SuperGlueEntity> cache = new HashSet<>();
		Set<BlockPos> found = new LinkedHashSet<>();
		ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		found.add(start.immutable());
		frontier.add(start.immutable());
		while (!frontier.isEmpty()) {
			BlockPos cur = frontier.poll();
			for (Direction d : Direction.values()) {
				BlockPos next = cur.relative(d);
				if (found.contains(next) || !movable(level, next)) {
					continue;
				}
				if (!SuperGlueEntity.isGlued(level, cur, d, cache)) {
					continue;
				}
				found.add(next);
				frontier.add(next);
				if (found.size() > MAX_BLOCKS) {
					player.sendOverlayMessage(Component.translatable("message.createnetlink.extender_too_big", MAX_BLOCKS)
						.withStyle(ChatFormatting.RED));
					return null;
				}
			}
		}
		if (found.size() < 2) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.extender_not_glued").withStyle(ChatFormatting.GOLD));
			return null;
		}
		int entities = 0;
		for (BlockPos p : found) {
			if (level.getBlockEntity(p) != null) {
				entities++;
			}
		}
		if (entities > 0) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.extender_block_entities", entities)
				.withStyle(ChatFormatting.RED));
			return null;
		}
		return found;
	}

	/** First click with the extender item: find the glued shape and let the player choose the side. */
	public static void beginAssembly(ServerPlayer player, ServerLevel level, BlockPos clicked) {
		Set<BlockPos> structure = gather(player, level, clicked);
		if (structure == null) {
			return;
		}
		ServerPlayNetworking.send(player, new NetlinkPackets.OpenExtenderAssembly(clicked, List.copyOf(structure)));
	}

	private static int height(BlockPos p, Direction up) {
		return p.getX() * up.getStepX() + p.getY() * up.getStepY() + p.getZ() * up.getStepZ();
	}

	/** The extender is built into existing blocks: full, solid, breakable blocks without block entities. */
	public static boolean canHostPlate(net.minecraft.world.level.BlockGetter level, BlockPos p) {
		BlockState state = level.getBlockState(p);
		return !state.isAir() && state.isCollisionShapeFullBlock(level, p) && state.getDestroySpeed(level, p) >= 0
			&& level.getBlockEntity(p) == null && !(state.getBlock() instanceof WallExtenderBlock) && !isGhost(state)
			&& state.getFluidState().isEmpty();
	}

	/**
	 * Breaking any plate never breaks the block: the whole extender comes off instead (the wall stands back up and every
	 * plate turns back into the block it was built into), after which everything can be broken normally.
	 */
	public static void registerEvents() {
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (!(state.getBlock() instanceof WallExtenderBlock) || !(level instanceof ServerLevel sl)) {
				return true;
			}
			WallExtenderBlockEntity master = masterOf(sl, pos);
			if (master != null && master.data() != null) {
				if (player instanceof ServerPlayer sp && !NetlinkServer.canModify(sp.getUUID(), master.data().owner)) {
					sp.sendOverlayMessage(Component.translatable("message.createnetlink.not_authorized",
						NetlinkServer.ownerName(master.data().owner)).withStyle(ChatFormatting.RED));
					return false;
				}
				disassemble(sl, master.getBlockPos(), master.data(), null);
				return false;
			}
			// orphaned plate (no master): just turn back into its block
			if (blockEntity instanceof WallExtenderBlockEntity be) {
				sl.setBlock(pos, be.disguise(), Block.UPDATE_ALL);
				return false;
			}
			return true;
		});
	}

	/** Where the plates go if the extender is put on {@code side} of the structure. */
	public static Set<BlockPos> plateCells(Set<BlockPos> structure, Direction side) {
		Direction up = side.getOpposite();
		int min = Integer.MAX_VALUE;
		for (BlockPos p : structure) {
			min = Math.min(min, height(p, up));
		}
		int plane = min - 1;
		Set<BlockPos> plates = new LinkedHashSet<>();
		for (BlockPos p : structure) {
			plates.add(p.relative(up, plane - height(p, up)));
		}
		return plates;
	}

	public static void assemble(ServerPlayer player, BlockPos clicked, int sideId) {
		ServerLevel level = player.level();
		ItemStack item = player.getMainHandItem().getItem() instanceof WallExtenderItem ? player.getMainHandItem()
			: player.getOffhandItem().getItem() instanceof WallExtenderItem ? player.getOffhandItem() : ItemStack.EMPTY;
		if (item.isEmpty() || player.blockPosition().distSqr(clicked) > 64 * 64) {
			return;
		}
		Set<BlockPos> structure = gather(player, level, clicked);
		if (structure == null) {
			return;
		}
		Direction side = Direction.from3DDataValue(sideId);
		Direction up = side.getOpposite();
		Set<BlockPos> plates = plateCells(structure, side);
		int missing = 0;
		for (BlockPos p : plates) {
			if (structure.contains(p) || !canHostPlate(level, p)) {
				missing++;
			}
		}
		if (missing > 0) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.extender_need_blocks", missing).withStyle(ChatFormatting.RED));
			return;
		}

		// master: the plate closest to where the player clicked
		BlockPos master = null;
		double best = Double.MAX_VALUE;
		for (BlockPos p : plates) {
			double d = p.distSqr(clicked);
			if (d < best) {
				best = d;
				master = p;
			}
		}
		if (master == null) {
			return;
		}

		ExtenderData data = new ExtenderData();
		data.up = up;
		data.owner = player.getUUID();
		int maxDepth = 0;
		for (BlockPos p : structure) {
			BlockPos rel = p.subtract(master);
			data.structure.add(new ExtenderData.Part(rel, level.getBlockState(p)));
			maxDepth = Math.max(maxDepth, data.depth(rel));
		}
		data.hmax = maxDepth;
		data.extension = 0;
		for (BlockPos p : plates) {
			data.plates.add(new ExtenderData.Part(p.subtract(master), level.getBlockState(p)));
		}

		// the glue that held the shape together is used up
		AABB bounds = null;
		for (BlockPos p : structure) {
			AABB b = new AABB(p);
			bounds = bounds == null ? b : bounds.minmax(b);
		}
		if (bounds != null) {
			for (SuperGlueEntity glue : level.getEntitiesOfClass(SuperGlueEntity.class, bounds.inflate(1))) {
				for (BlockPos p : structure) {
					if (glue.contains(p)) {
						glue.discard();
						break;
					}
				}
			}
		}

		// collapse: the shape vanishes into the plates
		for (BlockPos p : structure) {
			level.setBlock(p, Blocks.AIR.defaultBlockState(), MOVE_FLAGS);
		}
		BlockState plateState = NetlinkRegistry.WALL_EXTENDER.defaultBlockState().setValue(WallExtenderBlock.FACING, up);
		disassembling.add(master);
		try {
			for (BlockPos p : plates) {
				BlockState disguise = level.getBlockState(p);
				level.setBlock(p, plateState, Block.UPDATE_ALL);
				if (level.getBlockEntity(p) instanceof WallExtenderBlockEntity be) {
					if (p.equals(master)) {
						be.makeMaster(data, disguise);
					} else {
						be.makeSlave(master, disguise);
					}
				}
			}
		} finally {
			disassembling.remove(master);
		}
		for (BlockPos p : structure) {
			level.updateNeighborsAt(p, Blocks.AIR);
		}

		item.consume(1, player);
		level.playSound(null, master, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.0F, 0.7F);
		level.playSound(null, master, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 0.6F);

		NetlinkServer.touchNetwork(player);
		NetlinkServer.putDevice(new DeviceRecord(DeviceRecord.Kind.EXTENDER, level.dimension(), master, player.getUUID(), "", true, 0, up,
			BuiltInRegistries.BLOCK.getKey(NetlinkRegistry.WALL_EXTENDER).toString()));
		player.sendSystemMessage(Component.translatable("message.createnetlink.extender_assembled", structure.size(),
			Component.keybind("key.createnetlink.hud").withStyle(ChatFormatting.AQUA)).withStyle(ChatFormatting.GRAY));
	}

	// =================================================================== motion

	private static BlockState ghost() {
		return NetlinkRegistry.EXTENDER_GHOST.defaultBlockState();
	}

	private static boolean isGhost(BlockState state) {
		return state.getBlock() instanceof ExtenderGhostBlock;
	}

	/** Cells the wall physically occupies right now (real blocks at rest, ghosts while a step runs). */
	static Map<BlockPos, BlockState> occupied(BlockPos master, ExtenderData data) {
		int e = data.extension;
		return data.displayed(master, data.moveDir == 0 ? e : Math.min(e, e + data.moveDir));
	}

	/** Whether the cells the wall would newly enter going from {@code from} to {@code to} are free. */
	private static boolean canEnter(ServerLevel level, BlockPos master, ExtenderData data, int from, int to) {
		if (to < 0 || to > data.hmax) {
			return false;
		}
		if (to <= from) {
			return true;
		}
		Map<BlockPos, BlockState> now = data.displayed(master, from);
		for (BlockPos p : data.displayed(master, to).keySet()) {
			if (now.containsKey(p)) {
				continue;
			}
			if (!level.isLoaded(p)) {
				return false;
			}
			BlockState there = level.getBlockState(p);
			// leftover ghost blocks (e.g. the server stopped mid-move) must not block the wall forever
			if (!there.isAir() && !there.canBeReplaced() && !there.is(NetlinkRegistry.EXTENDER_GHOST)) {
				return false;
			}
		}
		return true;
	}

	/** Swaps the occupied cell set from {@code from} to {@code to}: cells that drop out become air, the rest the given filler. */
	private static void swap(ServerLevel level, Map<BlockPos, BlockState> from, Map<BlockPos, BlockState> to, boolean real) {
		for (BlockPos p : from.keySet()) {
			if (!to.containsKey(p)) {
				level.setBlock(p, Blocks.AIR.defaultBlockState(), MOVE_FLAGS);
			}
		}
		for (Map.Entry<BlockPos, BlockState> e : to.entrySet()) {
			BlockState want = real ? e.getValue() : ghost();
			if (level.getBlockState(e.getKey()) != want) {
				level.setBlock(e.getKey(), want, MOVE_FLAGS);
			}
		}
		Set<BlockPos> changed = new HashSet<>(from.keySet());
		changed.addAll(to.keySet());
		for (BlockPos p : changed) {
			level.updateNeighborsAt(p, Blocks.AIR);
		}
	}

	private static void broadcast(ServerLevel level, BlockPos master, ExtenderData data) {
		NetlinkPackets.ExtenderMotion packet = new NetlinkPackets.ExtenderMotion(master, data.extension, data.moveDir, data.moveTick, data.speed);
		for (ServerPlayer p : PlayerLookup.tracking(level, master)) {
			ServerPlayNetworking.send(p, packet);
		}
	}

	/**
	 * One server tick of a master plate. At rest the wall is made of real blocks; while it moves they are swapped for
	 * invisible ghost blocks (so it stays solid) and clients draw the wall sliding smoothly, like a Create contraption.
	 * Returns true if the data changed.
	 */
	public static boolean tickMotion(ServerLevel level, BlockPos master, ExtenderData data, int target) {
		target = Math.max(0, Math.min(data.hmax, target));
		if (data.moveDir == 0) {
			if (data.extension == target) {
				return false;
			}
			// whatever happened to the wall while it stood still becomes its shape before it moves (no duplicates)
			reconcile(level, master, data);
			int dir = Integer.signum(target - data.extension);
			if (!canEnter(level, master, data, data.extension, data.extension + dir)) {
				return false;
			}
			Map<BlockPos, BlockState> before = data.displayed(master, data.extension);
			data.moveDir = dir;
			data.moveTick = 0;
			swap(level, before, occupied(master, data), false);
			level.playSound(null, master, dir > 0 ? SoundEvents.PISTON_EXTEND : SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5F, 0.7F);
			broadcast(level, master, data);
			return true;
		}

		data.moveTick++;
		pushEntities(level, master, data, 0.0F);
		if (data.moveTick < Math.max(1, data.speed)) {
			return true;
		}

		// the step is done
		Map<BlockPos, BlockState> during = occupied(master, data);
		int e = data.extension;
		int n = e + data.moveDir;
		if (!canEnter(level, master, data, e, n)) {
			// something was put in the way mid-step: settle where we were
			data.moveDir = 0;
			data.moveTick = 0;
			swap(level, during, data.displayed(master, e), true);
			broadcast(level, master, data);
			return true;
		}
		data.extension = n;
		data.moveTick = 0;
		int next = Integer.signum(target - n);
		if (next != 0 && next == data.moveDir && canEnter(level, master, data, n, n + next)) {
			swap(level, during, occupied(master, data), false);
		} else {
			data.moveDir = 0;
			swap(level, during, data.displayed(master, n), true);
			level.playSound(null, master, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.35F, 1.4F);
		}
		broadcast(level, master, data);
		return true;
	}

	/**
	 * Pushes entities in front of the moving wall. Runs on both sides; each side only moves the entities it is
	 * authoritative for (the client moves its own player, the server everything else), which keeps riding smooth.
	 */
	public static void pushEntities(Level level, BlockPos master, ExtenderData data, float partial) {
		if (data.moveDir <= 0) {
			return;
		}
		float f = data.frac(partial);
		List<AABB> fronts = new ArrayList<>();
		AABB bounds = null;
		for (ExtenderData.Part p : data.leading()) {
			if (data.height(p, f) <= 0) {
				continue;
			}
			AABB b = data.box(master, p, f);
			fronts.add(b);
			bounds = bounds == null ? b : bounds.minmax(b);
		}
		if (bounds == null) {
			return;
		}
		Direction up = data.up;
		boolean positive = up.getAxisDirection() == Direction.AxisDirection.POSITIVE;
		Direction.Axis axis = up.getAxis();
		for (Entity entity : level.getEntities((Entity) null, bounds.inflate(0.01).expandTowards(up.getStepX() * 2.0, up.getStepY() * 2.0,
			up.getStepZ() * 2.0), en -> !en.isSpectator() && !(en instanceof SuperGlueEntity) && !en.noPhysics)) {
			if (!level.isClientSide() && entity instanceof ServerPlayer sp) {
				((ServerGamePacketListenerAccessor) sp.connection).netlink$setAboveGroundTickCount(0);
			}
			if (!entity.isLocalInstanceAuthoritative() || entity.getPistonPushReaction() == PushReaction.IGNORE) {
				continue;
			}
			AABB eb = entity.getBoundingBox();
			double push = 0;
			for (AABB b : fronts) {
				if (!eb.intersects(b)) {
					continue;
				}
				double need = positive ? b.max(axis) - eb.min(axis) : eb.max(axis) - b.min(axis);
				if (need > push && need < 1.5) {
					push = need;
				}
			}
			if (push > 1.0E-4) {
				entity.move(MoverType.SHULKER_BOX, new Vec3(up.getStepX() * push, up.getStepY() * push, up.getStepZ() * push));
				if (axis == Direction.Axis.Y && positive) {
					entity.setOnGround(true);
					entity.resetFallDistance();
					Vec3 v = entity.getDeltaMovement();
					if (v.y < 0) {
						entity.setDeltaMovement(v.x, 0, v.z);
					}
				}
			}
		}
	}

	// =================================================================== watcher

	/**
	 * Brings the stored shape in line with the world while the wall rests. Blocks a player broke are dropped from the
	 * shape and blocks that changed (doors opened, a block swapped) are taken over, so folding and unfolding never
	 * resurrects anything; ghost blocks left behind by an interrupted step are turned back into the real blocks.
	 * Cells in unloaded chunks are left alone. Returns true if the shape changed.
	 */
	public static boolean reconcile(ServerLevel level, BlockPos master, ExtenderData data) {
		if (data.moveDir != 0 || data.extension <= 0) {
			return false;
		}
		int sink = data.hmax - data.extension;
		boolean changed = false;
		boolean ghosts = false;
		List<ExtenderData.Part> next = new ArrayList<>(data.structure.size());
		for (ExtenderData.Part part : data.structure) {
			if (data.depth(part.rel()) <= sink) {
				next.add(part); // folded away: not in the world right now
				continue;
			}
			BlockPos at = master.offset(part.rel()).relative(data.up, -sink);
			if (!level.isLoaded(at)) {
				next.add(part);
				continue;
			}
			BlockState there = level.getBlockState(at);
			if (there == part.state()) {
				next.add(part);
			} else if (isGhost(there)) {
				level.setBlock(at, part.state(), Block.UPDATE_ALL);
				ghosts = true;
				next.add(part);
			} else if (there.isAir() || there.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
				|| level.getBlockEntity(at) != null || there.getBlock() instanceof WallExtenderBlock) {
				changed = true; // broken (or replaced by something that can't travel): no longer part of the wall
			} else {
				next.add(new ExtenderData.Part(part.rel(), there));
				changed = true;
			}
		}
		if (changed) {
			data.structure = next;
			data.invalidate();
		}
		if (changed || ghosts) {
			if (ghosts) {
				clearStrayGhosts(level, master, data);
			}
			if (level.getBlockEntity(master) instanceof WallExtenderBlockEntity be) {
				be.markDirty();
				BlockState s = level.getBlockState(master);
				level.sendBlockUpdated(master, s, s, Block.UPDATE_CLIENTS);
			}
		}
		return changed;
	}

	/** Removes ghost blocks the wall left anywhere along its path that it doesn't occupy now. */
	public static void clearStrayGhosts(ServerLevel level, BlockPos master, ExtenderData data) {
		Map<BlockPos, BlockState> now = occupied(master, data);
		Set<BlockPos> seen = new HashSet<>();
		for (ExtenderData.Part part : data.structure) {
			for (int s = 0; s <= data.hmax; s++) {
				BlockPos at = master.offset(part.rel()).relative(data.up, -s);
				if (!seen.add(at) || now.containsKey(at) || !level.isLoaded(at)) {
					continue;
				}
				if (isGhost(level.getBlockState(at))) {
					level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
	}

	/** True if every plate is still in place; plates in unloaded chunks count as present. */
	public static boolean platesIntact(ServerLevel level, BlockPos master, ExtenderData data) {
		for (ExtenderData.Part plate : data.plates) {
			BlockPos p = master.offset(plate.rel());
			if (level.isLoaded(p) && !(level.getBlockState(p).getBlock() instanceof WallExtenderBlock)) {
				return false;
			}
		}
		return true;
	}

	/** Puts the structure back (fully extended), restores what the plates replaced and drops the extender item. */
	public static void disassemble(ServerLevel level, BlockPos master, ExtenderData data, @Nullable BlockPos alreadyGone) {
		if (!disassembling.add(master)) {
			return;
		}
		try {
			Map<BlockPos, BlockState> now = occupied(master, data);
			Map<BlockPos, BlockState> full = data.displayed(master, data.hmax);
			data.moveDir = 0;
			data.moveTick = 0;
			broadcast(level, master, data);
			for (BlockPos p : now.keySet()) {
				if (!full.containsKey(p)) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), MOVE_FLAGS);
				}
			}
			for (Map.Entry<BlockPos, BlockState> e : full.entrySet()) {
				BlockState there = level.getBlockState(e.getKey());
				if (now.containsKey(e.getKey()) || there.isAir() || there.canBeReplaced() || isGhost(there)) {
					level.setBlock(e.getKey(), e.getValue(), MOVE_FLAGS);
				} else {
					Block.popResource(level, e.getKey(), new ItemStack(e.getValue().getBlock().asItem()));
				}
			}
			for (ExtenderData.Part plate : data.plates) {
				BlockPos p = master.offset(plate.rel());
				level.setBlock(p, plate.state(), Block.UPDATE_ALL);
			}
			Set<BlockPos> changed = new HashSet<>(now.keySet());
			changed.addAll(full.keySet());
			for (BlockPos p : changed) {
				level.updateNeighborsAt(p, Blocks.AIR);
			}
			Block.popResource(level, master.relative(data.up), new ItemStack(NetlinkRegistry.WALL_EXTENDER_ITEM));
			NetlinkServer.removeDevice(DeviceRecord.key(DeviceRecord.Kind.EXTENDER, level.dimension(), master));
			level.playSound(null, master, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.0F, 0.6F);
		} finally {
			disassembling.remove(master);
		}
	}

	/** A plate disappeared (broken, exploded, replaced): take the whole extender apart on the next tick. */
	public static void onPlateRemoved(ServerLevel level, BlockPos pos, BlockPos master, @Nullable ExtenderData masterData) {
		if (disassembling.contains(master)) {
			return;
		}
		if (masterData != null) {
			pending.add(() -> disassemble(level, master, masterData, pos));
		} else {
			pending.add(() -> {
				if (level.getBlockEntity(master) instanceof WallExtenderBlockEntity be && be.data() != null) {
					disassemble(level, master, be.data(), pos);
				}
			});
		}
	}

	private static @Nullable WallExtenderBlockEntity masterOf(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof WallExtenderBlockEntity be)) {
			return null;
		}
		if (be.isMaster()) {
			return be;
		}
		return level.getBlockEntity(be.masterPos()) instanceof WallExtenderBlockEntity m && m.isMaster() ? m : null;
	}

	public static void openConfig(ServerPlayer player, BlockPos pos) {
		WallExtenderBlockEntity master = masterOf(player.level(), pos);
		if (master == null || !NetlinkServer.requireImplant(player)) {
			return;
		}
		DeviceRecord d = NetlinkServer.device(DeviceRecord.key(DeviceRecord.Kind.EXTENDER, player.level().dimension(), master.getBlockPos()));
		if (d != null) {
			NetlinkServer.openDeviceConfig(player, d);
		}
	}

	/**
	 * Makes sure the network knows this extender. Without its device record the wall would never hear channel changes,
	 * so a lost record (chunk reloads, old saves) is recreated from the plate's own data.
	 */
	public static void ensureRecord(ServerLevel level, BlockPos master, ExtenderData data) {
		String key = DeviceRecord.key(DeviceRecord.Kind.EXTENDER, level.dimension(), master);
		DeviceRecord d = NetlinkServer.device(key);
		java.util.UUID owner = data.owner != null ? data.owner : d != null ? d.owner : null;
		if (d == null || !d.listen || !d.channel.equals(data.channel) || !java.util.Objects.equals(d.owner, owner)) {
			NetlinkServer.putDevice(new DeviceRecord(DeviceRecord.Kind.EXTENDER, level.dimension(), master.immutable(), owner, data.channel,
				true, 0, data.up, BuiltInRegistries.BLOCK.getKey(NetlinkRegistry.WALL_EXTENDER).toString()));
		}
	}

	public static void configure(ServerLevel level, BlockPos masterPos, java.util.UUID owner, String channel, int speed, boolean invert) {
		WallExtenderBlockEntity master = masterOf(level, masterPos);
		if (master == null || master.data() == null) {
			return;
		}
		ExtenderData data = master.data();
		data.owner = owner;
		data.channel = channel;
		data.speed = Math.max(1, Math.min(100, speed));
		data.invert = invert;
		master.markDirty();
		NetlinkServer.putDevice(new DeviceRecord(DeviceRecord.Kind.EXTENDER, level.dimension(), master.getBlockPos(), owner, channel, true, 0,
			data.up, BuiltInRegistries.BLOCK.getKey(NetlinkRegistry.WALL_EXTENDER).toString()));
		master.setReceived(NetlinkServer.channelValue(owner, channel));
	}

	public static void remove(ServerPlayer player, BlockPos pos) {
		ServerLevel level = player.level();
		WallExtenderBlockEntity master = masterOf(level, pos);
		if (master == null || master.data() == null || !NetlinkServer.requireImplant(player)
			|| player.blockPosition().distSqr(pos) > 64 * 64) {
			return;
		}
		if (!NetlinkServer.canModify(player.getUUID(), master.data().owner)) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.not_authorized", NetlinkServer.ownerName(master.data().owner))
				.withStyle(ChatFormatting.RED));
			return;
		}
		disassemble(level, master.getBlockPos(), master.data(), null);
	}

	public static @Nullable int[] settings(ServerLevel level, BlockPos pos) {
		WallExtenderBlockEntity master = masterOf(level, pos);
		if (master == null || master.data() == null) {
			return null;
		}
		return new int[]{master.data().speed, master.data().invert ? 1 : 0};
	}

	public static void push(ServerLevel level, BlockPos pos, int value) {
		if (level.getBlockEntity(pos) instanceof WallExtenderBlockEntity be && be.isMaster()) {
			be.setReceived(value);
		}
	}

	static Map<String, Object> debug() {
		return new HashMap<>();
	}
}
