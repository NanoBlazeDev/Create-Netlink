package dev.exavolt.netlinkdecor.block;

import dev.exavolt.netlink.network.NetlinkServer;
import dev.exavolt.netlinkdecor.DecorRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A decoration that follows a Netlink channel: server racks blink harder, dishes spin faster and monitors show the
 * value. Unlinked props idle on their own.
 */
public class LiveBlockEntity extends BlockEntity {
	private @Nullable UUID owner;
	private String channel = "";
	/** Channel value 0..15, or -1 when the prop isn't linked to a channel. */
	private int value = -1;

	public LiveBlockEntity(BlockPos pos, BlockState state) {
		super(DecorRegistry.LIVE_BE, pos, state);
	}

	public @Nullable UUID owner() {
		return owner;
	}

	public String channel() {
		return channel;
	}

	public int value() {
		return value;
	}

	public boolean linked() {
		return owner != null && !channel.isEmpty();
	}

	public void configure(@Nullable UUID owner, String channel) {
		this.owner = owner;
		this.channel = channel;
		this.value = linked() ? NetlinkServer.channelValue(owner, channel) : -1;
		sync();
	}

	private void sync() {
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	public void serverTick() {
		if (level == null || level.getGameTime() % 5 != Math.floorMod(worldPosition.asLong(), 5) || !NetlinkServer.running()) {
			return;
		}
		int v = linked() ? NetlinkServer.channelValue(owner, channel) : -1;
		if (v != value) {
			value = v;
			sync();
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		channel = input.getStringOr("Channel", "");
		value = input.getIntOr("Value", -1);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.storeNullable("Owner", UUIDUtil.CODEC, owner);
		output.putString("Channel", channel);
		output.putInt("Value", value);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}
}
