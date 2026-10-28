package dev.exavolt.netlink.block;

import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.network.NetlinkServer;
import dev.exavolt.netlink.network.WallExtenders;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * One plate of a Wall Extender. The master plate holds the {@link ExtenderData}; every other plate just points at it.
 */
public class WallExtenderBlockEntity extends BlockEntity {
	private @Nullable BlockPos master;
	private @Nullable ExtenderData data;
	/** The block this plate was built into; it is drawn underneath the extender overlay and comes back on removal. */
	private BlockState disguise = net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState();
	private int received;
	private boolean initialized;

	public WallExtenderBlockEntity(BlockPos pos, BlockState state) {
		super(NetlinkRegistry.WALL_EXTENDER_BE, pos, state);
	}

	public boolean isMaster() {
		return data != null;
	}

	public @Nullable ExtenderData data() {
		return data;
	}

	public BlockPos masterPos() {
		return master != null ? master : worldPosition;
	}

	public BlockState disguise() {
		return disguise;
	}

	public void makeMaster(ExtenderData data, BlockState disguise) {
		this.data = data;
		this.master = null;
		this.disguise = disguise;
		setChanged();
		syncClient();
	}

	public void makeSlave(BlockPos master, BlockState disguise) {
		this.master = master.immutable();
		this.data = null;
		this.disguise = disguise;
		setChanged();
		syncClient();
	}

	private void syncClient() {
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
		}
	}

	@Override
	public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
		return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
	}

	/** Clients get the folded structure too: they draw it while it slides. */
	@Override
	public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}

	public int received() {
		return received;
	}

	public void setReceived(int value) {
		if (received != value) {
			received = value;
			setChanged();
		}
	}

	public void markDirty() {
		setChanged();
	}

	void serverTick() {
		if (level == null || level.isClientSide() || data == null || !NetlinkServer.running()) {
			return;
		}
		if (!initialized && NetlinkServer.ready()) {
			// until then the saved state holds, so a restart doesn't fold and unfold everything
			initialized = true;
			WallExtenders.ensureRecord((net.minecraft.server.level.ServerLevel) level, worldPosition, data);
			received = NetlinkServer.channelValue(data.owner, data.channel);
			// leftovers of a step that was cut short (crash, chunk saved mid-move) are cleaned up once on load
			WallExtenders.clearStrayGhosts((net.minecraft.server.level.ServerLevel) level, worldPosition, data);
			WallExtenders.reconcile((net.minecraft.server.level.ServerLevel) level, worldPosition, data);
		}
		// background watcher: once a second, keep the stored shape and the plates in line with the world
		if (initialized && data.moveDir == 0 && (level.getGameTime() + (worldPosition.asLong() & 15)) % 20 == 0) {
			net.minecraft.server.level.ServerLevel sl = (net.minecraft.server.level.ServerLevel) level;
			if (!WallExtenders.platesIntact(sl, worldPosition, data)) {
				WallExtenders.disassemble(sl, worldPosition, data, null);
				return;
			}
			WallExtenders.reconcile(sl, worldPosition, data);
		}
		int target = (received > 0) != data.invert ? data.hmax : 0;
		if (WallExtenders.tickMotion((net.minecraft.server.level.ServerLevel) level, worldPosition, data, target)) {
			setChanged();
		}
	}

	// ---------------------------------------------------------------- client animation

	/** Ticks left to keep drawing the wall after it stopped, until the real blocks have arrived on the client. */
	private int linger;

	public int linger() {
		return linger;
	}

	void clientTick() {
		if (data == null) {
			return;
		}
		if (linger > 0) {
			linger--;
		}
		if (data.moveDir != 0 && data.moveTick < data.speed) {
			data.moveTick++;
		}
		WallExtenders.pushEntities(level, worldPosition, data, 0.0F);
	}

	/** Motion update from the server. */
	public void clientMotion(int extension, int dir, int tick, int speed) {
		if (data == null) {
			return;
		}
		if (dir == 0 && data.moveDir != 0) {
			linger = 4;
		}
		data.extension = extension;
		data.moveDir = dir;
		data.moveTick = tick;
		data.speed = speed;
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof net.minecraft.server.level.ServerLevel sl) {
			WallExtenders.onPlateRemoved(sl, pos, masterPos(), data);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		master = input.read("Master", BlockPos.CODEC).orElse(null);
		data = input.read("Extender", ExtenderData.CODEC).orElse(null);
		received = input.getIntOr("Received", 0);
		disguise = input.read("Disguise", BlockState.CODEC).orElse(net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState());
		if (level != null && level.isClientSide()) {
			// the disguise is part of the chunk mesh: rebuild it
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 8);
		}

	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.storeNullable("Master", BlockPos.CODEC, master);
		output.storeNullable("Extender", ExtenderData.CODEC, data);
		output.putInt("Received", received);
		output.store("Disguise", BlockState.CODEC, disguise);
	}
}
