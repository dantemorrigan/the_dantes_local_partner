package com.dantemorrigan.partner.client;

import com.dantemorrigan.partner.LocalPartner;
import com.dantemorrigan.partner.PartnerFx;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.chat.Component;

public final class LocalPartnerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(LocalPartner.PARTNER, PartnerRenderer::new);
		particle(PartnerFx.SUMMON, 12, 36);
		particle(PartnerFx.RECALL, 10, 37);
		particle(PartnerFx.ACCEPTED, 9, 24);
		particle(PartnerFx.GUARD, 8, 23);
		particle(PartnerFx.ASSIST, 10, 25);
		particle(PartnerFx.PATROL, 12, 34);
		particle(PartnerFx.DISMISS, 12, 36);
		ClientState.init();
		PartnerKeys.init();
		PartnerHud.init();
		pauseEntry();
		backpackButtons();
	}

	/** The partner's backpack (a chest screen titled "local_partner.backpack.title"): "Give me everything", "Resources only", "To chest" buttons. */
	private static void backpackButtons() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container)) return;
			if (!(screen.getTitle().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t)
					|| !t.getKey().equals("local_partner.backpack.title")) return;
			int bw = 96, bx = (w - bw * 3 - 8) / 2, by = Math.max(4, (h - 166) / 2 - 26);
			Screens.getWidgets(screen).add(new PartnerButton(bx, by, bw, 20, Component.translatable("local_partner.backpack.give_all"),
					Ui.Style.PRIMARY, "assist", null, () -> send("give_all")));
			Screens.getWidgets(screen).add(new PartnerButton(bx + bw + 4, by, bw, 20, Component.translatable("local_partner.backpack.give_res"),
					Ui.Style.NORMAL, "ore", null, () -> send("give_res")));
			Screens.getWidgets(screen).add(new PartnerButton(bx + (bw + 4) * 2, by, bw, 20, Component.translatable("local_partner.backpack.store"),
					Ui.Style.NORMAL, "free", null, () -> {
						send("store");
						client.setScreen(null);
					}));
		});
	}

	private static void send(String command) {
		if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(com.dantemorrigan.partner.PartnerNetwork.Command.TYPE)) {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.dantemorrigan.partner.PartnerNetwork.Command(command));
		}
	}

	private static void particle(SimpleParticleType type, int frames, int anchorY) {
		ParticleProviderRegistry.getInstance().register(type, sprites -> (t, level, x, y, z, xa, ya, za, random) ->
				new PartnerParticle(level, x, y, z, sprites, frames, anchorY));
	}

	/**
	 * "Local Partner…" in the pause menu's center column, right above the quit button (which moves down a row).
	 * The Dante's Infinity's own settings button sits in the bottom-left corner, so they never overlap.
	 */
	private static void pauseEntry() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof PauseScreen) || client.level == null) return;
			Button quit = null;
			for (AbstractWidget widget : Screens.getWidgets(screen).stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList()) {
				if (!(widget instanceof Button b) || b.getWidth() < 150) continue;
				if (Math.abs(b.getX() + b.getWidth() / 2 - w / 2) > 4) continue;
				if (quit == null || b.getY() > quit.getY()) quit = b;
			}
			if (quit == null) return; // pause menu without buttons (F3+Esc)
			int x = quit.getX(), y = quit.getY(), width = quit.getWidth();
			quit.setY(y + 24);
			Screens.getWidgets(screen).add(new PartnerButton(x, y, width, 20, Component.translatable("local_partner.pause.entry"),
					Ui.Style.NORMAL, "partner", () -> ClientState.state != 0, () -> client.setScreen(new PartnerPanelScreen(screen))));
		});
	}
}
