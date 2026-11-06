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

	/** When each recent chat message arrived and how many chat lines it takes (to keep the card clear of chat). */
	private static final java.util.ArrayDeque<long[]> CHAT = new java.util.ArrayDeque<>();
	private static final long CHAT_VISIBLE_MS = 10_000;
	private static float shownBottom = -1;

	private static void onChat(Component message) {
		Minecraft mc = Minecraft.getInstance();
		int width = (int) (net.minecraft.client.gui.components.ChatComponent.getWidth(mc.options.chatWidth().get()) / mc.options.chatScale().get());
		int lines = Math.max(1, mc.font.split(message, Math.max(40, width - 12)).size());
		CHAT.addLast(new long[]{net.minecraft.util.Util.getMillis(), lines});
		while (CHAT.size() > 30) CHAT.removeFirst();
	}

	/** Height in GUI pixels of the chat lines currently shown above the hotbar (0 when chat is quiet). */
	private static int chatHeight(Minecraft mc) {
		long now = net.minecraft.util.Util.getMillis();
		CHAT.removeIf(e -> now - e[0] > CHAT_VISIBLE_MS);
		int lines = 0;
		for (long[] e : CHAT) lines += (int) e[1];
		if (lines == 0) return 0;
		lines = Math.min(lines, 10);
		double scale = mc.options.chatScale().get();
		double lineHeight = 9.0 * (mc.options.chatLineSpacing().get() + 1.0);
		return (int) Math.ceil(lines * lineHeight * scale) + 2;
	}

	public static void init() {
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) onChat(message);
		});
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> onChat(message));
		// Drawn under the chat, so chat messages stay readable over the card.
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,
				LocalPartner.id("status_card"), (g, delta) -> render(g));
	}

	private static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || mc.screen instanceof CommandWheelScreen
				|| mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return;
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
			icon = ClientState.state != 1 ? "alert" : switch (ClientState.activity) {
				case "wood" -> "wood";
				case "ore", "stairs" -> "ore";
				case "fight", "fight_boss", "guard", "retreat" -> "guard";
				case "patrol" -> "patrol";
				case "help_food", "recover", "returning" -> "assist";
				case "hunt", "craft", "free", "sleep" -> "free";
				default -> order;
			};
			// What it is doing right now (activity), with the progress detail; the order is the fallback.
			if (ClientState.state == 1) {
				Component what = ClientState.activity.isEmpty() ? Component.translatable("local_partner.status." + order)
						: Component.translatable("partner.local_partner.activity." + ClientState.activity);
				line = ClientState.detail.isEmpty() ? what : Component.empty().append(what).append(Component.literal("  " + ClientState.detail).withColor(Ui.MUTED));
			} else {
				line = Component.translatable(ClientState.state == 3 ? "local_partner.pause.respawning" : "local_partner.pause.waiting");
			}
			color = ClientState.state == 1 ? Ui.TEXT : Ui.MUTED;
		}
		List<net.minecraft.util.FormattedCharSequence> lines = font.split(line, w - 30);
		int h = 22 + Math.max(1, lines.size()) * 9 - 9 + 12;
		// Bottom left; while chat lines are on screen the card moves up above them (smoothly), so both stay readable.
		int chat = chatHeight(mc);
		float target = chat > 0 ? screenH - 40 - chat - 3 : screenH - MARGIN_Y;
		shownBottom = shownBottom < 0 ? target : shownBottom + (target - shownBottom) * 0.25f;
		if (Math.abs(shownBottom - target) < 0.5f) shownBottom = target;
		int x = MARGIN_X, y = Math.round(shownBottom) - h; // grows upward when the text wraps
		Ui.frame(g, toast != null ? "toast" : "hud", x, y, w, h);
		Ui.icon(g, icon, x + 6, y + (h - 12) / 2f, 12);
		Component title = Component.translatable("local_partner.title");
		if (toast == null && ClientState.state == 1) {
			title = Component.empty().append(title).append(Component.literal(" · ").withColor(Ui.MUTED))
					.append(Component.translatable("partner.local_partner.era." + ClientState.era).withColor(Ui.HELP));
		}
		Ui.fitText(g, title, x + 24, y + 6, w - 30, Ui.withAlpha(Ui.ACCENT, alpha), false);
		int ty = y + 17;
		for (var l : lines) {
			g.text(font, l, x + 24, ty, Ui.withAlpha(color, alpha), false);
			ty += 9;
		}
	}

	private PartnerHud() {}
}
