package com.dantemorrigan.partner.client;

import com.dantemorrigan.partner.LocalPartner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Drawing helpers for the design pack: palette, 9-slice frames, plain textures, buttons, text. */
public final class Ui {
	public static final int BACKGROUND = 0xFF10201E, PANEL = 0xFF182B27, BORDER = 0xFF48655A, ACCENT = 0xFF92F0BD,
			TEXT = 0xFFE9F5E6, MUTED = 0xFFAAC3B5, HELP = 0xFFFFD182, ALERT = 0xFFFF9A85;

	public enum Style { NORMAL, PRIMARY, DANGER, DISABLED }

	/** 9-slice frame from {@code textures/gui/sprites/frames/<name>.png}. */
	public static void frame(GuiGraphicsExtractor g, String name, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, LocalPartner.id("frames/" + name), x, y, w, h);
	}

	/** A whole texture {@code textures/gui/<path>.png} drawn at any size (nearest filtering). */
	public static void tex(GuiGraphicsExtractor g, String path, float x, float y, float w, float h, int srcW, int srcH) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / srcW, h / srcH);
		g.blit(RenderPipelines.GUI_TEXTURED, LocalPartner.id("textures/gui/" + path + ".png"), 0, 0, 0f, 0f, srcW, srcH, srcW, srcH);
		g.pose().popMatrix();
	}

	public static void icon(GuiGraphicsExtractor g, String name, float x, float y, float size) {
		tex(g, "icons/" + name, x, y, size, size, 16, 16);
	}

	public static void button(GuiGraphicsExtractor g, Component label, int x, int y, int w, int h, Style style, boolean hover) {
		String frame = switch (style) {
			case PRIMARY -> hover ? "button_primary_hover" : "button_primary";
			case DANGER -> hover ? "button_danger_hover" : "button_danger";
			case DISABLED -> "button_disabled";
			case NORMAL -> hover ? "button_hover" : "button";
		};
		frame(g, frame, x, y, w, h);
		int color = style == Style.DISABLED ? MUTED : TEXT;
		fitText(g, label, x + w / 2, y + (h - 8) / 2, w - 8, color, true);
	}

	public static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	/** Text scaled down to fit {@code maxWidth}. */
	public static void fitText(GuiGraphicsExtractor g, Component text, int x, int y, int maxWidth, int color, boolean centered) {
		Font font = Minecraft.getInstance().font;
		int w = font.width(text);
		float s = w > maxWidth ? maxWidth / (float) w : 1f;
		g.pose().pushMatrix();
		g.pose().translate(centered ? x - w * s / 2f : x, y);
		g.pose().scale(s, s);
		g.text(font, text, 0, 0, color, true);
		g.pose().popMatrix();
	}

	public static int withAlpha(int color, float alpha) {
		return ((int) (((color >>> 24) & 0xFF) * alpha) << 24) | (color & 0xFFFFFF);
	}

	private Ui() {}
}
