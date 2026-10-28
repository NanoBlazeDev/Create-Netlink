package dev.exavolt.netlink.script;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** A saved script on a network. */
public final class ScriptRecord {
	public final String id;
	public String name;
	public String code;
	public boolean enabled;

	public ScriptRecord(String id, String name, String code, boolean enabled) {
		this.id = id;
		this.name = name;
		this.code = code;
		this.enabled = enabled;
	}

	public static final Codec<ScriptRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("id").forGetter(r -> r.id),
		Codec.STRING.fieldOf("name").forGetter(r -> r.name),
		Codec.STRING.fieldOf("code").forGetter(r -> r.code),
		Codec.BOOL.optionalFieldOf("enabled", false).forGetter(r -> r.enabled)
	).apply(i, ScriptRecord::new));
}
