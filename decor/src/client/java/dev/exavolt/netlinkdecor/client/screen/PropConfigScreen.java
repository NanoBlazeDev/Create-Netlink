package dev.exavolt.netlinkdecor.client.screen;

import dev.exavolt.netlink.client.screen.NetlinkScreen;
import dev.exavolt.netlink.net.NetlinkPackets.NetEntry;
import dev.exavolt.netlinkdecor.net.DecorPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Links a wireless prop to a Netlink channel so it reacts to it. */
public class PropConfigScreen extends NetlinkScreen {
	private final DecorPackets.OpenPropConfig data;
	private int network;
	private EditBox channel;
	private final List<int[]> chipRects = new ArrayList<>();
	private final List<String> chipNames = new ArrayList<>();

	public PropConfigScreen(DecorPackets.OpenPropConfig data) {
		super(Component.translatable("gui.netlinkdecor.prop.title"), 256, 170);
		this.data = data;
		if (data.owner().isPresent()) {
			for (int i = 0; i < data.networks().size(); i++) {
				if (data.networks().get(i).owner().equals(data.owner().get())) {
					network = i;
				}
			}
		}
	}

	private NetEntry net() {
		return data.networks().isEmpty() ? null : data.networks().get(Math.floorMod(network, data.networks().size()));
	}

	@Override
	protected void init() {
		super.init();
		int x = left + 10;
		int y = top + 26;
		button(x + 66, y, 170, 16, Component.literal(net() == null ? "-" : net().name()), b -> {
			network++;
			b.setMessage(Component.literal(net() == null ? "-" : net().name()));
		});
		inset_y = y + 22;
		channel = field(x + 66, inset_y, 170, data.channel());
		button(x, top + panelHeight - 26, 90, 18, Component.translatable("gui.netlinkdecor.prop.unlink"), b -> {
			ClientPlayNetworking.send(new DecorPackets.ApplyPropConfig(data.pos(), Optional.empty(), ""));
			super.onClose();
		});
		button(left + panelWidth - 10 - 90, top + panelHeight - 26, 90, 18, Component.translatable("gui.netlinkdecor.save"), b -> save());
	}

	private int inset_y;

	private void save() {
		NetEntry n = net();
		ClientPlayNetworking.send(new DecorPackets.ApplyPropConfig(data.pos(), n == null ? Optional.empty() : Optional.of(n.owner()),
			channel.getValue().trim()));
		super.onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == 257 || event.key() == 335) {
			save();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		inset(g, left + 76, inset_y, 170, 20);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int x = left + 10;
		g.text(font, Component.translatable("gui.netlinkdecor.prop.network"), x, top + 30, TEXT_DIM, false);
		g.text(font, Component.translatable("gui.netlinkdecor.prop.channel"), x, inset_y + 4, TEXT_DIM, false);
		// channel chips of the selected network
		chipRects.clear();
		chipNames.clear();
		NetEntry n = net();
		int cx = x, cy = inset_y + 28;
		if (n != null) {
			for (String c : n.channels()) {
				int w = Math.min(120, font.width(c) + 8);
				if (cx + w > left + panelWidth - 10) {
					cx = x;
					cy += 14;
				}
				if (cy > top + panelHeight - 44) {
					break;
				}
				boolean hover = mouseX >= cx && mouseX < cx + w && mouseY >= cy && mouseY < cy + 12;
				g.fill(cx, cy, cx + w, cy + 12, hover ? 0xFF22B6DC : 0xFF1A2230);
				g.text(font, trim(font, c, w - 6), cx + 4, cy + 2, hover ? 0xFF0B0F16 : TEXT, false);
				chipRects.add(new int[]{cx, cy, w});
				chipNames.add(c);
				cx += w + 4;
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < chipRects.size(); i++) {
			int[] r = chipRects.get(i);
			if (event.x() >= r[0] && event.x() < r[0] + r[2] && event.y() >= r[1] && event.y() < r[1] + 12) {
				channel.setValue(chipNames.get(i));
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}
}
