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

	/**
	 * Draws a live tactical radar centered on the player, plotting discovered landmarks (Home, Dungeons, Villages)
	 * and the partner's real-time position, direction vector, distance, and coordinates.
	 */
	public static void renderRadar(GuiGraphicsExtractor g, int x, int y, int size, boolean showDetails) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		g.fill(x, y, x + size, y + size, 0xCC091412);
		g.fill(x, y, x + size, y + 1, BORDER);
		g.fill(x, y + size - 1, x + size, y + size, BORDER);
		g.fill(x, y, x + 1, y + size, BORDER);
		g.fill(x + size - 1, y, x + size, y + size, BORDER);

		int cx = x + size / 2;
		int cy = y + size / 2;
		// Subtle radar grid lines
		g.fill(x + 2, cy, x + size - 2, cy + 1, 0x4448655A);
		g.fill(cx, y + 2, cx + 1, y + size - 2, 0x4448655A);

		if (mc.player == null || !ClientState.present()) return;

		double ox = mc.player.getX(), oz = mc.player.getZ();
		double dx = ClientState.px - ox, dz = ClientState.pz - oz;
		double dist = Math.hypot(dx, dz);
		double maxRange = Math.max(64.0, Math.min(2500.0, dist * 1.3));
		int radius = (size / 2) - 6;

		// Plot POIs from ClientState.pois ("H:x,y,z;D:x,y,z;V:x,y,z")
		if (ClientState.pois != null && !ClientState.pois.isEmpty()) {
			for (String token : ClientState.pois.split(";")) {
				if (token.length() < 3 || token.charAt(1) != ':') continue;
				char kind = token.charAt(0);
				String[] parts = token.substring(2).split(",");
				if (parts.length < 3) continue;
				try {
					int px = Integer.parseInt(parts[0]);
					int pz = Integer.parseInt(parts[2]);
					double pdx = (px - ox) / maxRange;
					double pdz = (pz - oz) / maxRange;
					double len = Math.hypot(pdx, pdz);
					if (len > 1.0) {
						pdx /= len;
						pdz /= len;
					}
					int dotX = cx + (int) Math.round(pdx * radius);
					int dotY = cy + (int) Math.round(pdz * radius);
					int poiColor = switch (kind) {
						case 'H' -> HELP;       // Home base (gold)
						case 'D' -> ALERT;      // Dungeon / Spawner (coral red)
						case 'V' -> 0xFF55FF88; // Village / Trader (emerald green)
						case 'S' -> 0xFFE699FF; // Structure / Ruins / Temple / Portal (amethyst purple)
						case 'M' -> 0xFFFF4444; // Hostile Mob Cluster / Danger (bright red)
						case 'A' -> 0xFFFFBB55; // Animal Herd (warm amber)
						case 'O' -> 0xFF55FFFF; // Rich Ore Vein / Lava Resource (diamond cyan)
						default -> 0xFF77CCFF;  // Biome landmark (sky blue)
					};
					g.fill(dotX - 1, dotY - 1, dotX + 2, dotY + 2, poiColor);
				} catch (NumberFormatException ignored) {}
			}
		}

		// Player dot at center
		g.fill(cx - 1, cy - 1, cx + 2, cy + 2, TEXT);

		// Partner dot & direction trail
		double ndx = dx / maxRange;
		double ndz = dz / maxRange;
		double nlen = Math.hypot(ndx, ndz);
		if (nlen > 1.0) {
			ndx /= nlen;
			ndz /= nlen;
		}
		int pxDot = cx + (int) Math.round(ndx * radius);
		int pzDot = cy + (int) Math.round(ndz * radius);

		// Dotted line from center to partner
		for (int i = 1; i <= 5; i++) {
			int lx = cx + (pxDot - cx) * i / 6;
			int ly = cy + (pzDot - cy) * i / 6;
			g.fill(lx, ly, lx + 1, ly + 1, 0x8892F0BD);
		}
		// Partner pulsing marker
		g.fill(pxDot - 2, pzDot - 2, pxDot + 3, pzDot + 3, 0x6692F0BD);
		g.fill(pxDot - 1, pzDot - 1, pxDot + 2, pzDot + 2, ACCENT);

		if (showDetails) {
			fitText(g, Component.literal("N"), cx, y + 2, 10, MUTED, true);
			String distStr = (int) Math.round(dist) + "m";
			fitText(g, Component.literal(distStr), x + 4, y + size - 9, size - 8, ACCENT, false);
			String coords = ClientState.px + ", " + ClientState.py + ", " + ClientState.pz;
			fitText(g, Component.literal(coords), cx, y + size + 3, size + 16, MUTED, true);
		}
	}

	private Ui() {}
}
