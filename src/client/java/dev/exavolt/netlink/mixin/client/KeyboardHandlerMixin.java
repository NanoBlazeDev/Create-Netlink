package dev.exavolt.netlink.mixin.client;

import dev.exavolt.netlink.client.ElevatorPanelClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While an Elevator Panel has focus, number keys, Enter, Backspace and Esc go to the panel instead of the game. */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void netlink$panelKeys(long handle, int action, KeyEvent event, CallbackInfo ci) {
		if (ElevatorPanelClient.onKey(event.key(), action)) {
			ci.cancel();
		}
	}
}
