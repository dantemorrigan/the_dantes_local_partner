package com.dantemorrigan.kingdoms.client;

import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.sim.Terrain;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The two pictures of the map. Terrain: one pixel per 8x8 blocks, colored by landscape, hill-shaded by height,
 * with darker deep water and outlined coasts. Politics: four pixels per cell; ownership is blended between cells
 * and through time, so borders are smooth curves that slide across the land as cells change hands.
 */
public final class MapTextures {
	public static final Identifier TERRAIN = KingdomsMod.id("dynamic/terrain");
	public static final Identifier POLITICS = KingdomsMod.id("dynamic/politics");
	public static final int PX = 4;

	public enum Mode { POLITICAL, TERRAIN, DIPLOMACY }

	private static DynamicTexture terrain, politics;
	private static int terrainBuilt = -1, terrainSize;
	private static int politicsBuilt = -1;
	private static long politicsAt;
	private static Mode politicsMode;
	private static int politicsSelected;

	private static final int[] LAND = {
			0xFF1D3B66, 0xFF2C5A8E, 0xFF3D78B0, 0xFFD9CC8E, 0xFF8DB25A, 0xFF4E8A3B, 0xFFDCC380, 0xFFE6ECF0,
			0xFF8B8A84, 0xFF5B7146, 0xFF3A7A2A, 0xFFB4AA58, 0xFFC0693A, 0xFF4B6E53, 0xFF9BC061, 0xFFD6A2C2};

	public static int terrainSize() {
		return terrainSize;
	}

