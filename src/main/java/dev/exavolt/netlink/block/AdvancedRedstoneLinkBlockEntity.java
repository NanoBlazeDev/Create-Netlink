package dev.exavolt.netlink.block;

import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.network.DeviceRecord;
import dev.exavolt.netlink.network.NetlinkServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

public class AdvancedRedstoneLinkBlockEntity extends BlockEntity {
	private @Nullable UUID owner;
	private String channel = "";
	private int received;
	private int transmitted;
	private boolean initialized;

	public AdvancedRedstoneLinkBlockEntity(BlockPos pos, BlockState state) {
		super(NetlinkRegistry.ADVANCED_REDSTONE_LINK_BE, pos, state);
	}

	public @Nullable UUID getOwner() {
		return owner;
	}

	public String getChannel() {
		return channel;
	}

	public int getReceived() {
		return received;
	}

	public boolean isListening() {
		return getBlockState().getValue(AdvancedRedstoneLinkBlock.RECEIVER);
	}

	private String key() {
		return DeviceRecord.key(DeviceRecord.Kind.LINK, level.dimension(), worldPosition);
	}

	void setOwner(UUID owner) {
		this.owner = owner;
		setChanged();
		ensureRecord();
	}

	void serverTick() {
		if (initialized || level == null || level.isClientSide()) {
			return;
		}
		if (!NetlinkServer.ready()) {
			return; // wait until scripts restored their signals, so outputs don't flicker after a restart
		}
		initialized = true;
		ensureRecord();
		if (isListening()) {
			setReceived(NetlinkServer.channelValue(owner, channel));
		} else if (getBlockState().getBlock() instanceof AdvancedRedstoneLinkBlock block) {
			block.updateTransmittedSignal(getBlockState(), level, worldPosition);
		}
	}

	/** Makes sure the saved device record matches this block entity, creating it if needed. */
	public @Nullable DeviceRecord ensureRecord() {
		if (level == null || level.isClientSide() || !NetlinkServer.running()) {
			return null;
		}
		String key = key();
		DeviceRecord d = NetlinkServer.device(key);
		boolean listen = isListening();
		if (d == null || !Objects.equals(d.owner, owner) || !d.channel.equals(channel) || d.listen != listen) {
			d = new DeviceRecord(DeviceRecord.Kind.LINK, level.dimension(), worldPosition, owner, channel, listen,
				listen ? 0 : transmitted, getBlockState().getValue(AdvancedRedstoneLinkBlock.FACING), "");
			NetlinkServer.putDevice(d);
		}
		return d;
	}

	/** Applies settings from the configuration screen (already permission-checked). */
	public void configure(UUID newOwner, String newChannel, boolean listen) {
		if (level == null) {
			return;
		}
		owner = newOwner;
		channel = newChannel;
		BlockState state = getBlockState();
		if (state.getValue(AdvancedRedstoneLinkBlock.RECEIVER) != listen) {
			received = 0;
			transmitted = 0;
			state = state.setValue(AdvancedRedstoneLinkBlock.RECEIVER, listen).setValue(AdvancedRedstoneLinkBlock.POWERED, false);
			level.setBlock(worldPosition, state, Block.UPDATE_ALL);
		}
		setChanged();
		level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
		ensureRecord();
		if (listen) {
			setReceived(NetlinkServer.channelValue(owner, channel));
		} else if (state.getBlock() instanceof AdvancedRedstoneLinkBlock block) {
			block.updateTransmittedSignal(state, level, worldPosition);
			NetlinkServer.updatePostValue(key(), transmitted);
		}
		if (state.getBlock() instanceof AdvancedRedstoneLinkBlock block) {
			block.notifyOutput(level, worldPosition, state);
		}
	}

	void transmit(int power) {
		if (level == null || level.isClientSide()) {
			return;
		}
		transmitted = power;
		ensureRecord();
		NetlinkServer.updatePostValue(key(), power);
	}

	public void setReceived(int value) {
		if (level == null || level.isClientSide() || value == received) {
			return;
		}
		received = value;
		setChanged();
		BlockState state = getBlockState();
		if (!state.getValue(AdvancedRedstoneLinkBlock.RECEIVER)) {
			return;
		}
		boolean powered = value > 0;
		if (state.getValue(AdvancedRedstoneLinkBlock.POWERED) != powered) {
			state = state.setValue(AdvancedRedstoneLinkBlock.POWERED, powered);
			level.setBlock(worldPosition, state, Block.UPDATE_CLIENTS);
		}
		if (state.getBlock() instanceof AdvancedRedstoneLinkBlock block) {
			block.notifyOutput(level, worldPosition, state);
		}
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level != null && !level.isClientSide()) {
			NetlinkServer.removeDevice(key());
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		channel = input.getStringOr("Channel", "");
		received = input.getIntOr("Received", 0);
		transmitted = input.getIntOr("Transmitted", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.storeNullable("Owner", UUIDUtil.CODEC, owner);
		output.putString("Channel", channel);
		output.putInt("Received", received);
		output.putInt("Transmitted", transmitted);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}

	@Override
	public void setLevel(Level level) {
		super.setLevel(level);
		initialized = false;
	}
}
