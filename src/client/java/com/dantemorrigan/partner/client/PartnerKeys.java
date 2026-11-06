package com.dantemorrigan.partner.client;

import com.dantemorrigan.partner.LocalPartner;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** C opens the command wheel (rebindable; The Dante's Infinity keeps V/B/R). */
public final class PartnerKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(LocalPartner.id("partner"));
	public static final KeyMapping WHEEL = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.local_partner.wheel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY));

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			boolean pressed = false;
			while (WHEEL.consumeClick()) pressed = true;
			if (!pressed || mc.player == null || mc.screen != null) return;
			if (!ClientState.modOnServer) return;
			if (ClientState.state == 0) {
				mc.player.sendOverlayMessage(Component.translatable("local_partner.hud.none"));
				return;
			}
			if (ClientState.state != 1) ClientState.toast("unavailable");
			mc.setScreen(new CommandWheelScreen());
		});
	}

	private PartnerKeys() {}
}
