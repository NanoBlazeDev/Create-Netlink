package dev.exavolt.netlink.net;

import dev.exavolt.netlink.Netlink;
import dev.exavolt.netlink.item.KeyboardConfig;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Every packet the mod sends, with hand-written codecs. */
public final class NetlinkPackets {
	private NetlinkPackets() {
	}

	public static final int MAX_CHANNEL = 64;

	// ------------------------------------------------------------------ shared view records

	public record NetEntry(UUID owner, String name, boolean own, List<String> channels) {
		static void write(FriendlyByteBuf buf, NetEntry e) {
			buf.writeUUID(e.owner);
			buf.writeUtf(e.name, 64);
			buf.writeBoolean(e.own);
			writeList(buf, e.channels, (b, s) -> b.writeUtf(s, MAX_CHANNEL));
		}

		static NetEntry read(FriendlyByteBuf buf) {
			return new NetEntry(buf.readUUID(), buf.readUtf(64), buf.readBoolean(), readList(buf, b -> b.readUtf(MAX_CHANNEL)));
		}
	}

	public record HudDevice(int kind, BlockPos pos, Direction face, String channel, boolean listen, int value, boolean foreign,
	                        String ownerName) {
		static void write(FriendlyByteBuf buf, HudDevice d) {
			buf.writeByte(d.kind);
			buf.writeBlockPos(d.pos);
			buf.writeByte(d.face.get3DDataValue());
			buf.writeUtf(d.channel, MAX_CHANNEL);
			buf.writeBoolean(d.listen);
			buf.writeByte(d.value);
			buf.writeBoolean(d.foreign);
			buf.writeUtf(d.ownerName, 64);
		}

		static HudDevice read(FriendlyByteBuf buf) {
			return new HudDevice(buf.readByte(), buf.readBlockPos(), Direction.from3DDataValue(buf.readByte()), buf.readUtf(MAX_CHANNEL),
				buf.readBoolean(), buf.readByte(), buf.readBoolean(), buf.readUtf(64));
		}
	}

	/** block = registry id of the mechanism: the bugged block, or the block a link is mounted on. */
	public record MapDevice(int kind, String dim, BlockPos pos, String channel, boolean listen, int value, String block) {
		static void write(FriendlyByteBuf buf, MapDevice d) {
			buf.writeByte(d.kind);
			buf.writeUtf(d.dim, 128);
			buf.writeBlockPos(d.pos);
			buf.writeUtf(d.channel, MAX_CHANNEL);
			buf.writeBoolean(d.listen);
			buf.writeByte(d.value);
			buf.writeUtf(d.block, 128);
		}

		static MapDevice read(FriendlyByteBuf buf) {
			return new MapDevice(buf.readByte(), buf.readUtf(128), buf.readBlockPos(), buf.readUtf(MAX_CHANNEL), buf.readBoolean(), buf.readByte(),
				buf.readUtf(128));
		}
	}

	/** manual is -1 when the channel has no value set from the network screen. */
	public record MapChannel(String channel, int value, int manual, int posts, int listens) {
		static void write(FriendlyByteBuf buf, MapChannel c) {
			buf.writeUtf(c.channel, MAX_CHANNEL);
			buf.writeByte(c.value);
			buf.writeByte(c.manual);
			buf.writeVarInt(c.posts);
			buf.writeVarInt(c.listens);
		}

		static MapChannel read(FriendlyByteBuf buf) {
			return new MapChannel(buf.readUtf(MAX_CHANNEL), buf.readByte(), buf.readByte(), buf.readVarInt(), buf.readVarInt());
		}
	}

	public record MapNetwork(UUID owner, String name, boolean own, List<String> authorized, List<MapDevice> devices,
	                         List<MapChannel> channels) {
		static void write(FriendlyByteBuf buf, MapNetwork n) {
			buf.writeUUID(n.owner);
			buf.writeUtf(n.name, 64);
			buf.writeBoolean(n.own);
			writeList(buf, n.authorized, (b, s) -> b.writeUtf(s, 64));
			writeList(buf, n.devices, MapDevice::write);
			writeList(buf, n.channels, MapChannel::write);
		}

		static MapNetwork read(FriendlyByteBuf buf) {
			return new MapNetwork(buf.readUUID(), buf.readUtf(64), buf.readBoolean(), readList(buf, b -> b.readUtf(64)),
				readList(buf, MapDevice::read), readList(buf, MapChannel::read));
		}
	}

	// ------------------------------------------------------------------ payloads

