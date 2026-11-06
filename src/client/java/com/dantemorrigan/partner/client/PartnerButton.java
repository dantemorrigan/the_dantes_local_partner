package com.dantemorrigan.partner.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A button in the design pack's style (9-slice frames, mint accent, partner icon), used for the pause-menu entry,
 * the partner panel and the partner's backpack screen. An optional status dot shows whether the partner is in the world.
 */
public final class PartnerButton extends AbstractButton {
	private final Runnable action;
	private final Ui.Style style;
	private final @Nullable String icon;
	private final @Nullable Supplier<Boolean> dot;

	public PartnerButton(int x, int y, int w, int h, Component label, Ui.Style style, @Nullable String icon, @Nullable Supplier<Boolean> dot, Runnable action) {
		super(x, y, w, h, label);
		this.action = action;
		this.style = style;
		this.icon = icon;
		this.dot = dot;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		action.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		boolean hover = this.isHoveredOrFocused();
		Ui.Style s = this.active ? style : Ui.Style.DISABLED;
		String frame = switch (s) {
			case PRIMARY -> hover ? "button_primary_hover" : "button_primary";
			case DANGER -> hover ? "button_danger_hover" : "button_danger";
			case DISABLED -> "button_disabled";
			case NORMAL -> hover ? "button_hover" : "button";
		};
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		Ui.frame(g, frame, x, y, w, h);
		var font = Minecraft.getInstance().font;
		int left = x + 6;
		if (icon != null) {
			Ui.icon(g, icon, x + 5, y + (h - 12) / 2f, 12);
			left = x + 21;
		}
		int right = x + w - 6;
		if (dot != null) {
			boolean on = dot.get();
			int dx = x + w - 10, dy = y + h / 2 - 2;
			g.fill(dx, dy, dx + 4, dy + 4, on ? Ui.ACCENT : Ui.MUTED);
			right = dx - 4;
		}
		int color = !this.active ? Ui.MUTED : hover ? Ui.ACCENT : Ui.TEXT;
		Component label = getMessage();
		int textW = font.width(label);
		int avail = right - left;
		int cx = icon == null && dot == null ? x + w / 2 : left + avail / 2;
		Ui.fitText(g, label, Math.max(left + Math.min(textW, avail) / 2, cx), y + (h - 8) / 2, avail, color, true);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}
