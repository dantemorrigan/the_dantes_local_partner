package com.dantemorrigan.partner.client;

import com.dantemorrigan.partner.PartnerNetwork;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * The command wheel (C), built from the design pack's layers: 224 × 224 sprites sharing one center, six 60° sectors
 * starting at the top and going clockwise, a 49 px neutral hub. Mouse picks the sector, arrows/Tab move the focus,
 * LMB/Enter confirms, Esc or C closes without a choice. Does not pause the game.
 */
public final class CommandWheelScreen extends Screen {
	private static final String[] IDS = {"follow", "wood", "ore", "guard", "patrol", "free"};
	private static final int[] ANGLES = {-90, -30, 30, 90, 150, 210};
	private static final int[][] ICON = {{112, 34}, {180, 73}, {180, 151}, {112, 190}, {44, 151}, {44, 73}};
	private static final int[] ACCENT = {0xFF92F0BD, 0xFFF2BB84, 0xFFA2DFED, 0xFFF4A594, 0xFFC2C8FF, 0xFFE0EABA};
	private static final int SIZE = 224, INNER = 49, OUTER = 107;

	private int focus = -1;
	private boolean keyboard;
	private double lastMx = -1, lastMy = -1;

	public CommandWheelScreen() {
		super(Component.translatable("local_partner.wheel.title"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private boolean compact() {
		return width < 456;
	}

	private float scale() {
		float usable = compact() ? Math.min(width - 16, height - 110) : Math.min(width - 260, height - 70);
		return Math.max(0.45f, Math.min(1.25f, usable / SIZE));
	}

	private float cx() {
		return compact() ? width / 2f : width / 2f - 30;
	}

	private float cy() {
		return compact() ? (height - 70) / 2f + 18 : height / 2f;
	}

	private static boolean available(int sector) {
		return ClientState.present();
	}

	private int sectorAt(double mx, double my) {
		float s = scale();
		double dx = (mx - cx()) / s, dy = (my - cy()) / s;
		double r = Math.hypot(dx, dy);
		if (r < INNER || r > OUTER + 6) return -1;
		double deg = Math.toDegrees(Math.atan2(dy, dx)); // 0 = right, clockwise positive
		for (int i = 0; i < 6; i++) {
			double diff = ((deg - ANGLES[i]) % 360 + 540) % 360 - 180;
			if (Math.abs(diff) <= 30) return i;
		}
		return -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		if (mx != lastMx || my != lastMy) {
			lastMx = mx;
			lastMy = my;
			int under = sectorAt(mx, my);
			if (under >= 0 || !keyboard) {
				focus = under;
				keyboard = false;
			}
		}
		g.fill(0, 0, width, height, 0x8A0B1513);
		Font font = Minecraft.getInstance().font;
		float s = scale(), x0 = cx() - SIZE * s / 2, y0 = cy() - SIZE * s / 2;

		// Title: keycap + LOCAL PARTNER, top left.
		Ui.frame(g, "keycap", 10, 10, 20, 20);
		Component key = PartnerKeys.WHEEL.getTranslatedKeyMessage();
		g.text(font, key, 20 - font.width(key) / 2, 16, Ui.TEXT, false);
		g.text(font, Component.translatable("local_partner.title").withStyle(ChatFormatting.BOLD), 36, 16, Ui.TEXT, true);

		// Layers: ring, selected/disabled, hub, icons, text.
		Ui.tex(g, "wheel/ring", x0, y0, SIZE * s, SIZE * s, SIZE, SIZE);
		for (int i = 0; i < 6; i++) {
			if (!available(i)) Ui.tex(g, "wheel/disabled/" + IDS[i], x0, y0, SIZE * s, SIZE * s, SIZE, SIZE);
			else if (i == focus) Ui.tex(g, "wheel/selected/" + IDS[i], x0, y0, SIZE * s, SIZE * s, SIZE, SIZE);
		}
		Ui.tex(g, "wheel/hub", x0, y0, SIZE * s, SIZE * s, SIZE, SIZE);
		float iconSize = 16 * s * 1.25f;
		for (int i = 0; i < 6; i++) {
			Ui.icon(g, IDS[i], x0 + ICON[i][0] * s - iconSize / 2, y0 + ICON[i][1] * s - iconSize / 2, iconSize);
		}
		Ui.icon(g, "partner", x0 + 112 * s - iconSize / 2, y0 + 101 * s - iconSize / 2, iconSize);
		Ui.fitText(g, Component.translatable("local_partner.wheel.title").withStyle(ChatFormatting.BOLD),
				(int) (x0 + 112 * s), (int) (y0 + 124 * s) - 4, (int) (80 * s), Ui.ACCENT, true);

		// Description card: right of the wheel, or under it on narrow screens.
		int cardW = 132, cardH = 62;
		int cardX = compact() ? (int) (width / 2f - cardW / 2f) : (int) (x0 + SIZE * s + 8);
		int cardY = compact() ? (int) (y0 + SIZE * s + 6) : (int) (cy() - cardH / 2f);
		cardX = Math.min(cardX, width - cardW - 6);
		Ui.frame(g, "panel", cardX, cardY, cardW, cardH);
		if (focus < 0) {
			Ui.icon(g, "partner", cardX + 7, cardY + 7, 12);
			Ui.fitText(g, Component.translatable("local_partner.wheel.neutral"), cardX + 23, cardY + 9, cardW - 30, Ui.ACCENT, false);
			g.textWithWordWrap(font, Component.translatable("local_partner.wheel.hover_hint"), cardX + 7, cardY + 25, cardW - 14, Ui.MUTED, false);
		} else if (!available(focus)) {
			Ui.icon(g, "alert", cardX + 7, cardY + 7, 12);
			Ui.fitText(g, Component.translatable("local_partner.wheel.unavailable"), cardX + 23, cardY + 9, cardW - 30, Ui.ALERT, false);
			g.textWithWordWrap(font, Component.translatable("local_partner.wheel.unavailable_detail"), cardX + 7, cardY + 25, cardW - 14, Ui.MUTED, false);
		} else {
			Ui.icon(g, IDS[focus], cardX + 7, cardY + 7, 12);
			Ui.fitText(g, Component.translatable("local_partner.command." + IDS[focus]).withStyle(ChatFormatting.BOLD),
					cardX + 23, cardY + 9, cardW - 30, ACCENT[focus], false);
			g.textWithWordWrap(font, Component.translatable("local_partner.command." + IDS[focus] + ".desc"), cardX + 7, cardY + 25, cardW - 14, Ui.MUTED, false);
		}

		// Controls hint, bottom left.
		Component hint = Component.translatable("local_partner.wheel.hint");
		int hw = Math.min(width - 20, font.width(hint) + 16);
		Ui.frame(g, "panel", 10, height - 30, hw, 20);
		Ui.fitText(g, hint, 18, height - 24, hw - 16, Ui.TEXT, false);
	}

	private void confirm() {
		if (focus < 0 || !available(focus)) {
			if (focus >= 0) Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.6f));
			return;
		}
		if (ClientPlayNetworking.canSend(PartnerNetwork.Command.TYPE)) ClientPlayNetworking.send(new PartnerNetwork.Command(IDS[focus]));
		Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2f));
		ClientState.order = IDS[focus];
		ClientState.toast("accepted");
		onClose();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			int under = sectorAt(event.x(), event.y());
			if (under >= 0) focus = under;
			if (under >= 0) confirm();
		} else if (event.button() == 1) onClose();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == GLFW.GLFW_KEY_ESCAPE || key == KeyMappingHelper.getBoundKeyOf(PartnerKeys.WHEEL).getValue()) {
			onClose();
			return true;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
			confirm();
			return true;
		}
		int step = switch (key) {
			case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_TAB -> (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
			case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_UP -> -1;
			default -> 0;
		};
		if (step != 0) {
			focus = focus < 0 ? (step > 0 ? 0 : 5) : Math.floorMod(focus + step, 6);
			keyboard = true;
		}
		return true; // gameplay keys never leak out of the open wheel
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(null);
	}
}