	public record HudToggle(boolean on) implements CustomPacketPayload {
		public static final Type<HudToggle> TYPE = new Type<>(Netlink.id("hud_toggle"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HudToggle> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeBoolean(p.on), buf -> new HudToggle(buf.readBoolean()));

		@Override
		public Type<HudToggle> type() {
			return TYPE;
		}
	}

	public record HudSync(List<HudDevice> devices) implements CustomPacketPayload {
		public static final Type<HudSync> TYPE = new Type<>(Netlink.id("hud_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HudSync> CODEC = StreamCodec.of(
			(buf, p) -> writeList(buf, p.devices, HudDevice::write), buf -> new HudSync(readList(buf, HudDevice::read)));

		@Override
		public Type<HudSync> type() {
			return TYPE;
		}
	}

	/** speed and invert are only used by Wall Extenders (ticks per block, extend while the channel is off). */
	public record OpenDeviceConfig(int kind, BlockPos pos, Optional<UUID> owner, String ownerName, String channel, boolean listen,
	                               boolean allowListen, boolean foreign, List<NetEntry> networks, int speed, boolean invert)
		implements CustomPacketPayload {
		public static final Type<OpenDeviceConfig> TYPE = new Type<>(Netlink.id("open_device_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenDeviceConfig> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeByte(p.kind);
				buf.writeBlockPos(p.pos);
				writeOptUuid(buf, p.owner);
				buf.writeUtf(p.ownerName, 64);
				buf.writeUtf(p.channel, MAX_CHANNEL);
				buf.writeBoolean(p.listen);
				buf.writeBoolean(p.allowListen);
				buf.writeBoolean(p.foreign);
				writeList(buf, p.networks, NetEntry::write);
				buf.writeVarInt(p.speed);
				buf.writeBoolean(p.invert);
			},
			buf -> new OpenDeviceConfig(buf.readByte(), buf.readBlockPos(), readOptUuid(buf), buf.readUtf(64), buf.readUtf(MAX_CHANNEL),
				buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), readList(buf, NetEntry::read), buf.readVarInt(), buf.readBoolean())
		);

		@Override
		public Type<OpenDeviceConfig> type() {
			return TYPE;
		}
	}

	public record ApplyDeviceConfig(int kind, BlockPos pos, UUID owner, String channel, boolean listen, int speed, boolean invert)
		implements CustomPacketPayload {
		public static final Type<ApplyDeviceConfig> TYPE = new Type<>(Netlink.id("apply_device_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ApplyDeviceConfig> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeByte(p.kind);
				buf.writeBlockPos(p.pos);
				buf.writeUUID(p.owner);
				buf.writeUtf(p.channel, MAX_CHANNEL);
				buf.writeBoolean(p.listen);
				buf.writeVarInt(p.speed);
				buf.writeBoolean(p.invert);
			},
			buf -> new ApplyDeviceConfig(buf.readByte(), buf.readBlockPos(), buf.readUUID(), buf.readUtf(MAX_CHANNEL), buf.readBoolean(),
				buf.readVarInt(), buf.readBoolean())
		);

		@Override
		public Type<ApplyDeviceConfig> type() {
			return TYPE;
		}
	}

	public record DetachBug(BlockPos pos) implements CustomPacketPayload {
		public static final Type<DetachBug> TYPE = new Type<>(Netlink.id("detach_bug"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DetachBug> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeBlockPos(p.pos), buf -> new DetachBug(buf.readBlockPos()));

		@Override
		public Type<DetachBug> type() {
			return TYPE;
		}
	}

	public record OpenKeyboardConfig(int hand, KeyboardConfig config, List<NetEntry> networks) implements CustomPacketPayload {
		public static final Type<OpenKeyboardConfig> TYPE = new Type<>(Netlink.id("open_keyboard_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenKeyboardConfig> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeByte(p.hand);
				KeyboardConfig.STREAM_CODEC.encode(buf, p.config);
				writeList(buf, p.networks, NetEntry::write);
			},
			buf -> new OpenKeyboardConfig(buf.readByte(), KeyboardConfig.STREAM_CODEC.decode(buf), readList(buf, NetEntry::read))
		);

		@Override
		public Type<OpenKeyboardConfig> type() {
			return TYPE;
		}
	}

	public record ApplyKeyboardConfig(int hand, KeyboardConfig config) implements CustomPacketPayload {
		public static final Type<ApplyKeyboardConfig> TYPE = new Type<>(Netlink.id("apply_keyboard_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ApplyKeyboardConfig> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeByte(p.hand);
				KeyboardConfig.STREAM_CODEC.encode(buf, p.config);
			},
			buf -> new ApplyKeyboardConfig(buf.readByte(), KeyboardConfig.STREAM_CODEC.decode(buf))
		);

