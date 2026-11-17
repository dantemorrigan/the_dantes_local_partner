package com.dantemorrigan.kingdoms.client;

import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.net.KNet;
import com.dantemorrigan.kingdoms.sim.BuildingType;
import com.dantemorrigan.kingdoms.sim.Chronicle;
import com.dantemorrigan.kingdoms.sim.Era;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Terrain;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The strategic map: the realm from above like in a strategy game. Terrain, living borders that slide as land
 * changes hands, disputed land and sieges, towns and castles, marching armies and battles, kingdom names across
 * their land. A kingdom list on the left, the selected kingdom (or town) on the right with the god's acts, the
 * god's tools at the bottom, the chronicle in the corner. Drag to pan, wheel to zoom.
 */
public final class MapScreen extends Screen {
	enum Tool {
		SELECT("select"), FOUND("found"), SMITE("smite"), METEOR("meteor"), GIFT("gift"), ARMY("raise_army"), OBSERVE("observe");
		final String icon;

		Tool(String icon) {
			this.icon = icon;
		}
	}

	private record Btn(int x, int y, int w, int h, Runnable action) {}

	private static final int BAR = 86;
	private static final int TEXT = 0xFFEDE6D2, MUTED = 0xFFA9A28F, GOLD = 0xFFF2C94C, RED = 0xFFE86A5A, GREEN = 0xFF7EDB7A;

	private static float camX = Float.NaN, camZ;
	private static float zoom = 6f;
	private static int selected;
	private static int selectedTown;
	private static int selectedArmy;
	/** A place picked from the chronicle: block x, z. */
	private static int[] pin;
	private static MapTextures.Mode mode = MapTextures.Mode.POLITICAL;
	private static Tool tool = Tool.SELECT;
	static boolean observing;

	private final List<Btn> buttons = new ArrayList<>();
	private String picking; // "war" / "peace" / "ally": waiting for a click on the other kingdom
	private boolean dragging, moved, draggingMini;
	private double pressX, pressY;
	private int listScroll;
	private long confirmDestroy;
	private int mapX1, mapY1, mapX2, mapY2;
	private boolean narrow;

	public MapScreen() {
		super(Component.translatable("kingdoms.map.title"));
	}

	/** Select a kingdom (and show it); for tests and links. */
	public static void select(int id) {
		selected = id;
		selectedTown = 0;
	}

	@Override
	protected void init() {
		send(new KNet.View(true, ClientRealm.needTerrain()));
		if (Float.isNaN(camX)) centerOnPlayer();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		send(new KNet.View(false, false));
		super.onClose();
	}

