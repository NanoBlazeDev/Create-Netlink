package dev.exavolt.netlink.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.exavolt.netlink.Netlink;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** World-wide persistent state: networks (owner + authorized players), devices and manual channel values. */
public final class NetworkSavedData extends SavedData {
	public static final class NetworkInfo {
		public final UUID owner;
		public String ownerName;
		public final Map<UUID, String> authorized;

		public NetworkInfo(UUID owner, String ownerName, Map<UUID, String> authorized) {
			this.owner = owner;
			this.ownerName = ownerName;
			this.authorized = new LinkedHashMap<>(authorized);
		}

		public static final Codec<NetworkInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("owner").forGetter(n -> n.owner),
			Codec.STRING.optionalFieldOf("name", "?").forGetter(n -> n.ownerName),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING).optionalFieldOf("authorized", Map.of()).forGetter(n -> n.authorized)
		).apply(i, NetworkInfo::new));
	}

	public static final Codec<NetworkSavedData> CODEC = RecordCodecBuilder.create(i -> i.group(
		NetworkInfo.CODEC.listOf().optionalFieldOf("networks", List.of()).forGetter(d -> new ArrayList<>(d.networks.values())),
		DeviceRecord.CODEC.listOf().optionalFieldOf("devices", List.of()).forGetter(d -> new ArrayList<>(d.devices.values())),
		Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("manual", Map.of()).forGetter(d -> d.manual),
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, dev.exavolt.netlink.script.ScriptRecord.CODEC.listOf()).optionalFieldOf("scripts", Map.of())
			.forGetter(d -> d.scripts),
		Codec.unboundedMap(Codec.STRING, ElevatorParams.CODEC).optionalFieldOf("elevators", Map.of()).forGetter(d -> d.elevators),
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.unboundedMap(Codec.STRING, Codec.STRING)).optionalFieldOf("storage", Map.of())
			.forGetter(d -> d.storage),
		Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("clocks", Map.of()).forGetter(d -> d.clocks)
	).apply(i, NetworkSavedData::new));

	public static final SavedDataType<NetworkSavedData> TYPE = new SavedDataType<>(
		Netlink.id("networks"), NetworkSavedData::new, CODEC, null
	);

	public final Map<UUID, NetworkInfo> networks = new LinkedHashMap<>();
	public final Map<String, DeviceRecord> devices = new LinkedHashMap<>();
	public final Map<String, Integer> manual = new HashMap<>();
	public final Map<UUID, List<dev.exavolt.netlink.script.ScriptRecord>> scripts = new LinkedHashMap<>();
	/** Advanced elevator settings keyed by device key. */
	public final Map<String, ElevatorParams> elevators = new HashMap<>();
	/** Script storage (storage.get / storage.set) per network; scripts touch it from their own threads. */
	public final Map<UUID, Map<String, String>> storage = new java.util.concurrent.ConcurrentHashMap<>();
	/** Running keyboard pulse trains: channel key -> ticks on/off. */
	public final Map<String, Integer> clocks = new HashMap<>();

	public NetworkSavedData() {
	}

	private NetworkSavedData(List<NetworkInfo> networks, List<DeviceRecord> devices, Map<String, Integer> manual,
	                         Map<UUID, List<dev.exavolt.netlink.script.ScriptRecord>> scripts, Map<String, ElevatorParams> elevators, Map<UUID, Map<String, String>> storage, Map<String, Integer> clocks) {
		this.clocks.putAll(clocks);
		storage.forEach((k, v) -> this.storage.put(k, new java.util.concurrent.ConcurrentHashMap<>(v)));
		scripts.forEach((k, v) -> this.scripts.put(k, new ArrayList<>(v)));
		this.elevators.putAll(elevators);
		for (NetworkInfo n : networks) {
			this.networks.put(n.owner, n);
		}
		for (DeviceRecord d : devices) {
			this.devices.put(d.key(), d);
		}
		this.manual.putAll(manual);
	}

	public static NetworkSavedData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public static String channelKey(UUID owner, String channel) {
		return owner + "|" + channel;
	}

	public NetworkInfo network(UUID owner, String nameIfNew) {
		NetworkInfo info = networks.get(owner);
		if (info == null) {
			info = new NetworkInfo(owner, nameIfNew, Map.of());
			networks.put(owner, info);
			setDirty();
		} else if (nameIfNew != null && !nameIfNew.equals("?") && !nameIfNew.equals(info.ownerName)) {
			info.ownerName = nameIfNew;
			setDirty();
		}
		return info;
	}
}
