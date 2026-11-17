package com.dantemorrigan.kingdoms.client;

import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.net.KNet;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** M opens the strategic map. Citizens get their renderer; map packets go to {@link ClientRealm}. */
public final class KingdomsClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(KingdomsMod.id("kingdoms"));
	public static final KeyMapping MAP = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.kingdoms.map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY));

	private static int hintTicks = -1;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(KingdomsMod.CITIZEN, CitizenRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(KNet.Land.TYPE, (p, ctx) -> ClientRealm.land(p));
		ClientPlayNetworking.registerGlobalReceiver(KNet.State.TYPE, (p, ctx) -> ClientRealm.state(p.data()));
		ClientPlayConnectionEvents.JOIN.register((h, s, mc) -> {
			ClientRealm.reset();
			hintTicks = 100;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> {
			ClientRealm.reset();
			MapScreen.observing = false;
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			boolean pressed = false;
			while (MAP.consumeClick()) pressed = true;
			if (hintTicks > 0 && --hintTicks == 0 && mc.player != null && ClientPlayNetworking.canSend(KNet.View.TYPE)) {
				mc.player.sendOverlayMessage(Component.translatable("kingdoms.hint.open_map", MAP.getTranslatedKeyMessage()));
			}
			if (pressed && mc.player != null && mc.screen == null && ClientPlayNetworking.canSend(KNet.View.TYPE)) {
				mc.setScreen(new MapScreen());
			}
		});
	}
}