		@Override
		public Type<ApplyKeyboardConfig> type() {
			return TYPE;
		}
	}

	/** Like Create's LinkedControllerInputPacket: a batch of GLFW keys pressed or released. */
	public record ControllerInput(List<Integer> keys, boolean press) implements CustomPacketPayload {
		public static final Type<ControllerInput> TYPE = new Type<>(Netlink.id("keyboard_input"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ControllerInput> CODEC = StreamCodec.of(
			(buf, p) -> {
				writeList(buf, p.keys, FriendlyByteBuf::writeVarInt);
				buf.writeBoolean(p.press);
			},
			buf -> new ControllerInput(readList(buf, FriendlyByteBuf::readVarInt), buf.readBoolean())
		);

		@Override
		public Type<ControllerInput> type() {
			return TYPE;
		}
	}

	public record ControllerBind(int key, BlockPos linkPos) implements CustomPacketPayload {
		public static final Type<ControllerBind> TYPE = new Type<>(Netlink.id("controller_bind"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ControllerBind> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.key);
				buf.writeBlockPos(p.linkPos);
			},
			buf -> new ControllerBind(buf.readVarInt(), buf.readBlockPos())
		);

		@Override
		public Type<ControllerBind> type() {
			return TYPE;
		}
	}

	public record NetworkMap(boolean open, List<MapNetwork> networks) implements CustomPacketPayload {
		public static final Type<NetworkMap> TYPE = new Type<>(Netlink.id("network_map"));
		public static final StreamCodec<RegistryFriendlyByteBuf, NetworkMap> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBoolean(p.open);
				writeList(buf, p.networks, MapNetwork::write);
			},
			buf -> new NetworkMap(buf.readBoolean(), readList(buf, MapNetwork::read))
		);

		@Override
		public Type<NetworkMap> type() {
			return TYPE;
		}
	}

