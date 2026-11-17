package com.dantemorrigan.kingdoms.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Text helpers for the map: fit into a width, wrap, center. */
final class Ui2 {
	static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	static void fit(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int maxW, int color) {
		fit(g, font, text, x, y, maxW, color, 1f);
	}

	/** Never draws with a zero or negative width (that mirrors the text); too long text is shrunk, then cut. */
	static void fit(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int maxW, int color, float scale) {
		if (maxW < 4) return;
		int w = Math.max(1, font.width(text));
		float s = w * scale > maxW ? maxW / (float) w : scale;
		FormattedCharSequence seq = text.getVisualOrderText();
		if (s < 0.5f) {
			s = 0.5f;
			seq = Language.getInstance().getVisualOrder(font.substrByWidth(text, Math.max(1, (int) (maxW / s))));
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(s, s);
		g.text(font, seq, 0, 0, color, true);
		g.pose().popMatrix();
	}

	static void fitCentered(GuiGraphicsExtractor g, Font font, Component text, int cx, int y, int maxW, int color) {
		if (maxW < 4) return;
		int w = Math.max(1, font.width(text));
		float s = w > maxW ? Math.max(0.5f, maxW / (float) w) : 1f;
		FormattedCharSequence seq = text.getVisualOrderText();
		int shown = w;
		if (w * s > maxW) {
			seq = Language.getInstance().getVisualOrder(font.substrByWidth(text, Math.max(1, (int) (maxW / s))));
			shown = (int) (maxW / s);
		}
		g.pose().pushMatrix();
		g.pose().translate(cx - shown * s / 2f, y);
		g.pose().scale(s, s);
		g.text(font, seq, 0, 0, color, true);
		g.pose().popMatrix();
	}

	static int wrap(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int maxW, int color) {
		return wrap(g, font, text, x, y, maxW, color, 1f);
	}

	/** Returns the y below the text. */
	static int wrap(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int maxW, int color, float scale) {
		if (maxW < 8) return y;
		List<FormattedCharSequence> lines = font.split(text, (int) (maxW / scale));
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), 0, i * 9, color, true);
		g.pose().popMatrix();
		return y + (int) Math.ceil(lines.size() * 9 * scale);
	}

	private Ui2() {}
}