	public static void updateTerrain() {
		int size = ClientRealm.tSize;
		if (size == 0) return;
		if (terrain == null || terrainSize != size) {
			if (terrain != null) terrain.close();
			terrain = new DynamicTexture(() -> "kingdoms terrain", size, size, false);
			Minecraft.getInstance().getTextureManager().register(TERRAIN, terrain);
			terrainSize = size;
			terrainBuilt = -1;
		}
		if (terrainBuilt == ClientRealm.terrainVersion) return;
		terrainBuilt = ClientRealm.terrainVersion;
		NativeImage img = terrain.getPixels();
		byte[] cls = ClientRealm.tCls, h = ClientRealm.tHeight;
		int rows = ClientRealm.tRows;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				if (z >= rows) {
					img.setPixel(x, z, 0xFF2A2F3A);
					continue;
				}
				int i = z * size + x;
				int c = cls[i] & 15;
				int hh = h[i] & 0xFF;
				int col = LAND[c];
				if (Terrain.water(c)) {
					float depth = Math.max(0, Math.min(1, (62 - hh) / 40f));
					col = shade(col, 1.08f - depth * 0.45f);
					if (coast(cls, size, x, z, false)) col = mix(col, 0xFF7FB2D8, 0.35f);
				} else {
					int hw = x > 0 && z > 0 ? h[i - size - 1] & 0xFF : hh;
					float light = 1f + Math.max(-0.35f, Math.min(0.35f, (hh - hw) * 0.045f));
					if (hh > 150 && c != Terrain.SNOW) col = mix(col, 0xFFF4F6F8, Math.min(0.85f, (hh - 150) / 40f));
					else if (hh > 105) col = mix(col, 0xFF9A9890, Math.min(0.5f, (hh - 105) / 70f));
					col = shade(col, light);
					// paper grain
					int grain = ((x * 73856093) ^ (z * 19349663)) & 7;
					col = shade(col, 0.97f + grain * 0.008f);
					if (coast(cls, size, x, z, true)) col = shade(col, 0.72f);
				}
				img.setPixel(x, z, col);
			}
		}
		terrain.upload();
	}

	private static boolean coast(byte[] cls, int size, int x, int z, boolean land) {
		for (int d = 0; d < 4; d++) {
			int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
			if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
			boolean w = Terrain.water(cls[nz * size + nx] & 15);
			if (land == w) return true;
		}
		return false;
	}

	/** Rebuilt when ownership changed, while borders slide, and a few times a second while sieges pulse. */
	public static void updatePolitics(Mode mode, int selected) {
		int n = ClientRealm.n, size = n * PX;
		if (politics == null || politics.getPixels() == null || politics.getPixels().getWidth() != size) {
			if (politics != null) politics.close();
			politics = new DynamicTexture(() -> "kingdoms politics", size, size, false);
			Minecraft.getInstance().getTextureManager().register(POLITICS, politics);
			politicsBuilt = -1;
		}
		long now = System.currentTimeMillis();
		boolean animating = now - ClientRealm.lastChange < ClientRealm.BORDER_ANIM_MS + 100;
		boolean sieges = false;
		for (byte s : ClientRealm.siege) if (s != 0) {
			sieges = true;
			break;
		}
		boolean stale = politicsBuilt != ClientRealm.version || politicsMode != mode || politicsSelected != selected;
		if (!stale && !((animating || sieges) && now - politicsAt > (animating ? 60 : 140))) return;
		politicsBuilt = ClientRealm.version;
		politicsMode = mode;
		politicsSelected = selected;
		politicsAt = now;
		draw(politics.getPixels(), n, now, mode, selected);
		politics.upload();
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}

	private static void draw(NativeImage img, int n, long now, Mode mode, int selected) {
		short[] owner = ClientRealm.owner, prev = ClientRealm.prevOwner, claim = ClientRealm.claim, siegeBy = ClientRealm.siegeBy;
		byte[] siege = ClientRealm.siege;
		long[] changed = ClientRealm.changed;
		float[] t = new float[n * n];
		for (int i = 0; i < t.length; i++) {
			long dt = now - changed[i];
			t[i] = changed[i] == 0 || dt >= ClientRealm.BORDER_ANIM_MS ? 1f : smooth(Math.max(0, dt) / (float) ClientRealm.BORDER_ANIM_MS);
		}
		int[] colorOf = new int[65536];
		for (ClientRealm.KView k : ClientRealm.kingdoms.values()) colorOf[k.id & 0xFFFF] = kingdomColor(k, mode, selected);
		int size = n * PX;
		int[] ids = new int[8];
		float[] ps = new float[8];
		int stripe = (int) (now / 90);
		for (int py = 0; py < size; py++) {
			float v = (py + 0.5f) / PX - 0.5f;
			int z0 = (int) Math.floor(v);
			float fz = smooth(v - z0);
			for (int px = 0; px < size; px++) {
				float u = (px + 0.5f) / PX - 0.5f;
				int x0 = (int) Math.floor(u);
				float fx = smooth(u - x0);
				int count = 0;
				for (int c = 0; c < 4; c++) {
					int cx = Math.max(0, Math.min(n - 1, x0 + (c & 1))), cz = Math.max(0, Math.min(n - 1, z0 + (c >> 1)));
					float w = ((c & 1) == 1 ? fx : 1 - fx) * ((c >> 1) == 1 ? fz : 1 - fz);
					int i = cz * n + cx;
					float tt = t[i];
					count = add(ids, ps, count, owner[i], w * tt);
					if (tt < 1f) count = add(ids, ps, count, prev[i], w * (1 - tt));
				}
				int k1 = 0, k2 = 0;
				float p1 = -1, p2 = -1;
				for (int j = 0; j < count; j++) {
					if (ps[j] > p1) {
						p2 = p1;
						k2 = k1;
						p1 = ps[j];
						k1 = ids[j];
					} else if (ps[j] > p2) {
						p2 = ps[j];
						k2 = ids[j];
					}
				}
				if (count == 1) p2 = 0;
				int col = 0;
				if (k1 != 0 && colorOf[k1 & 0xFFFF] != 0) {
					int base = colorOf[k1 & 0xFFFF];
					float margin = p1 - Math.max(0, p2);
					if (margin < 0.16f) col = withAlpha(shade(base, 0.55f), 235);
					else if (margin < 0.30f) col = withAlpha(shade(base, 1.08f), 170);
					else if (margin < 0.55f) col = withAlpha(base, 120);
					else col = withAlpha(base, mode == Mode.TERRAIN ? 0 : 82);
					if (mode == Mode.TERRAIN && margin >= 0.16f) col = margin < 0.3f ? withAlpha(base, 90) : 0;
				} else if (k2 != 0 && p2 > 0.38f && colorOf[k2 & 0xFFFF] != 0) {
					col = withAlpha(shade(colorOf[k2 & 0xFFFF], 0.55f), 120); // the outer edge of a kingdom toward the wild
				}
				// disputed land and sieges, per cell
				int cx = Math.max(0, Math.min(n - 1, px / PX)), cz = Math.max(0, Math.min(n - 1, py / PX));
				int ci = cz * n + cx;
				if (mode != Mode.TERRAIN) {
					int cl = claim[ci];
					if (cl != 0 && cl != owner[ci] && colorOf[cl & 0xFFFF] != 0 && ((px + py) & 7) < 2) {
						col = withAlpha(shade(colorOf[cl & 0xFFFF], 0.9f), 190);
					}
				}
				int sg = siege[ci] & 0xFF;
				if (sg > 0 && siegeBy[ci] != 0) {
					int band = (px - py + stripe) & 7;
					if (band < 3) {
						int sc = colorOf[siegeBy[ci] & 0xFFFF];
						if (sc == 0) sc = 0xFFFF4040;
						col = mix(col == 0 ? withAlpha(sc, 0) : col, withAlpha(shade(sc, 1.15f), 230), 0.35f + sg / 160f);
					}
				}
				img.setPixel(px, py, col);
			}
		}
	}

	private static int add(int[] ids, float[] ps, int count, int id, float p) {
		if (p <= 0) return count;
		for (int j = 0; j < count; j++) {
			if (ids[j] == id) {
				ps[j] += p;
				return count;
			}
		}
		if (count >= ids.length) return count;
		ids[count] = id;
		ps[count] = p;
		return count + 1;
	}

	static int kingdomColor(ClientRealm.KView k, Mode mode, int selected) {
		if (mode != Mode.DIPLOMACY || selected == 0) return k.color();
		if (k.id == selected) return 0xFFF2C94C;
		ClientRealm.KView s = ClientRealm.kingdom(selected);
		if (s == null) return k.color();
		if (s.atWar(k.id)) return 0xFFE0382E;
		if (s.allied(k.id)) return 0xFF3FCF5A;
		float r = s.rel.getOrDefault(k.id, 0f);
		return r >= 0 ? mix(0xFFA0A0A0, 0xFF8FD08F, r / 100f) : mix(0xFFA0A0A0, 0xFFE08A6A, -r / 100f);
	}

	public static int shade(int c, float f) {
		int a = c >>> 24, r = Math.min(255, (int) (((c >> 16) & 255) * f)), g = Math.min(255, (int) (((c >> 8) & 255) * f)), b = Math.min(255, (int) ((c & 255) * f));
		return a << 24 | r << 16 | g << 8 | b;
	}

	public static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int aa = (int) ((a >>> 24) * (1 - t) + (b >>> 24) * t);
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return aa << 24 | r << 16 | g << 8 | bl;
	}

	public static int withAlpha(int c, int alpha) {
		return (alpha & 255) << 24 | (c & 0xFFFFFF);
	}

	private MapTextures() {}
}