	public record RequestMap() implements CustomPacketPayload {
		public static final Type<RequestMap> TYPE = new Type<>(Netlink.id("request_map"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestMap> CODEC = StreamCodec.of((buf, p) -> {
		}, buf -> new RequestMap());

		@Override
		public Type<RequestMap> type() {
			return TYPE;
		}
	}

	public static final int ACTION_SET = 0;
	public static final int ACTION_PULSE = 1;
	public static final int ACTION_CLEAR = 2;

	public record SetChannel(UUID owner, String channel, int action, int value) implements CustomPacketPayload {
		public static final Type<SetChannel> TYPE = new Type<>(Netlink.id("set_channel"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetChannel> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUUID(p.owner);
				buf.writeUtf(p.channel, MAX_CHANNEL);
				buf.writeByte(p.action);
				buf.writeByte(p.value);
			},
			buf -> new SetChannel(buf.readUUID(), buf.readUtf(MAX_CHANNEL), buf.readByte(), buf.readByte())
		);

		@Override
		public Type<SetChannel> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ glue & wall extender

	/** Glue the selection a..b (corners may be in mid-air); the server shrinks it to the blocks inside. */
	public record GlueApply(BlockPos a, BlockPos b) implements CustomPacketPayload {
		public static final Type<GlueApply> TYPE = new Type<>(Netlink.id("glue_apply"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GlueApply> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBlockPos(p.a);
				buf.writeBlockPos(p.b);
			},
			buf -> new GlueApply(buf.readBlockPos(), buf.readBlockPos())
		);

		@Override
		public Type<GlueApply> type() {
			return TYPE;
		}
	}

	/** Flood-fill the structure connected to pos and glue it. */
	public record GlueAuto(BlockPos pos) implements CustomPacketPayload {
		public static final Type<GlueAuto> TYPE = new Type<>(Netlink.id("glue_auto"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GlueAuto> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeBlockPos(p.pos), buf -> new GlueAuto(buf.readBlockPos()));

		@Override
		public Type<GlueAuto> type() {
			return TYPE;
		}
	}

	public record GlueRemove(int entityId) implements CustomPacketPayload {
		public static final Type<GlueRemove> TYPE = new Type<>(Netlink.id("glue_remove"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GlueRemove> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeVarInt(p.entityId), buf -> new GlueRemove(buf.readVarInt()));

		@Override
		public Type<GlueRemove> type() {
			return TYPE;
		}
	}

	/** Server found a glued structure under the clicked block; client picks the side the extender goes on. */
	public record OpenExtenderAssembly(BlockPos clicked, List<BlockPos> structure) implements CustomPacketPayload {
		public static final Type<OpenExtenderAssembly> TYPE = new Type<>(Netlink.id("open_extender_assembly"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenExtenderAssembly> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBlockPos(p.clicked);
				writeList(buf, p.structure, (b, pos) -> b.writeBlockPos(pos));
			},
			buf -> new OpenExtenderAssembly(buf.readBlockPos(), readList(buf, b -> b.readBlockPos()))
		);

		@Override
		public Type<OpenExtenderAssembly> type() {
			return TYPE;
		}
	}

	public record AssembleExtender(BlockPos clicked, int side) implements CustomPacketPayload {
		public static final Type<AssembleExtender> TYPE = new Type<>(Netlink.id("assemble_extender"));
		public static final StreamCodec<RegistryFriendlyByteBuf, AssembleExtender> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBlockPos(p.clicked);
				buf.writeByte(p.side);
			},
			buf -> new AssembleExtender(buf.readBlockPos(), buf.readByte())
		);

		@Override
		public Type<AssembleExtender> type() {
			return TYPE;
		}
	}

	public record RemoveExtender(BlockPos pos) implements CustomPacketPayload {
		public static final Type<RemoveExtender> TYPE = new Type<>(Netlink.id("remove_extender"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RemoveExtender> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeBlockPos(p.pos), buf -> new RemoveExtender(buf.readBlockPos()));

		@Override
		public Type<RemoveExtender> type() {
			return TYPE;
		}
	}

	/** A Wall Extender started, continued or finished a one-block step; clients animate it locally. */
	public record ExtenderMotion(BlockPos master, int extension, int dir, int tick, int speed) implements CustomPacketPayload {
		public static final Type<ExtenderMotion> TYPE = new Type<>(Netlink.id("extender_motion"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ExtenderMotion> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBlockPos(p.master);
				buf.writeVarInt(p.extension);
				buf.writeByte(p.dir);
				buf.writeVarInt(p.tick);
				buf.writeVarInt(p.speed);
			},
			buf -> new ExtenderMotion(buf.readBlockPos(), buf.readVarInt(), buf.readByte(), buf.readVarInt(), buf.readVarInt())
		);

		@Override
		public Type<ExtenderMotion> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ scripts

	public static final int SCRIPT_SAVE = 0;
	public static final int SCRIPT_DELETE = 1;
	public static final int SCRIPT_RUN = 2;
	public static final int SCRIPT_STOP = 3;
	public static final int SCRIPT_CLEAR_LOG = 4;
	public static final int MAX_CODE = 60_000;

	public record ScriptInfo(String id, String name, boolean enabled, boolean running, boolean crashed) {
		static void write(FriendlyByteBuf buf, ScriptInfo i) {
			buf.writeUtf(i.id, 64);
			buf.writeUtf(i.name, 64);
			buf.writeBoolean(i.enabled);
			buf.writeBoolean(i.running);
			buf.writeBoolean(i.crashed);
		}

		static ScriptInfo read(FriendlyByteBuf buf) {
			return new ScriptInfo(buf.readUtf(64), buf.readUtf(64), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
		}
	}

	/** Client asks for the scripts of a network (and the code of one when it opens it). */
	public record ScriptRequest(UUID owner, String selected, boolean withCode) implements CustomPacketPayload {
		public static final Type<ScriptRequest> TYPE = new Type<>(Netlink.id("script_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScriptRequest> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUUID(p.owner);
				buf.writeUtf(p.selected, 64);
				buf.writeBoolean(p.withCode);
			},
			buf -> new ScriptRequest(buf.readUUID(), buf.readUtf(64), buf.readBoolean())
		);

		@Override
		public Type<ScriptRequest> type() {
			return TYPE;
		}
	}

	/** Log lines are prefixed with a level char: I, W, E or S. */
	public record ScriptState(UUID owner, String ownerName, List<ScriptInfo> scripts, String selected, Optional<String> code,
	                          List<String> log) implements CustomPacketPayload {
		public static final Type<ScriptState> TYPE = new Type<>(Netlink.id("script_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScriptState> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUUID(p.owner);
				buf.writeUtf(p.ownerName, 64);
				writeList(buf, p.scripts, ScriptInfo::write);
				buf.writeUtf(p.selected, 64);
				buf.writeBoolean(p.code.isPresent());
				p.code.ifPresent(c -> buf.writeUtf(c, MAX_CODE * 3));
				writeList(buf, p.log, (b, s) -> b.writeUtf(s, 1024));
			},
			buf -> new ScriptState(buf.readUUID(), buf.readUtf(64), readList(buf, ScriptInfo::read), buf.readUtf(64),
				buf.readBoolean() ? Optional.of(buf.readUtf(MAX_CODE * 3)) : Optional.empty(), readList(buf, b -> b.readUtf(1024)))
		);

		@Override
		public Type<ScriptState> type() {
			return TYPE;
		}
	}

	public record ScriptAction(UUID owner, int action, String id, String name, String code) implements CustomPacketPayload {
		public static final Type<ScriptAction> TYPE = new Type<>(Netlink.id("script_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScriptAction> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUUID(p.owner);
				buf.writeByte(p.action);
				buf.writeUtf(p.id, 64);
				buf.writeUtf(p.name, 64);
				buf.writeUtf(p.code, MAX_CODE * 3);
			},
			buf -> new ScriptAction(buf.readUUID(), buf.readByte(), buf.readUtf(64), buf.readUtf(64), buf.readUtf(MAX_CODE * 3))
		);

		@Override
		public Type<ScriptAction> type() {
			return TYPE;
		}
	}

	/** A floor typed on an Elevator Panel inside a cabin. */
	public record ElevatorPanelSelect(int entityId, String floor) implements CustomPacketPayload {
		public static final Type<ElevatorPanelSelect> TYPE = new Type<>(Netlink.id("elevator_panel_select"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ElevatorPanelSelect> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.entityId);
				buf.writeUtf(p.floor, 32);
			},
			buf -> new ElevatorPanelSelect(buf.readVarInt(), buf.readUtf(32))
		);

		@Override
		public Type<ElevatorPanelSelect> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ helpers

	static <T> void writeList(FriendlyByteBuf buf, List<T> list, BiConsumer<FriendlyByteBuf, T> writer) {
		buf.writeVarInt(list.size());
		for (T t : list) {
			writer.accept(buf, t);
		}
	}

	static <T> List<T> readList(FriendlyByteBuf buf, Function<FriendlyByteBuf, T> reader) {
		int n = buf.readVarInt();
		if (n < 0 || n > 65536) {
			throw new IllegalArgumentException("Bad list size " + n);
		}
		List<T> list = new ArrayList<>(n);
		for (int k = 0; k < n; k++) {
			list.add(reader.apply(buf));
		}
		return list;
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
		c2s.register(HudToggle.TYPE, HudToggle.CODEC);
		c2s.register(ApplyDeviceConfig.TYPE, ApplyDeviceConfig.CODEC);
		c2s.register(DetachBug.TYPE, DetachBug.CODEC);
		c2s.register(ApplyKeyboardConfig.TYPE, ApplyKeyboardConfig.CODEC);
		c2s.register(ControllerInput.TYPE, ControllerInput.CODEC);
		c2s.register(ControllerBind.TYPE, ControllerBind.CODEC);
		c2s.register(RequestMap.TYPE, RequestMap.CODEC);
		c2s.register(SetChannel.TYPE, SetChannel.CODEC);
		c2s.register(GlueApply.TYPE, GlueApply.CODEC);
		c2s.register(GlueAuto.TYPE, GlueAuto.CODEC);
		c2s.register(GlueRemove.TYPE, GlueRemove.CODEC);
		c2s.register(AssembleExtender.TYPE, AssembleExtender.CODEC);
		c2s.register(RemoveExtender.TYPE, RemoveExtender.CODEC);
		c2s.register(ScriptRequest.TYPE, ScriptRequest.CODEC);
		c2s.registerLarge(ScriptAction.TYPE, ScriptAction.CODEC, 1 << 20);
		c2s.register(ElevatorPanelSelect.TYPE, ElevatorPanelSelect.CODEC);
		s2c.registerLarge(ScriptState.TYPE, ScriptState.CODEC, 1 << 21);
		s2c.registerLarge(OpenExtenderAssembly.TYPE, OpenExtenderAssembly.CODEC, 1 << 21);
		s2c.registerLarge(HudSync.TYPE, HudSync.CODEC, 1 << 20);
		s2c.register(OpenDeviceConfig.TYPE, OpenDeviceConfig.CODEC);
		s2c.register(ExtenderMotion.TYPE, ExtenderMotion.CODEC);
		s2c.register(OpenKeyboardConfig.TYPE, OpenKeyboardConfig.CODEC);
		s2c.registerLarge(NetworkMap.TYPE, NetworkMap.CODEC, 1 << 22);
		NetlinkServerHandlers.register();
	}
}
