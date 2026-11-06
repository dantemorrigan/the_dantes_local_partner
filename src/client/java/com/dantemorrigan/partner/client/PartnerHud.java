package com.dantemorrigan.partner.client;

import com.dantemorrigan.partner.LocalPartner;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The status card, bottom left (clear of the hotbar and of The Dante's Infinity's top-left panels):
 * a short toast (alert, assist, recall, accepted...) for two seconds, otherwise the current task.
 * Keeps 8 px from the hotbar; hidden when the GUI is too small for it.
 */
public final class PartnerHud {
	private static final int MARGIN_X = 12, MARGIN_Y = 11, MIN_W = 96, MAX_W = 139;

	public static void init() {
		// Drawn under the chat, so chat messages stay readable over the card.
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,
				LocalPartner.id("status_card"), (g, delta) -> render(g));
	}

	private static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || mc.screen instanceof CommandWheelScreen) return;
		String toast = ClientState.toast();
		if (ClientState.state == 0 && toast == null) return;
		int screenW = g.guiWidth(), screenH = g.guiHeight();
		int available = screenW / 2 - 91 - 8 - MARGIN_X;
		if (available < MIN_W) return;
		int w = Math.min(MAX_W, available);
		Font font = mc.font;

		String icon;
		Component line;
		int color;
		float alpha = 1f;
		if (toast != null) {
			icon = switch (toast) {
				case "summon" -> "partner";
				case "unavailable" -> "alert";
				default -> toast;
			};
			line = Component.translatable("local_partner.status." + toast);
			color = switch (toast) {
				case "alert", "unavailable" -> Ui.ALERT;
				case "assist" -> Ui.HELP;
				default -> Ui.ACCENT;
			};
			alpha = ClientState.toastAlpha();
		} else {
			String order = ClientState.order.isEmpty() ? "follow" : ClientState.order;
			icon = ClientState.state == 1 ? order : "alert";
			line = ClientState.state == 1 ? Component.translatable("local_partner.status." + order)
					: Component.translatable(ClientState.state == 3 ? "local_partner.pause.respawning" : "local_partner.pause.waiting");
			color = ClientState.state == 1 ? Ui.TEXT : Ui.MUTED;
		}
		List<net.minecraft.util.FormattedCharSequence> lines = font.split(line, w - 30);
		int h = 22 + Math.max(1, lines.size()) * 9 - 9 + 12;
		int x = MARGIN_X, y = screenH - MARGIN_Y - h; // grows upward when the text wraps
		Ui.frame(g, toast != null ? "toast" : "hud", x, y, w, h);
		Ui.icon(g, icon, x + 6, y + (h - 12) / 2f, 12);
		Ui.fitText(g, Component.translatable("local_partner.title"), x + 24, y + 6, w - 30, Ui.withAlpha(Ui.ACCENT, alpha), false);
		int ty = y + 17;
		for (var l : lines) {
			g.text(font, l, x + 24, ty, Ui.withAlpha(color, alpha), false);
			ty += 9;
		}
	}

	private PartnerHud() {}
}
