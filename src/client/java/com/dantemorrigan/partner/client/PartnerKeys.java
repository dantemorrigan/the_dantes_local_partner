package com.dantemorrigan.partner.client;

import com.dantemorrigan.partner.LocalPartner;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** C opens the command wheel; G pings a contextual target (rebindable; The Dante's Infinity keeps V/B/R). */
public final class PartnerKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(LocalPartner.id("partner"));
	public static final KeyMapping WHEEL = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.local_partner.wheel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY));
	public static final KeyMapping PING = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.local_partner.ping", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			boolean wheelPressed = false;
			while (WHEEL.consumeClick()) wheelPressed = true;
			boolean pingPressed = false;
			while (PING.consumeClick()) pingPressed = true;
			if (mc.player == null || mc.screen != null || !ClientState.modOnServer) return;

			if (pingPressed) {
				if (ClientState.state == 0) {
					mc.player.sendOverlayMessage(Component.translatable("local_partner.hud.none"));
				} else if (ClientState.state == 1 && net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(com.dantemorrigan.partner.PartnerNetwork.Command.TYPE)) {
					net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.dantemorrigan.partner.PartnerNetwork.Command("ping"));
					mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.35f));
				}
			}

			if (!wheelPressed) return;
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