	static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload p) {
		if (ClientPlayNetworking.canSend(p.type())) ClientPlayNetworking.send(p);
	}

	static void god(String action, int a, int b, int x, int z) {
		send(new KNet.God(action, a, b, x, z));
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2f));
	}

	void centerOnPlayer() {
		var p = Minecraft.getInstance().player;
		if (p != null && ClientRealm.hasState) {
			camX = ClientRealm.cellX(p.getX());
			camZ = ClientRealm.cellZ(p.getZ());
		} else {
			camX = camZ = ClientRealm.n / 2f;
		}
	}

	// ------------------------------------------------------------------ layout

	private int leftW() {
		return narrow ? 0 : Math.min(104, width / 6);
	}

	private float sx(float cellX) {
		return (mapX1 + mapX2) / 2f + (cellX - camX) * zoom;
	}

	private float sy(float cellZ) {
		return (mapY1 + mapY2) / 2f + (cellZ - camZ) * zoom;
	}

	private float cx(double screenX) {
		return (float) (camX + (screenX - (mapX1 + mapX2) / 2f) / zoom);
	}

	private float cz(double screenY) {
		return (float) (camZ + (screenY - (mapY1 + mapY2) / 2f) / zoom);
	}

	private int blockX(float cellX) {
		return Math.round(ClientRealm.originX + cellX * Realm.CELL);
	}

	private int blockZ(float cellZ) {
		return Math.round(ClientRealm.originZ + cellZ * Realm.CELL);
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0xFF0D1220);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		buttons.clear();
		tip = null;
		narrow = width < 400;
		mapX1 = 0;
		mapX2 = width;
		mapY1 = 0;
		mapY2 = height - BAR;
		float minZoom = Math.max(1.2f, Math.min(mapX2 - mapX1, mapY2 - mapY1) / (float) ClientRealm.n);
		zoom = Math.max(minZoom, Math.min(48f, zoom));

		drawMap(g, mx, my);
		chronicle(g, mx, my);
		kingdomList(g, mx, my);
		resourceStrip(g);
		topRight(g, mx, my);
		commandBar(g, mx, my);
		hover(g, mx, my);
		if (tip != null) tooltipLines(g, tip, mx, my);
	}

	private void drawMap(GuiGraphicsExtractor g, int mx, int my) {
		g.fill(mapX1, mapY1, mapX2, mapY2, 0xFF1D3B66);
		if (!ClientRealm.hasState) {
			center(g, Component.translatable("kingdoms.map.loading"), (mapX1 + mapX2) / 2, (mapY1 + mapY2) / 2, TEXT);
			return;
		}
		g.enableScissor(mapX1, mapY1, mapX2, mapY2);
		int n = ClientRealm.n;
		float ox = sx(0), oy = sy(0);
		MapTextures.updateTerrain();
		if (MapTextures.terrainSize() > 0) {
			int ts = MapTextures.terrainSize();
			g.pose().pushMatrix();
			g.pose().translate(ox, oy);
			g.pose().scale(zoom * n / ts, zoom * n / ts);
			g.blit(RenderPipelines.GUI_TEXTURED, MapTextures.TERRAIN, 0, 0, 0f, 0f, ts, ts, ts, ts);
			g.pose().popMatrix();
		} else {
			center(g, Component.translatable("kingdoms.map.surveying"), (mapX1 + mapX2) / 2, mapY2 - 20, MUTED);
		}
		MapTextures.updatePolitics(mode, selected);
		int ps = n * MapTextures.PX;
		g.pose().pushMatrix();
		g.pose().translate(ox, oy);
		g.pose().scale(zoom / MapTextures.PX, zoom / MapTextures.PX);
		g.blit(RenderPipelines.GUI_TEXTURED, MapTextures.POLITICS, 0, 0, 0f, 0f, ps, ps, ps, ps);
		g.pose().popMatrix();

		// realm edge
		g.outline((int) ox - 1, (int) oy - 1, (int) (zoom * n) + 2, (int) (zoom * n) + 2, 0xAA000000);
		if (zoom >= 14) grid(g, n);
		if (mode == MapTextures.Mode.DIPLOMACY) diplomacyLines(g);
		labels(g);
		roads(g);
		walls(g);
		towns(g, mx, my);
		caravans(g);
		armies(g);
		player(g);
		if (picking != null) {
			center(g, Component.translatable("kingdoms.map.pick_" + picking), (mapX1 + mapX2) / 2, mapY1 + 6, GOLD);
		}
		g.disableScissor();
	}

	private void grid(GuiGraphicsExtractor g, int n) {
		int x1 = Math.max(0, (int) cx(mapX1)), x2 = Math.min(n, (int) cx(mapX2) + 1);
		int z1 = Math.max(0, (int) cz(mapY1)), z2 = Math.min(n, (int) cz(mapY2) + 1);
		for (int x = x1; x <= x2; x++) g.fill((int) sx(x), mapY1, (int) sx(x) + 1, mapY2, 0x18000000);
		for (int z = z1; z <= z2; z++) g.fill(mapX1, (int) sy(z), mapX2, (int) sy(z) + 1, 0x18000000);
	}

	private void labels(GuiGraphicsExtractor g) {
		Font font = this.font;
		for (ClientRealm.KView k : ClientRealm.kingdoms.values()) {
			if (!k.alive || k.labelSize < 2) continue;
			float extent = (float) Math.sqrt(k.labelSize) * zoom;
			Component name = k.shortName().copy().withStyle(s -> s.withBold(true));
			String upper = name.getString().toUpperCase(Locale.ROOT);
			int w = font.width(upper);
			float scale = Math.max(0.6f, Math.min(3.2f, extent * 0.9f / Math.max(1, w)));
			if (w * scale < 18) continue;
			float x = sx(k.lx), y = sy(k.lz) - (zoom > 10 ? 12 : 0);
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(scale, scale);
			int col = MapTextures.shade(k.color(), 0.45f);
			Component txt = Component.literal(upper).withStyle(s -> s.withBold(true));
			int tw = font.width(txt);
			for (int d = 0; d < 4; d++) g.text(font, txt, -tw / 2 + (d == 0 ? 1 : d == 1 ? -1 : 0), -4 + (d == 2 ? 1 : d == 3 ? -1 : 0), 0xCCF5EEDC, false);
			g.text(font, txt, -tw / 2, -4, 0xFF000000 | col, false);
			g.pose().popMatrix();
		}
	}

	private boolean waterCell(int cell) {
		if (ClientRealm.tSize == 0) return false;
		int n = ClientRealm.n, cx = cell % n, cz = cell / n;
		int s = Math.min(ClientRealm.tSize - 1, cx * 2 + 1), t = Math.min(ClientRealm.tSize - 1, cz * 2 + 1);
		return com.dantemorrigan.kingdoms.sim.Terrain.water(ClientRealm.tCls[t * ClientRealm.tSize + s] & 15);
	}

	/** Roads as lines between the cells they cross: width and colour by age, bridges brown, railways striped. */
	private void roads(GuiGraphicsExtractor g) {
		if (zoom < 2f) return;
		for (ClientRealm.RView ro : ClientRealm.roads) {
			int n = ClientRealm.n;
			int[] cells = ro.cells();
			int upto = (int) Math.floor(ro.progress() * (cells.length - 1));
			int lv = ro.level();
			int base = switch (lv) {
				case 0 -> 0xCC9C7A4A;
				case 1 -> 0xDDD9C48A;
				case 2 -> 0xEEE6E0D0;
				case 3 -> 0xFFFFFFFF;
				default -> 0xFF202020;
			};
			int thick = lv >= 3 ? 2 : 1;
			for (int i = 0; i < Math.min(upto, cells.length - 1); i++) {
				float x1 = sx(cells[i] % n + 0.5f), y1 = sy(cells[i] / n + 0.5f), x2 = sx(cells[i + 1] % n + 0.5f), y2 = sy(cells[i + 1] / n + 0.5f);
				if (Math.max(x1, x2) < mapX1 || Math.min(x1, x2) > mapX2 || Math.max(y1, y2) < mapY1 || Math.min(y1, y2) > mapY2) continue;
				boolean bridge = waterCell(cells[i]) || waterCell(cells[i + 1]);
				int steps = (int) Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
				for (int s = 0; s <= steps; s++) {
					float t = steps == 0 ? 0 : s / (float) steps;
					int px = (int) (x1 + (x2 - x1) * t), py = (int) (y1 + (y2 - y1) * t);
					int col = bridge ? 0xFFB07A3A : base;
					if (lv == 4 && (s / 3) % 2 == 0) col = bridge ? 0xFF8A5A2A : 0xFFEFEFEF;
					g.fill(px, py, px + thick + (bridge ? 1 : 0), py + thick + (bridge ? 1 : 0), col);
				}
			}
		}
	}

	/** A town wall: a square ring of the kingdom's dark colour, with a dot for each tower. */
	private void walls(GuiGraphicsExtractor g) {
		if (zoom < 3.5f) return;
		float half = com.dantemorrigan.kingdoms.sim.Settlement.WALL_R / (float) Realm.CELL;
		for (ClientRealm.TView t : ClientRealm.towns) {
			if (t.wall() == 0 || t.wallp() <= 0) continue;
			float cxs = ClientRealm.cellX(t.x()), czs = ClientRealm.cellZ(t.z());
			int x1 = (int) sx(cxs - half), y1 = (int) sy(czs - half), x2 = (int) sx(cxs + half), y2 = (int) sy(czs + half);
			if (x2 < mapX1 || x1 > mapX2 || y2 < mapY1 || y1 > mapY2) continue;
			ClientRealm.KView k = ClientRealm.kingdom(t.kingdom());
			int col = k == null ? 0xFF404040 : MapTextures.shade(0xFF000000 | k.color(), 0.45f);
			float prog = Math.min(1f, t.wallp());
			// the finished part of the ring, clockwise from the north-west corner
			int per = (x2 - x1) * 4, done = (int) (per * prog);
			for (int s = 0; s < done; s += 2) {
				int side = s / (x2 - x1), pos = s % (x2 - x1);
				int px = side == 0 ? x1 + pos : side == 1 ? x2 : side == 2 ? x2 - pos : x1;
				int py = side == 0 ? y1 : side == 1 ? y1 + pos : side == 2 ? y2 : y2 - pos;
				g.fill(px, py, px + 2, py + 2, col);
			}
			if (prog >= 1f) {
				int[][] spots = {{x1, y1}, {(x1 + x2) / 2, y1}, {x2, y1}, {x2, (y1 + y2) / 2}, {x2, y2}, {(x1 + x2) / 2, y2}, {x1, y2}, {x1, (y1 + y2) / 2}};
				for (int[] sp : spots) g.fill(sp[0] - 2, sp[1] - 2, sp[0] + 3, sp[1] + 3, 0xFFF2E6C0);
			}
		}
	}

	private void towns(GuiGraphicsExtractor g, int mx, int my) {
		for (ClientRealm.TView t : ClientRealm.towns) {
			float x = sx(ClientRealm.cellX(t.x())), y = sy(ClientRealm.cellZ(t.z()));
			if (x < mapX1 - 20 || x > mapX2 + 20 || y < mapY1 - 20 || y > mapY2 + 20) continue;
			ClientRealm.KView k = ClientRealm.kingdom(t.kingdom());
			float size = Math.max(9, Math.min(22, zoom * (t.capital() ? 1.7f : 1.3f)));
			String icon = t.ruin() || k == null ? "ruin" : t.capital() ? "castle" : "town";
			if (k != null) {
				int r = (int) (size * 0.62f);
				g.fill((int) x - r, (int) y - r / 3, (int) x + r, (int) y + r, MapTextures.withAlpha(k.color(), 120));
			}
			icon(g, icon, x - size / 2, y - size / 2 - 2, size);
			if (selectedTown == t.id()) g.outline((int) (x - size / 2) - 2, (int) (y - size / 2) - 4, (int) size + 4, (int) size + 4, GOLD);
			if (zoom >= 4.5f || t.capital()) {
				Component name = t.title();
				float sc = zoom >= 10 ? 1f : 0.75f;
				g.pose().pushMatrix();
				g.pose().translate(x, y + size / 2 + 1);
				g.pose().scale(sc, sc);
				int w = font.width(name);
				g.fill(-w / 2 - 2, -1, w / 2 + 2, 9, 0x99000000);
				g.text(font, name, -w / 2, 0, t.capital() ? GOLD : TEXT, false);
				g.pose().popMatrix();
			}
		}
	}

	private void caravans(GuiGraphicsExtractor g) {
		long now = System.currentTimeMillis();
		long dayMs = ClientRealm.dayMs();
		for (ClientRealm.CView c : ClientRealm.caravans.values()) {
			ClientRealm.KView k = ClientRealm.kingdom(c.kingdom);
			float x = sx(c.rx(now, dayMs)), y = sy(c.rz(now, dayMs));
			if (x < mapX1 - 12 || x > mapX2 + 12 || y < mapY1 - 12 || y > mapY2 + 12) continue;
			float size = Math.max(8, Math.min(15, zoom * 1.0f));
			if (k != null) g.fill((int) (x - size / 2), (int) (y + size / 2 - 2), (int) (x + size / 2), (int) (y + size / 2), 0xFF000000 | k.color());
			icon(g, "caravan", x - size / 2, y - size / 2, size);
		}
	}

	private void armies(GuiGraphicsExtractor g) {
		long now = System.currentTimeMillis();
		long dayMs = ClientRealm.dayMs();
		for (ClientRealm.AView a : ClientRealm.armies.values()) {
			ClientRealm.KView k = ClientRealm.kingdom(a.kingdom);
			if (k == null) continue;
			float x = sx(a.rx(now, dayMs)), y = sy(a.rz(now, dayMs));
			if (a.mission == 0 && (Math.abs(a.tx - a.x) > 0.3f || Math.abs(a.tz - a.z) > 0.3f) && zoom >= 2.5f) {
				dotted(g, x, y, sx(a.tx), sy(a.tz), MapTextures.withAlpha(k.color(), 0xDD), true);
				int ex = (int) sx(a.tx), ey = (int) sy(a.tz);
				g.outline(ex - 3, ey - 3, 7, 7, 0xFF000000 | k.color());
			}
			float size = Math.max(10, Math.min(20, zoom * 1.3f));
			g.fill((int) (x - size / 2), (int) (y + size / 2 - 3), (int) (x + size / 2), (int) (y + size / 2), 0x88000000);
			g.fill((int) (x - size * 0.35f), (int) (y - size * 0.45f), (int) (x + size * 0.45f), (int) (y + size * 0.05f), 0xFF000000 | k.color());
			icon(g, a.mission == 1 ? "patrol" : a.mission == 2 ? "soldier" : "army", x - size / 2, y - size / 2, size);
			String s = String.valueOf(Math.round(a.soldiers));
			g.text(font, s, (int) (x + size / 2 - 1), (int) (y + size / 2 - 7), TEXT, true);
			if (a.fighting > 0) {
				float pulse = 0.8f + 0.25f * (float) Math.sin(now / 120.0);
				float bs = size * pulse;
				icon(g, "battle", x - bs / 2 + size * 0.5f, y - bs / 2 - size * 0.5f, bs);
			}
		}
	}

	private void diplomacyLines(GuiGraphicsExtractor g) {
		for (ClientRealm.KView a : ClientRealm.kingdoms.values()) {
			if (!a.alive) continue;
			ClientRealm.TView ca = capital(a);
			if (ca == null) continue;
			for (ClientRealm.KView b : ClientRealm.kingdoms.values()) {
				if (b.id <= a.id || !b.alive) continue;
				ClientRealm.TView cb = capital(b);
				if (cb == null) continue;
				int col = a.atWar(b.id) ? 0xDDE0382E : a.allied(b.id) ? 0xDD3FCF5A : 0;
				if (col == 0) continue;
				dotted(g, sx(ClientRealm.cellX(ca.x())), sy(ClientRealm.cellZ(ca.z())), sx(ClientRealm.cellX(cb.x())), sy(ClientRealm.cellZ(cb.z())), col, a.atWar(b.id));
			}
		}
	}

	private void dotted(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int col, boolean dashed) {
		float len = (float) Math.hypot(x2 - x1, y2 - y1);
		int steps = (int) (len / 2);
		long phase = System.currentTimeMillis() / 60;
		for (int i = 0; i < steps; i++) {
			if (dashed && ((i + phase) / 3) % 2 == 0) continue;
			float t = i / (float) steps;
			int x = (int) (x1 + (x2 - x1) * t), y = (int) (y1 + (y2 - y1) * t);
			g.fill(x, y, x + 2, y + 2, col);
		}
	}

	private void player(GuiGraphicsExtractor g) {
		var p = Minecraft.getInstance().player;
		if (p == null) return;
		float x = sx(ClientRealm.cellX(p.getX())), y = sy(ClientRealm.cellZ(p.getZ()));
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().rotate((float) Math.toRadians(p.getYRot() + 180));
		icon(g, "player", -6, -6, 12);
		g.pose().popMatrix();
	}

	static ClientRealm.TView capital(ClientRealm.KView k) {
		for (ClientRealm.TView t : ClientRealm.towns) if (t.id() == k.capital && t.kingdom() == k.id) return t;
		for (ClientRealm.TView t : ClientRealm.towns) if (t.kingdom() == k.id) return t;
		return null;
	}

	// ------------------------------------------------------------------ chrome (command-bar layout)

	private static int topRightWidth() {
		return 18 * 4 + 6 + 62 + 10;
	}

	private void topRight(GuiGraphicsExtractor g, int mx, int my) {
		int w = topRightWidth();
		int x0 = width - w - 3;
		frame(g, "panel", x0, 2, w, 22);
		int year = (int) (ClientRealm.day / Realm.DAYS_PER_YEAR) + 1;
		int season = (int) (ClientRealm.day % Realm.DAYS_PER_YEAR) * 4 / Realm.DAYS_PER_YEAR;
		Component date = Component.translatable("kingdoms.map.date", year, Component.translatable("kingdoms.season." + season));
		Ui2.fit(g, font, date, x0 + 6, 9, 60, GOLD, 0.9f);
		int x = x0 + 70;
		MapTextures.Mode[] modes = MapTextures.Mode.values();
		String[] layerIcons = {"layer_political", "layer_terrain", "layer_diplomacy"};
		for (int i = 0; i < modes.length; i++) {
			MapTextures.Mode m = modes[i];
			button(g, x, 4, 18, 18, layerIcons[i], null, mode == m, mx, my, () -> mode = m);
			if (Ui2.inside(mx, my, x, 4, 18, 18)) tooltip(g, Component.translatable("kingdoms.map.layer." + m.name().toLowerCase(Locale.ROOT)), mx, my);
			x += 18;
		}
		x += 6;
		button(g, x, 4, 18, 18, null, Component.literal("✕"), false, mx, my, this::onClose);
	}

	/** The top-left strip: the treasury and stores of the selected kingdom, like a resource bar in a strategy game. */
	private void resourceStrip(GuiGraphicsExtractor g) {
		ClientRealm.KView k = ClientRealm.kingdom(selected);
		int w = width - topRightWidth() - 12;
		if (w < 90) return;
		w = Math.min(w, 7 * 52 + 10);
		frame(g, "panel", 3, 2, w, 22);
		if (k == null) {
			icon(g, "crown", 8, 5, 16);
			Ui2.fit(g, font, Component.translatable("kingdoms.map.pick_hint"), 28, 9, w - 30, MUTED, 0.85f);
			return;
		}
		String[] ic = {"gold", "food", "wood", "stone", "iron", "people", "battle"};
		float[] v = {k.gold, k.food, k.wood, k.stone, k.iron, k.pop, k.might};
		int items = Math.max(2, Math.min(7, (w - 8) / 38));
		int per = (w - 8) / items;
		for (int i = 0; i < items; i++) {
			int x = 8 + i * per;
			icon(g, ic[i], x, 5, 14);
			Ui2.fit(g, font, Component.literal(String.valueOf((int) v[i])), x + 16, 9, per - 17, TEXT, 0.9f);
		}
	}

	private void kingdomList(GuiGraphicsExtractor g, int mx, int my) {
		int w = leftW();
		if (w == 0) return;
		List<ClientRealm.KView> alive = new ArrayList<>();
		for (ClientRealm.KView k : ClientRealm.kingdoms.values()) if (k.alive) alive.add(k);
		alive.sort((a, b) -> Float.compare(b.cells, a.cells));
		float maxCells = 1;
		for (ClientRealm.KView k : alive) maxCells = Math.max(maxCells, k.cells);
		int rowH = 20, y0 = 28;
		int visible = Math.max(1, Math.min(alive.size(), (mapY2 - y0 - 14) / rowH));
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, alive.size() - visible)));
		int h = Math.max(1, visible) * rowH + 6;
		g.fill(3, y0 - 2, w, y0 - 2 + h + (alive.isEmpty() ? 30 : 0), 0xAA0B1020);
		g.outline(3, y0 - 2, w - 3, h + (alive.isEmpty() ? 30 : 0), 0x88B08A3E);
		for (int i = listScroll; i < alive.size() && i - listScroll < visible; i++) {
			ClientRealm.KView k = alive.get(i);
			int y = y0 + (i - listScroll) * rowH;
			boolean hover = Ui2.inside(mx, my, 4, y, w - 5, rowH - 1);
			if (k.id == selected) g.fill(4, y, w - 1, y + rowH - 1, 0x66F2C94C);
			else if (hover) g.fill(4, y, w - 1, y + rowH - 1, 0x33FFFFFF);
			g.fill(6, y + 2, 11, y + rowH - 4, 0xFF000000 | k.color());
			Ui2.fit(g, font, k.shortName(), 14, y + 2, w - 34, TEXT, 0.9f);
			if (k.wars.length > 0) icon(g, "battle", w - 15, y + 1, 10);
			Ui2.fit(g, font, Component.translatable(Era.of(k.era).key()), 14, y + 11, w - 20, MUTED, 0.65f);
			int bw = (int) ((w - 18) * (k.cells / maxCells));
			g.fill(14, y + rowH - 3, 14 + bw, y + rowH - 2, 0xFF000000 | k.color());
			buttons.add(new Btn(4, y, w - 5, rowH - 1, () -> clickKingdom(k.id)));
		}
		if (alive.isEmpty()) Ui2.wrap(g, font, Component.translatable("kingdoms.map.empty"), 8, y0 + 2, w - 14, MUTED, 0.75f);
	}

	private void clickKingdom(int id) {
		if (picking != null && selected != 0 && id != selected) {
			god(picking, selected, id, 0, 0);
			picking = null;
			return;
		}
		if (selected == id) {
			ClientRealm.KView k = ClientRealm.kingdom(id);
			ClientRealm.TView c = k == null ? null : capital(k);
			if (c != null) {
				camX = ClientRealm.cellX(c.x());
				camZ = ClientRealm.cellZ(c.z());
			}
		}
		selected = id;
		selectedTown = 0;
		selectedArmy = 0;
		pin = null;
	}

	private void commandBar(GuiGraphicsExtractor g, int mx, int my) {
		int y0 = mapY2;
		buttons.add(new Btn(0, y0, width, BAR, () -> {})); // the bar swallows clicks (registered first, so its own buttons win)
		frame(g, "bar", 0, y0, width, BAR);
		// --- minimap
		int ms = BAR - 14, mxp = 7, myp = y0 + 7;
		frame(g, "slot", mxp - 3, myp - 3, ms + 6, ms + 6);
		minimap(g, mxp, myp, ms, mx, my);
		// --- command grid
		int cols = 6, bs = 22, gap = 2, gw = cols * (bs + gap) - gap, gx = width - gw - 9, gy = y0 + 8;
		frame(g, "slot", gx - 4, gy - 4, gw + 8, 3 * (bs + gap) - gap + 8);
		commands(g, gx, gy, bs, gap, cols, mx, my);
		// --- information panel
		int ix = mxp + ms + 10, iw = gx - 8 - ix;
		if (iw > 80) {
			frame(g, "slot", ix, y0 + 5, iw, BAR - 10);
			infoPanel(g, ix + 5, y0 + 9, iw - 10, BAR - 18, mx, my);
		}
	}

	private void minimap(GuiGraphicsExtractor g, int x, int y, int size, int mx, int my) {
		g.fill(x, y, x + size, y + size, 0xFF1D3B66);
		int n = ClientRealm.n;
		if (ClientRealm.hasState) {
			int ts = MapTextures.terrainSize();
			if (ts > 0) {
				g.pose().pushMatrix();
				g.pose().translate(x, y);
				g.pose().scale(size / (float) ts, size / (float) ts);
				g.blit(RenderPipelines.GUI_TEXTURED, MapTextures.TERRAIN, 0, 0, 0f, 0f, ts, ts, ts, ts);
				g.pose().popMatrix();
			}
			int ps = n * MapTextures.PX;
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(size / (float) ps, size / (float) ps);
			g.blit(RenderPipelines.GUI_TEXTURED, MapTextures.POLITICS, 0, 0, 0f, 0f, ps, ps, ps, ps);
			g.pose().popMatrix();
			for (ClientRealm.TView t : ClientRealm.towns) {
				int tx = x + (int) (ClientRealm.cellX(t.x()) / n * size), tz = y + (int) (ClientRealm.cellZ(t.z()) / n * size);
				g.fill(tx - 1, tz - 1, tx + 1, tz + 1, t.capital() ? GOLD : 0xFFFFFFFF);
			}
			long now = System.currentTimeMillis();
			for (ClientRealm.AView a : ClientRealm.armies.values()) {
				ClientRealm.KView k = ClientRealm.kingdom(a.kingdom);
				int ax = x + (int) (a.rx(now, ClientRealm.dayMs()) / n * size), az = y + (int) (a.rz(now, ClientRealm.dayMs()) / n * size);
				g.fill(ax - 1, az - 1, ax + 2, az + 2, a.fighting > 0 ? 0xFFFF3030 : k == null ? 0xFFFFFFFF : k.color());
			}
			// the visible part of the map
			float vx1 = Math.max(0, cx(mapX1)), vx2 = Math.min(n, cx(mapX2)), vz1 = Math.max(0, cz(mapY1)), vz2 = Math.min(n, cz(mapY2));
			g.outline(x + (int) (vx1 / n * size), y + (int) (vz1 / n * size), Math.max(2, (int) ((vx2 - vx1) / n * size)), Math.max(2, (int) ((vz2 - vz1) / n * size)), 0xFFFFFFFF);
		}
		g.outline(x - 1, y - 1, size + 2, size + 2, 0xFF000000);
		buttons.add(new Btn(x, y, size, size, () -> {}));
		minimapRect = new int[]{x, y, size};
	}

	private int[] minimapRect = {0, 0, 1};

	private void minimapJump(double mx, double my) {
		int[] m = minimapRect;
		camX = (float) ((mx - m[0]) / m[2] * ClientRealm.n);
		camZ = (float) ((my - m[1]) / m[2] * ClientRealm.n);
		clampCam();
	}

	private void commands(GuiGraphicsExtractor g, int x0, int y0, int bs, int gap, int cols, int mx, int my) {
		boolean anyArmy = !ClientRealm.armies.isEmpty();
		boolean god = ClientRealm.god, kingdom = selected != 0 && ClientRealm.kingdom(selected) != null && ClientRealm.kingdom(selected).alive;
		record Cmd(String id, String icon, boolean needsGod, boolean needsKingdom, boolean danger, Runnable run, boolean active) {}
		int sel = selected;
		List<Cmd> cmds = new ArrayList<>();
		cmds.add(new Cmd("select", "select", false, false, false, () -> tool = Tool.SELECT, tool == Tool.SELECT));
		cmds.add(new Cmd("battle", "battle", false, false, false, this::goToBattle, false));
		cmds.add(new Cmd("observe", "observe", false, false, false, () -> tool = tool == Tool.OBSERVE ? Tool.SELECT : Tool.OBSERVE, tool == Tool.OBSERVE));
		cmds.add(new Cmd("found", "found", true, false, false, () -> tool = tool == Tool.FOUND ? Tool.SELECT : Tool.FOUND, tool == Tool.FOUND));
		cmds.add(new Cmd("smite", "smite", true, false, false, () -> tool = tool == Tool.SMITE ? Tool.SELECT : Tool.SMITE, tool == Tool.SMITE));
		cmds.add(new Cmd("meteor", "meteor", true, false, false, () -> tool = tool == Tool.METEOR ? Tool.SELECT : Tool.METEOR, tool == Tool.METEOR));
		cmds.add(new Cmd("gift", "gift", true, true, false, () -> tool = tool == Tool.GIFT ? Tool.SELECT : Tool.GIFT, tool == Tool.GIFT));
		cmds.add(new Cmd("army", "raise_army", true, true, false, () -> tool = tool == Tool.ARMY ? Tool.SELECT : Tool.ARMY, tool == Tool.ARMY));
		cmds.add(new Cmd("bless", "bless", true, true, false, () -> god("bless", sel, 0, 0, 0), false));
		cmds.add(new Cmd("plague", "plague", true, true, false, () -> god("plague", sel, 0, 0, 0), false));
		cmds.add(new Cmd("war", "war", true, true, false, () -> picking = "war".equals(picking) ? null : "war", "war".equals(picking)));
		cmds.add(new Cmd("peace", "peace", true, true, false, () -> picking = "peace".equals(picking) ? null : "peace", "peace".equals(picking)));
		cmds.add(new Cmd("ally", "ally", true, true, false, () -> picking = "ally".equals(picking) ? null : "ally", "ally".equals(picking)));
		cmds.add(new Cmd("era", "era", true, true, false, () -> god("era", sel, 0, 0, 0), false));
		cmds.add(new Cmd("rebel", "rebel", true, true, false, () -> god("rebel", sel, 0, 0, 0), false));
		cmds.add(new Cmd("destroy", "destroy", true, true, true, () -> {
			if (System.currentTimeMillis() - confirmDestroy < 3000) {
				god("destroy", sel, 0, 0, 0);
				confirmDestroy = 0;
				selected = 0;
			} else confirmDestroy = System.currentTimeMillis();
		}, System.currentTimeMillis() - confirmDestroy < 3000));
		for (int i = 0; i < cmds.size(); i++) {
			Cmd c = cmds.get(i);
			int x = x0 + (i % cols) * (bs + gap), y = y0 + (i / cols) * (bs + gap);
			boolean enabled = (!c.needsGod || god) && (!c.needsKingdom || kingdom) && (!c.id.equals("battle") || anyArmy);
			boolean hover = Ui2.inside(mx, my, x, y, bs, bs);
			frame(g, c.active ? "button_active" : c.danger && enabled ? "button_danger" : hover && enabled ? "button_hover" : "button", x, y, bs, bs);
			icon(g, c.icon, x + 3, y + 3, bs - 6);
			if (!enabled) g.fill(x + 1, y + 1, x + bs - 1, y + bs - 1, 0xAA05070C);
			if (enabled) buttons.add(new Btn(x, y, bs, bs, c.run));
			if (hover) {
				boolean confirm = c.id.equals("destroy") && c.active;
				List<Component> lines = new ArrayList<>();
				lines.add(Component.translatable("kingdoms.cmd." + c.id).withColor(GOLD));
				lines.add(Component.translatable(confirm ? "kingdoms.god.destroy_confirm" : "kingdoms.cmd." + c.id + ".tip").withColor(confirm ? RED : MUTED));
				if (!enabled) lines.add(Component.translatable(c.id.equals("battle") ? "kingdoms.cmd.no_army" : c.needsGod && !god ? "kingdoms.god.denied" : "kingdoms.cmd.need_kingdom").withColor(RED));
				tip = lines;
			}
		}
		if (observing) {
			int x = x0, y = y0 + 3 * (bs + gap) + 2;
			button(g, x - 90, y0 + 3 * (bs + gap) - bs - 2, 86, bs, "observe", Component.translatable("kingdoms.god.return"), false, mx, my, () -> {
				god("return", 0, 0, 0, 0);
				observing = false;
			});
		}
	}

	private List<Component> tip;

	/** To the fiercest fight of the realm (or, failing that, the biggest army): you arrive among the soldiers. */
	private void goToBattle() {
		ClientRealm.AView best = null;
		for (ClientRealm.AView a : ClientRealm.armies.values()) {
			boolean fa = a.fighting > 0, fb = best != null && best.fighting > 0;
			if (best == null || (fa && !fb) || (fa == fb && a.soldiers > best.soldiers)) best = a;
		}
		if (best == null) return;
		teleportTo(blockX(best.x), blockZ(best.z));
	}

	private void teleportTo(int x, int z) {
		god("observe", 0, 0, x, z);
		observing = true;
		tool = Tool.SELECT;
		onClose();
	}

	private void armyInfo(GuiGraphicsExtractor g, ClientRealm.AView a, int x, int y, int w, int h) {
		ClientRealm.KView k = ClientRealm.kingdom(a.kingdom);
		frame(g, "slot", x, y, 46, 46);
		if (k != null) g.fill(x + 3, y + 3, x + 43, y + 43, MapTextures.withAlpha(k.color(), 150));
		icon(g, a.mission == 1 ? "patrol" : a.mission == 2 ? "soldier" : "army", x + 5, y + 5, 36);
		Ui2.fit(g, font, Component.translatable("kingdoms.map.army." + a.mission), x + 52, y, w - 160, TEXT);
		if (k != null) Ui2.fit(g, font, k.title().copy().withColor(k.color()), x + 52, y + 10, w - 160, MUTED, 0.85f);
		Ui2.fit(g, font, Component.translatable("kingdoms.map.army_size", Math.round(a.soldiers)), x + 52, y + 21, w - 160, TEXT, 0.85f);
		ClientRealm.KView enemy = ClientRealm.kingdom(a.target);
		Component status = a.fighting > 0 ? Component.translatable("kingdoms.map.in_battle").withColor(RED)
				: a.mission == 0 && enemy != null ? Component.translatable("kingdoms.map.marching_on", enemy.shortName()).withColor(GOLD)
				: Component.translatable("kingdoms.map.army_idle").withColor(MUTED);
		Ui2.fit(g, font, status, x + 52, y + 32, w - 160, TEXT, 0.85f);
		int bw = 140;
		button(g, x + w - bw - 2, y + 4, bw, 18, "observe", Component.translatable("kingdoms.map.tp_army"), false, mx(), my(), () -> teleportTo(blockX(a.x), blockZ(a.z)));
	}

	private void pinInfo(GuiGraphicsExtractor g, int[] p, int x, int y, int w, int h) {
		icon(g, "observe", x, y, 32);
		Ui2.fit(g, font, Component.translatable("kingdoms.map.place"), x + 38, y, w - 190, GOLD);
		Ui2.fit(g, font, Component.literal("X " + p[0] + "  Z " + p[1]), x + 38, y + 11, w - 190, MUTED, 0.9f);
		int bw = 140;
		button(g, x + w - bw - 2, y + 4, bw, 18, "observe", Component.translatable("kingdoms.map.tp_here"), false, mx(), my(), () -> teleportTo(p[0], p[1]));
	}

	private int mx() {
		return (int) Minecraft.getInstance().mouseHandler.getScaledXPos(Minecraft.getInstance().getWindow());
	}

	private int my() {
		return (int) Minecraft.getInstance().mouseHandler.getScaledYPos(Minecraft.getInstance().getWindow());
	}

	private void infoPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
		ClientRealm.TView town = null;
		for (ClientRealm.TView t : ClientRealm.towns) if (t.id() == selectedTown) town = t;
		ClientRealm.KView k = ClientRealm.kingdom(selected);
		ClientRealm.AView army = ClientRealm.armies.get(selectedArmy);
		if (army != null) {
			armyInfo(g, army, x, y, w, h);
			return;
		}
		if (pin != null && k == null && town == null) {
			pinInfo(g, pin, x, y, w, h);
			return;
		}
		if (town != null) {
			townInfo(g, town, x, y, w, h);
			return;
		}
		if (k == null) {
			realmInfo(g, x, y, w, h);
			return;
		}
		// portrait
		int ps = 40;
		frame(g, "slot", x, y, ps + 6, ps + 6);
		g.fill(x + 3, y + 3, x + ps + 3, y + ps + 3, MapTextures.withAlpha(k.color(), 150));
		icon(g, k.alive ? "crown" : "castle_ruin", x + 5, y + 5, ps - 4);
		boolean wide = w >= 380;
		int c1 = x + ps + 12, c1w = wide ? Math.min(230, w / 2) : x + w - c1;
		Ui2.fit(g, font, k.title(), c1, y, c1w, TEXT);
		Ui2.fit(g, font, k.alive ? k.kingTitle() : Component.translatable("kingdoms.map.fallen"), c1, y + 10, c1w, k.alive ? GOLD : RED, 0.8f);
		Ui2.fit(g, font, Component.translatable(Era.of(k.era).key()), c1, y + 19, c1w / 2, TEXT, 0.8f);
		float prog = k.knowNext <= k.knowFrom ? 1 : (k.know - k.knowFrom) / (k.knowNext - k.knowFrom);
		bar(g, c1 + c1w / 2 + 2, y + 21, c1w / 2 - 2, 4, prog, 0xFF5FA8F0);
		String[][] st = {{"people", String.valueOf((int) k.pop)}, {"soldier", (int) k.soldiers + (k.army >= 1 ? "+" + (int) k.army : "")},
				{"layer_political", String.valueOf(k.cells)}, {"battle", String.valueOf((int) k.might)}, {"knowledge", String.valueOf((int) k.know)},
				{"caravan", String.valueOf(ClientRealm.caravans.values().stream().filter(c -> c.kingdom == k.id).count())}};
		int per = c1w / st.length;
		for (int i = 0; i < st.length; i++) {
			int cx2 = c1 + i * per;
			icon(g, st[i][0], cx2, y + 31, 10);
			Ui2.fit(g, font, Component.literal(st[i][1]), cx2 + 12, y + 32, per - 13, TEXT, 0.85f);
		}
		icon(g, "stability", c1, y + 45, 9);
		bar(g, c1 + 12, y + 48, c1w - 12, 4, k.stability / 100f, k.stability > 50 ? GREEN : k.stability > 25 ? GOLD : RED);
		if (wide) {
			int c3 = c1 + c1w + 10, c3w = x + w - c3;
			int yy = Ui2.wrap(g, font, Component.translatable("kingdoms.map.temper", trait("agg", k.agg), trait("exp", k.exp), trait("dip", k.dip)), c3, y, c3w, MUTED, 0.75f) + 3;
			if (k.wars.length > 0) yy = Ui2.wrap(g, font, Component.translatable("kingdoms.map.at_war", names(k.wars)), c3, yy, c3w, RED, 0.75f) + 2;
			if (k.allies.length > 0) Ui2.wrap(g, font, Component.translatable("kingdoms.map.allies", names(k.allies)), c3, yy, c3w, GREEN, 0.75f);
		}
	}

	private MutableComponent names(int[] ids) {
		MutableComponent list = Component.empty();
		for (int id : ids) {
			ClientRealm.KView e = ClientRealm.kingdom(id);
			if (e == null) continue;
			if (!list.getSiblings().isEmpty()) list.append(", ");
			list.append(e.shortName().copy().withColor(e.color()));
		}
		return list;
	}

	private void realmInfo(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		int alive = 0, wars = 0, towns = ClientRealm.towns.size();
		float pop = 0, sold = 0;
		for (ClientRealm.KView kv : ClientRealm.kingdoms.values()) if (kv.alive) {
			alive++;
			pop += kv.pop;
			wars += kv.wars.length;
			sold += kv.soldiers + kv.army;
		}
		icon(g, "crown", x, y, 32);
		Ui2.fit(g, font, Component.translatable("kingdoms.map.realm"), x + 38, y, 120, GOLD);
		String[][] st = {{"crown", alive + ""}, {"town", towns + ""}, {"people", (int) pop + ""}, {"soldier", (int) sold + ""}, {"battle", wars / 2 + ""}, {"caravan", ClientRealm.caravans.size() + ""}};
		for (int i = 0; i < st.length; i++) {
			int cx2 = x + 38 + (i % 3) * 56, cy2 = y + 12 + (i / 3) * 13;
			icon(g, st[i][0], cx2, cy2, 10);
			Ui2.fit(g, font, Component.literal(st[i][1]), cx2 + 13, cy2 + 1, 40, TEXT, 0.9f);
		}
		int hx = x + 38 + 3 * 56 + 8;
		if (x + w - hx > 80) Ui2.wrap(g, font, Component.translatable(ClientRealm.god ? "kingdoms.map.hint_god" : "kingdoms.map.hint"), hx, y, x + w - hx, MUTED, 0.7f);
	}

	private void townInfo(GuiGraphicsExtractor g, ClientRealm.TView t, int x, int y, int w, int h) {
		ClientRealm.KView k = ClientRealm.kingdom(t.kingdom());
		frame(g, "slot", x, y, 46, 46);
		if (k != null) g.fill(x + 3, y + 3, x + 43, y + 43, MapTextures.withAlpha(k.color(), 150));
		icon(g, t.ruin() ? "ruin" : t.capital() ? "castle" : "town", x + 5, y + 5, 36);
		Ui2.fit(g, font, t.title(), x + 52, y, 150, TEXT);
		Ui2.fit(g, font, k == null ? Component.translatable("kingdoms.map.ruins") : k.title().copy().withColor(k.color()), x + 52, y + 10, 150, MUTED, 0.8f);
		if (t.capital()) Ui2.fit(g, font, Component.translatable("kingdoms.map.capital"), x + 52, y + 20, 150, GOLD, 0.8f);
		Ui2.fit(g, font, Component.translatable("kingdoms.map.buildings", t.done(), t.building()), x + 52, y + 30, 170, TEXT, 0.75f);
		BuildingType[] types = BuildingType.values();
		int col = 0, row = 0, cx2 = x + 230;
		for (int i = 0; i < t.types().length && i < types.length; i++) {
			if (t.types()[i] == 0) continue;
			int px = cx2 + col * 104, py = y + row * 9;
			if (px + 100 > x + w) break;
			Ui2.fit(g, font, Component.translatable(types[i].key()).append(" ×" + t.types()[i]), px, py, 100, TEXT, 0.7f);
			if (++row >= 5) {
				row = 0;
				col++;
			}
		}
		buttons.add(new Btn(x + w - 1, y, 0, 0, () -> {}));
	}

	private Component trait(String key, float v) {
		int level = v < 0.34f ? 0 : v < 0.67f ? 1 : 2;
		return Component.translatable("kingdoms.trait." + key + "." + level);
	}

	private void chronicle(GuiGraphicsExtractor g, int mx, int my) {
		if (ClientRealm.news.isEmpty()) return;
		int w = Math.min(200, (mapX2 - mapX1) / 3);
		int x = mapX2 - w - 4;
		int y = 28;
		int shown = 0;
		for (int i = ClientRealm.news.size() - 1; i >= 0 && shown < 5; i--) {
			Chronicle.Entry e = ClientRealm.news.get(i);
			if (e.level() == 0 && shown > 2) continue;
			List<FormattedCharSequence> lines = font.split(e.text(), (int) ((w - 8) / 0.72f));
			int h = (int) (lines.size() * 9 * 0.72f) + 4;
			if (y + h > mapY2 - 4) break;
			boolean hov = Ui2.inside(mx, my, x, y, w, h);
			g.fill(x, y, x + w, y + h, hov ? 0xCC2A2416 : 0x9A0B1020);
			g.fill(x, y, x + 2, y + h, e.level() >= 2 ? GOLD : e.level() == 1 ? 0xFF8FA8D8 : 0xFF666666);
			g.pose().pushMatrix();
			g.pose().translate(x + 5, y + 2);
			g.pose().scale(0.72f, 0.72f);
			for (int l = 0; l < lines.size(); l++) g.text(font, lines.get(l), 0, l * 9, TEXT, false);
			g.pose().popMatrix();
			if (e.x() != 0 || e.z() != 0) {
				int ex = e.x(), ez = e.z();
				buttons.add(new Btn(x, y, w, h, () -> {
					camX = ClientRealm.cellX(ex);
					camZ = ClientRealm.cellZ(ez);
					zoom = Math.max(zoom, 8);
					pin = new int[]{ex, ez};
					selected = 0;
					selectedTown = 0;
					selectedArmy = 0;
				}));
			}
			y += h + 1;
			shown++;
		}
	}

	private void hover(GuiGraphicsExtractor g, int mx, int my) {
		if (!ClientRealm.hasState || mx < mapX1 || mx >= mapX2 || my < mapY1 || my >= mapY2 || dragging) return;
		float fx = cx(mx), fz = cz(my);
		int cxi = (int) Math.floor(fx), czi = (int) Math.floor(fz);
		if (cxi < 0 || czi < 0 || cxi >= ClientRealm.n || czi >= ClientRealm.n) return;
		long nowMs = System.currentTimeMillis();
		for (ClientRealm.CView c : ClientRealm.caravans.values()) {
			float x = sx(c.rx(nowMs, ClientRealm.dayMs())), y = sy(c.rz(nowMs, ClientRealm.dayMs()));
			if (Math.abs(mx - x) < 8 && Math.abs(my - y) < 8) {
				ClientRealm.KView k = ClientRealm.kingdom(c.kingdom);
				int type = c.returning ? c.getType : c.giveType, amt = c.returning ? c.getAmt : c.giveAmt;
				List<Component> lines = new ArrayList<>();
				lines.add(Component.translatable("kingdoms.map.caravan").withColor(GOLD));
				if (k != null) lines.add(k.title().copy().withColor(k.color()));
				lines.add(Component.translatable("kingdoms.map.cargo", amt, Component.translatable("kingdoms.good." + com.dantemorrigan.kingdoms.sim.Caravan.GOODS[Math.floorMod(type, 5)])).withColor(MUTED));
				lines.add(Component.translatable(c.returning ? "kingdoms.map.caravan_home" : "kingdoms.map.caravan_out").withColor(MUTED));
				tooltipLines(g, lines, mx, my);
				return;
			}
		}
		for (ClientRealm.AView a : ClientRealm.armies.values()) {
			float x = sx(a.rx(nowMs, ClientRealm.dayMs())), y = sy(a.rz(nowMs, ClientRealm.dayMs()));
			if (Math.abs(mx - x) < 10 && Math.abs(my - y) < 10) {
				ClientRealm.KView k = ClientRealm.kingdom(a.kingdom);
				List<Component> lines = new ArrayList<>();
				lines.add(Component.translatable("kingdoms.map.army." + a.mission).withColor(GOLD));
				if (k != null) lines.add(k.title().copy().withColor(k.color()));
				lines.add(Component.translatable("kingdoms.map.army_size", Math.round(a.soldiers)).withColor(MUTED));
				if (a.fighting > 0) lines.add(Component.translatable("kingdoms.map.in_battle").withColor(RED));
				tooltipLines(g, lines, mx, my);
				return;
			}
		}
		// town under the cursor
		for (ClientRealm.TView t : ClientRealm.towns) {
			float x = sx(ClientRealm.cellX(t.x())), y = sy(ClientRealm.cellZ(t.z()));
			if (Math.abs(mx - x) < 9 && Math.abs(my - y) < 9) {
				ClientRealm.KView k = ClientRealm.kingdom(t.kingdom());
				List<Component> lines = new ArrayList<>();
				lines.add(t.title().copy().withColor(GOLD));
				lines.add(k == null ? Component.translatable("kingdoms.map.ruins") : k.title().copy().withColor(k.color()));
				lines.add(Component.translatable("kingdoms.map.buildings", t.done(), t.building()).withColor(MUTED));
				tooltipLines(g, lines, mx, my);
				return;
			}
		}
		List<Component> lines = new ArrayList<>();
		int o = ClientRealm.ownerAt(cxi, czi);
		ClientRealm.KView k = ClientRealm.kingdom(o);
		lines.add(k == null ? Component.translatable("kingdoms.map.wild") : k.title().copy().withColor(k.color()));
		if (ClientRealm.tSize > 0) {
			int s = Math.min(ClientRealm.tSize - 1, (int) (fx * 2)), t = Math.min(ClientRealm.tSize - 1, (int) (fz * 2));
			int cls = ClientRealm.tCls[Math.max(0, t) * ClientRealm.tSize + Math.max(0, s)] & 15;
			lines.add(Component.translatable("kingdoms.terrain." + cls).withColor(MUTED));
		}
		int i = czi * ClientRealm.n + cxi;
		int cl = ClientRealm.claim[i];
		if (cl != 0 && cl != o) {
			ClientRealm.KView c = ClientRealm.kingdom(cl);
			if (c != null) lines.add(Component.translatable("kingdoms.map.disputed", c.shortName()).withColor(GOLD));
		}
		if (ClientRealm.siege[i] != 0) {
			ClientRealm.KView b = ClientRealm.kingdom(ClientRealm.siegeBy[i]);
			if (b != null) lines.add(Component.translatable("kingdoms.map.siege", b.shortName(), ClientRealm.siege[i] & 0xFF).withColor(RED));
		}
		lines.add(Component.literal("X " + blockX(fx) + "  Z " + blockZ(fz)).withColor(0xFF777777));
		tooltipLines(g, lines, mx, my);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		double mx = e.x(), my = e.y();
		if (e.button() == 0 && Ui2.inside(mx, my, minimapRect[0], minimapRect[1], minimapRect[2], minimapRect[2])) {
			draggingMini = true;
			minimapJump(mx, my);
			return true;
		}
		if (e.button() == 0) {
			for (int i = buttons.size() - 1; i >= 0; i--) {
				Btn b = buttons.get(i);
				if (Ui2.inside(mx, my, b.x, b.y, b.w, b.h)) {
					b.action.run();
					Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
					return true;
				}
			}
		}
		if (mx >= mapX1 && mx < mapX2 && my >= mapY1 && my < mapY2) {
			if (e.button() == 1) {
				tool = Tool.SELECT;
				picking = null;
				return true;
			}
			dragging = true;
			moved = false;
			pressX = mx;
			pressY = my;
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (draggingMini) {
			minimapJump(e.x(), e.y());
			return true;
		}
		if (dragging) {
			if (Math.abs(e.x() - pressX) + Math.abs(e.y() - pressY) > 3) moved = true;
			camX -= (float) (dx / zoom);
			camZ -= (float) (dy / zoom);
			clampCam();
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (draggingMini) {
			draggingMini = false;
			return true;
		}
		if (dragging) {
			dragging = false;
			if (!moved) clickMap(e.x(), e.y());
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (!narrow && mx < leftW() && my > 26 && my < mapY2) {
			listScroll -= (int) Math.signum(sy);
			return true;
		}
		if (mx >= mapX1 && mx < mapX2) {
			float bx = cx(mx), bz = cz(my);
			zoom *= sy > 0 ? 1.18f : 1 / 1.18f;
			zoom = Math.max(1.2f, Math.min(48f, zoom));
			// keep the point under the cursor in place
			camX = bx - (float) ((mx - (mapX1 + mapX2) / 2f) / zoom);
			camZ = bz - (float) ((my - (mapY1 + mapY2) / 2f) / zoom);
			clampCam();
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	private void clampCam() {
		camX = Math.max(0, Math.min(ClientRealm.n, camX));
		camZ = Math.max(0, Math.min(ClientRealm.n, camZ));
	}

	private void clickMap(double mx, double my) {
		float fx = cx(mx), fz = cz(my);
		int bx = blockX(fx), bz = blockZ(fz);
		int cxi = (int) Math.floor(fx), czi = (int) Math.floor(fz);
		switch (tool) {
			case SELECT -> {
				long nowMs = System.currentTimeMillis();
				for (ClientRealm.AView a : ClientRealm.armies.values()) {
					float x = sx(a.rx(nowMs, ClientRealm.dayMs())), y = sy(a.rz(nowMs, ClientRealm.dayMs()));
					if (Math.abs(mx - x) < 10 && Math.abs(my - y) < 10 && picking == null) {
						selectedArmy = a.id;
						selected = a.kingdom;
						selectedTown = 0;
						pin = null;
						return;
					}
				}
				selectedArmy = 0;
				pin = null;
				for (ClientRealm.TView t : ClientRealm.towns) {
					float x = sx(ClientRealm.cellX(t.x())), y = sy(ClientRealm.cellZ(t.z()));
					if (Math.abs(mx - x) < 9 && Math.abs(my - y) < 9 && picking == null) {
						selectedTown = t.id();
						selected = t.kingdom();
						return;
					}
				}
				int o = ClientRealm.ownerAt(cxi, czi);
				if (picking != null) {
					if (o != 0 && o != selected) {
						god(picking, selected, o, 0, 0);
						picking = null;
					}
					return;
				}
				selected = o;
				selectedTown = 0;
			}
			case FOUND -> {
				god("found", 0, 0, bx, bz);
				tool = Tool.SELECT;
			}
			case SMITE -> god("smite", 0, 0, bx, bz);
			case METEOR -> god("meteor", 0, 0, bx, bz);
			case GIFT -> {
				if (selected != 0) god("gift", selected, 0, bx, bz);
			}
			case ARMY -> {
				if (selected != 0) god("army", selected, 0, bx, bz);
			}
			case OBSERVE -> observe(bx, bz);
		}
	}

	private void observe(int x, int z) {
		god("observe", 0, 0, x, z);
		observing = true;
		tool = Tool.SELECT;
		onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int key = e.key();
		float step = 40f / zoom;
		switch (key) {
			case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> camZ -= step;
			case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> camZ += step;
			case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> camX -= step;
			case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> camX += step;
			case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> zoom *= 1.25f;
			case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> zoom /= 1.25f;
			case GLFW.GLFW_KEY_H -> centerOnPlayer();
			case GLFW.GLFW_KEY_B -> goToBattle();
			case GLFW.GLFW_KEY_TAB -> mode = MapTextures.Mode.values()[(mode.ordinal() + 1) % MapTextures.Mode.values().length];
			default -> {
				if (KingdomsClient.MAP.matches(e) && !(this.getFocused() != null)) {
					onClose();
					return true;
				}
				if (key == GLFW.GLFW_KEY_ESCAPE && (picking != null || tool != Tool.SELECT)) {
					picking = null;
					tool = Tool.SELECT;
					return true;
				}
				return super.keyPressed(e);
			}
		}
		clampCam();
		return true;
	}

	// ------------------------------------------------------------------ drawing helpers

	static void frame(GuiGraphicsExtractor g, String name, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, KingdomsMod.id("frames/" + name), x, y, w, h);
	}

	static void icon(GuiGraphicsExtractor g, String name, float x, float y, float size) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(size / 32f, size / 32f);
		g.blit(RenderPipelines.GUI_TEXTURED, KingdomsMod.id("textures/gui/icons/" + name + ".png"), 0, 0, 0f, 0f, 32, 32, 32, 32);
		g.pose().popMatrix();
	}

	private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String iconName, Component label, boolean active, int mx, int my, Runnable action) {
		button(g, x, y, w, h, iconName, label, active, mx, my, action, false);
	}

	private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String iconName, Component label, boolean active, int mx, int my, Runnable action, boolean danger) {
		boolean hover = Ui2.inside(mx, my, x, y, w, h);
		frame(g, active ? "button_active" : danger ? "button_danger" : hover ? "button_hover" : "button", x, y, w, h);
		int tx = x + 4;
		if (iconName != null) {
			float s = Math.min(h - 4, 14);
			icon(g, iconName, label == null ? x + (w - s) / 2f : x + 3, y + (h - s) / 2f, s);
			tx = (int) (x + s + 6);
		}
		if (label != null) {
			if (iconName == null) Ui2.fitCentered(g, font, label, x + w / 2, y + (h - 8) / 2, w - 6, TEXT);
			else Ui2.fit(g, font, label, tx, y + (h - 8) / 2 + 1, x + w - tx - 3, TEXT, 0.8f);
		}
		buttons.add(new Btn(x, y, w, h, action));
	}

	private int stat(GuiGraphicsExtractor g, String iconName, Component value, int x, int y, int w) {
		icon(g, iconName, x, y, 10);
		Ui2.fit(g, font, value, x + 13, y + 1, w - 15, TEXT, 0.85f);
		return y + 12;
	}

	private static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float v, int color) {
		if (w < 3 || h < 1) return;
		v = Math.max(0, Math.min(1, v));
		g.fill(x, y, x + w, y + h, 0xFF05070C);
		g.fill(x, y, x + (int) (w * v), y + h, color);
		g.outline(x - 1, y - 1, w + 2, h + 2, 0x66B08A3E);
	}

	private void center(GuiGraphicsExtractor g, Component c, int x, int y, int color) {
		int w = font.width(c);
		g.fill(x - w / 2 - 4, y - 3, x + w / 2 + 4, y + 10, 0xAA000000);
		g.text(font, c, x - w / 2, y, color, true);
	}

	private void tooltip(GuiGraphicsExtractor g, Component c, int mx, int my) {
		tooltipLines(g, List.of(c), mx, my);
	}

	private void tooltipLines(GuiGraphicsExtractor g, List<Component> lines, int mx, int my) {
		int w = 0;
		for (Component c : lines) w = Math.max(w, font.width(c));
		w = Math.min(w, width - 16);
		int h = lines.size() * 10 + 4;
		int x = mx + 10, y = my + 10;
		if (x + w + 8 > width) x = mx - w - 14;
		if (y + h > height) y = my - h - 4;
		x = Math.max(2, Math.min(x, width - w - 10));
		y = Math.max(2, Math.min(y, height - h - 2));
		frame(g, "tooltip", x, y, w + 8, h);
		for (int i = 0; i < lines.size(); i++) Ui2.fit(g, font, lines.get(i), x + 4, y + 3 + i * 10, w, TEXT);
	}
}
