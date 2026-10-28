package dev.exavolt.netlinkdecor.net;

import dev.exavolt.netlink.net.NetlinkPackets.NetEntry;
import dev.exavolt.netlinkdecor.NetlinkDecor;
import dev.exavolt.netlinkdecor.item.ChiselSettings;
import dev.exavolt.netlinkdecor.voxel.VoxelEditor;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DecorPackets {
	private DecorPackets() {
	}

	/** Chisel stroke: the target voxel in global voxel coordinates at the chisel's resolution. */
	public record ChiselUse(long x, long y, long z, int res, int action) implements CustomPacketPayload {
		public static final Type<ChiselUse> TYPE = new Type<>(NetlinkDecor.id("chisel_use"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ChiselUse> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarLong(p.x);
				buf.writeVarLong(p.y);
				buf.writeVarLong(p.z);
				buf.writeVarInt(p.res);
				buf.writeByte(p.action);
			},
			buf -> new ChiselUse(buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarInt(), buf.readByte())
		);

		@Override
		public Type<ChiselUse> type() {
			return TYPE;
		}
	}

	public record ChiselConfigure(ChiselSettings settings) implements CustomPacketPayload {
		public static final Type<ChiselConfigure> TYPE = new Type<>(NetlinkDecor.id("chisel_configure"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ChiselConfigure> CODEC = StreamCodec.of(
			(buf, p) -> ChiselSettings.STREAM_CODEC.encode(buf, p.settings),
			buf -> new ChiselConfigure(ChiselSettings.STREAM_CODEC.decode(buf))
		);

		@Override
		public Type<ChiselConfigure> type() {
			return TYPE;
		}
	}

	/** Server -> client: open the channel screen of a live prop. */
	public record OpenPropConfig(BlockPos pos, Optional<UUID> owner, String channel, List<NetEntry> networks) implements CustomPacketPayload {
		public static final Type<OpenPropConfig> TYPE = new Type<>(NetlinkDecor.id("open_prop_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenPropConfig> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBlockPos(p.pos);
				writeOptUuid(buf, p.owner);
				buf.writeUtf(p.channel, 64);
				buf.writeVarInt(p.networks.size());
				for (NetEntry e : p.networks) {
					buf.writeUUID(e.owner());
					buf.writeUtf(e.name(), 64);
					buf.writeBoolean(e.own());
					buf.writeVarInt(e.channels().size());
					for (String c : e.channels()) {
						buf.writeUtf(c, 64);
					}
				}
			},
			buf -> {
				BlockPos pos = buf.readBlockPos();
				Optional<UUID> owner = readOptUuid(buf);
				String channel = buf.readUtf(64);
				int n = Math.min(buf.readVarInt(), 256);
				List<NetEntry> nets = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					UUID o = buf.readUUID();
					String name = buf.readUtf(64);
					boolean own = buf.readBoolean();
					int cn = Math.min(buf.readVarInt(), 4096);
					List<String> chans = new ArrayList<>();
					for (int k = 0; k < cn; k++) {
						chans.add(buf.readUtf(64));
					}
					nets.add(new NetEntry(o, name, own, chans));
				}
				return new OpenPropConfig(pos, owner, channel, nets);
			}
		);

		@Override
		public Type<OpenPropConfig> type() {
			return TYPE;
		}
	}

	public record ApplyPropConfig(BlockPos pos, Optional<UUID> owner, String channel) implements CustomPacketPayload {
		public static final Type<ApplyPropConfig> TYPE = new Type<>(NetlinkDecor.id("apply_prop_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ApplyPropConfig> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBlockPos(p.pos);
				writeOptUuid(buf, p.owner);
				buf.writeUtf(p.channel, 64);
			},
			buf -> new ApplyPropConfig(buf.readBlockPos(), readOptUuid(buf), buf.readUtf(64))
		);

		@Override
		public Type<ApplyPropConfig> type() {
			return TYPE;
		}
	}

	static void writeOptUuid(FriendlyByteBuf buf, Optional<UUID> id) {
		buf.writeBoolean(id.isPresent());
		id.ifPresent(buf::writeUUID);
	}

	static Optional<UUID> readOptUuid(FriendlyByteBuf buf) {
		return buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
	}

	public static void register() {
		PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s = PayloadTypeRegistry.serverboundPlay();
		PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c = PayloadTypeRegistry.clientboundPlay();
		c2s.register(ChiselUse.TYPE, ChiselUse.CODEC);
		c2s.register(ChiselConfigure.TYPE, ChiselConfigure.CODEC);
		c2s.register(ApplyPropConfig.TYPE, ApplyPropConfig.CODEC);
		s2c.registerLarge(OpenPropConfig.TYPE, OpenPropConfig.CODEC, 1 << 20);

		ServerPlayNetworking.registerGlobalReceiver(ChiselUse.TYPE,
			(p, ctx) -> VoxelEditor.use(ctx.player(), p.x(), p.y(), p.z(), p.res(), p.action()));
		ServerPlayNetworking.registerGlobalReceiver(ChiselConfigure.TYPE, (p, ctx) -> VoxelEditor.applySettings(ctx.player(), p.settings()));
		ServerPlayNetworking.registerGlobalReceiver(ApplyPropConfig.TYPE, (p, ctx) -> DecorServer.applyConfig(ctx.player(), p));
	}
}
