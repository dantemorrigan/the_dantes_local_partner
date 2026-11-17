package com.dantemorrigan.kingdoms.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
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

	static void fit(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int maxW, int color, float scale) {
		int w = font.width(text);
		float s = w * scale > maxW ? maxW / (float) Math.max(1, w) : scale;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(s, s);
		g.text(font, text, 0, 0, color, true);
		g.pose().popMatrix();
	}

	static void fitCentered(GuiGraphicsExtractor g, Font font, Component text, int cx, int y, int maxW, int color) {
		int w = font.width(text);
		float s = w > maxW ? maxW / (float) Math.max(1, w) : 1f;
		g.pose().pushMatrix();
		g.pose().translate(cx - w * s / 2f, y);
		g.pose().scale(s, s);
		g.text(font, text, 0, 0, color, true);
		g.pose().popMatrix();
	}

	static int wrap(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int maxW, int color) {
		return wrap(g, font, text, x, y, maxW, color, 1f);
	}

	/** Returns the y below the text. */
	static int wrap(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int maxW, int color, float scale) {
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
