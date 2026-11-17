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

	private static final int TOP = 22, BOTTOM = 26;
	private static final int TEXT = 0xFFEDE6D2, MUTED = 0xFFA9A28F, GOLD = 0xFFF2C94C, RED = 0xFFE86A5A, GREEN = 0xFF7EDB7A;

	private static float camX = Float.NaN, camZ;
	private static float zoom = 6f;
	private static int selected;
	private static int selectedTown;
	private static MapTextures.Mode mode = MapTextures.Mode.POLITICAL;
	private static Tool tool = Tool.SELECT;
	static boolean observing;

	private final List<Btn> buttons = new ArrayList<>();
	private String picking; // "war" / "peace" / "ally": waiting for a click on the other kingdom
	private boolean dragging, moved;
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
		return narrow ? 0 : Math.min(118, width / 5);
	}

	private int rightW() {
		return Math.min(148, Math.max(120, width / 4));
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
		narrow = width < 380;
		mapX1 = leftW();
		mapX2 = width - rightW();
		mapY1 = TOP;
		mapY2 = height - BOTTOM;
		float minZoom = Math.max(1.2f, (mapX2 - mapX1) / (float) ClientRealm.n);
		zoom = Math.max(minZoom, Math.min(48f, zoom));

		drawMap(g, mx, my);
		topBar(g, mx, my);
		if (!narrow) kingdomList(g, mx, my);
		rightPanel(g, mx, my);
		toolbar(g, mx, my);
		chronicle(g, mx, my);
		hover(g, mx, my);
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
		towns(g, mx, my);
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

	private void armies(GuiGraphicsExtractor g) {
		long now = System.currentTimeMillis();
		long dayMs = ClientRealm.dayMs();
		for (ClientRealm.AView a : ClientRealm.armies.values()) {
			ClientRealm.KView k = ClientRealm.kingdom(a.kingdom);
			if (k == null) continue;
			float x = sx(a.rx(now, dayMs)), y = sy(a.rz(now, dayMs));
			float size = Math.max(10, Math.min(20, zoom * 1.3f));
			g.fill((int) (x - size / 2), (int) (y + size / 2 - 3), (int) (x + size / 2), (int) (y + size / 2), 0x88000000);
			g.fill((int) (x - size * 0.35f), (int) (y - size * 0.45f), (int) (x + size * 0.45f), (int) (y + size * 0.05f), 0xFF000000 | k.color());
			icon(g, "army", x - size / 2, y - size / 2, size);
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

	// ------------------------------------------------------------------ chrome

	private void topBar(GuiGraphicsExtractor g, int mx, int my) {
		frame(g, "panel", 0, 0, width, TOP);
		icon(g, "crown", 4, 3, 16);
		int year = (int) (ClientRealm.day / Realm.DAYS_PER_YEAR) + 1;
		int season = (int) (ClientRealm.day % Realm.DAYS_PER_YEAR) * 4 / Realm.DAYS_PER_YEAR;
		Component date = Component.translatable("kingdoms.map.date", year, Component.translatable("kingdoms.season." + season));
		g.text(font, date, 24, 7, GOLD, true);
		int x = width - 4;
		// close
		x -= 18;
		button(g, x, 2, 18, 18, null, Component.literal("✕"), false, mx, my, this::onClose);
		x -= 4;
		// layers
		MapTextures.Mode[] modes = MapTextures.Mode.values();
		String[] layerIcons = {"layer_political", "layer_terrain", "layer_diplomacy"};
		for (int i = modes.length - 1; i >= 0; i--) {
			x -= 18;
			MapTextures.Mode m = modes[i];
			button(g, x, 2, 18, 18, layerIcons[i], null, mode == m, mx, my, () -> mode = m);
			if (Ui2.inside(mx, my, x, 2, 18, 18)) tooltip(g, Component.translatable("kingdoms.map.layer." + m.name().toLowerCase(Locale.ROOT)), mx, my);
		}
		x -= 6;
		int[] speeds = {0, 1, 2, 5, 10};
		String[] sIcons = {"pause", "speed1", "speed2", "speed3", "speed3"};
		for (int i = speeds.length - 1; i >= 0; i--) {
			int sp = speeds[i];
			x -= 18;
			boolean active = ClientRealm.speed == sp;
			button(g, x, 2, 18, 18, sIcons[i], null, active, mx, my, () -> {
				if (ClientRealm.god) god("speed", sp, 0, 0, 0);
			});
			if (i == 4) g.text(font, "×10", x + 3, 12, TEXT, true);
			if (Ui2.inside(mx, my, x, 2, 18, 18)) tooltip(g, Component.translatable("kingdoms.map.speed", sp), mx, my);
		}
		Component title = Component.translatable("kingdoms.map.title");
		int tw = font.width(title);
		int dateEnd = 24 + font.width(date) + 8;
		if ((width - tw) / 2 > dateEnd && (width + tw) / 2 < x - 8) g.text(font, title, (width - tw) / 2, 7, TEXT, true);
	}

	private void kingdomList(GuiGraphicsExtractor g, int mx, int my) {
		int w = leftW();
		frame(g, "panel", 0, TOP, w, height - TOP - BOTTOM);
		g.text(font, Component.translatable("kingdoms.map.kingdoms"), 6, TOP + 6, GOLD, true);
		List<ClientRealm.KView> alive = new ArrayList<>();
		for (ClientRealm.KView k : ClientRealm.kingdoms.values()) if (k.alive) alive.add(k);
		alive.sort((a, b) -> Float.compare(b.cells, a.cells));
		float maxCells = 1;
		for (ClientRealm.KView k : alive) maxCells = Math.max(maxCells, k.cells);
		int rowH = 24, y0 = TOP + 18;
		int visible = (height - BOTTOM - y0 - 4) / rowH;
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, alive.size() - visible)));
		for (int i = listScroll; i < alive.size() && i - listScroll < visible; i++) {
			ClientRealm.KView k = alive.get(i);
			int y = y0 + (i - listScroll) * rowH;
			boolean hover = Ui2.inside(mx, my, 3, y, w - 6, rowH - 2);
			if (k.id == selected) g.fill(3, y, w - 3, y + rowH - 2, 0x55F2C94C);
			else if (hover) g.fill(3, y, w - 3, y + rowH - 2, 0x33FFFFFF);
			g.fill(5, y + 2, 11, y + rowH - 4, 0xFF000000 | k.color());
			Ui2.fit(g, font, k.shortName(), 14, y + 2, w - 34, TEXT);
			if (k.wars.length > 0) icon(g, "battle", w - 18, y + 1, 10);
			Ui2.fit(g, font, Component.translatable(Era.of(k.era).key()), 14, y + 12, w - 50, MUTED, 0.75f);
			int bw = (int) ((w - 20) * (k.cells / maxCells));
			g.fill(14, y + rowH - 4, 14 + bw, y + rowH - 3, 0xFF000000 | k.color());
			buttons.add(new Btn(3, y, w - 6, rowH - 2, () -> clickKingdom(k.id)));
		}
		if (alive.isEmpty()) Ui2.wrap(g, font, Component.translatable("kingdoms.map.empty"), 6, y0, w - 12, MUTED);
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
	}

	private void rightPanel(GuiGraphicsExtractor g, int mx, int my) {
		int w = rightW(), x0 = width - w, y = TOP;
		frame(g, "panel", x0, y, w, height - TOP - BOTTOM);
		int x = x0 + 6, iw = w - 12;
		y += 6;
		ClientRealm.TView town = null;
		for (ClientRealm.TView t : ClientRealm.towns) if (t.id() == selectedTown) town = t;
		ClientRealm.KView k = ClientRealm.kingdom(selected);
		if (k == null && town == null) {
			g.text(font, Component.translatable("kingdoms.map.realm"), x, y, GOLD, true);
			y += 12;
			int alive = 0, wars = 0;
			float pop = 0;
			for (ClientRealm.KView kv : ClientRealm.kingdoms.values()) if (kv.alive) {
				alive++;
				pop += kv.pop;
				wars += kv.wars.length;
			}
			y = stat(g, "crown", Component.translatable("kingdoms.map.n_kingdoms", alive), x, y, iw);
			y = stat(g, "people", Component.translatable("kingdoms.map.n_people", (int) pop), x, y, iw);
			y = stat(g, "battle", Component.translatable("kingdoms.map.n_wars", wars / 2), x, y, iw);
			y = stat(g, "town", Component.translatable("kingdoms.map.n_towns", ClientRealm.towns.size()), x, y, iw);
			y += 6;
			y = Ui2.wrap(g, font, Component.translatable(ClientRealm.god ? "kingdoms.map.hint_god" : "kingdoms.map.hint"), x, y, iw, MUTED, 0.75f);
			if (ClientRealm.god) {
				y += 6;
				int yy = y;
				button(g, x, yy, iw, 16, "found", Component.translatable("kingdoms.tool.found"), tool == Tool.FOUND, mx, my, () -> tool = Tool.FOUND);
				y += 20;
				if (System.currentTimeMillis() - confirmDestroy < 3000) {
					button(g, x, y, iw, 16, "destroy", Component.translatable("kingdoms.god.reset_confirm"), false, mx, my, () -> {
						god("reset", 0, 0, 0, 0);
						confirmDestroy = 0;
					}, true);
				} else {
					button(g, x, y, iw, 16, "destroy", Component.translatable("kingdoms.god.reset"), false, mx, my, () -> confirmDestroy = System.currentTimeMillis(), true);
				}
				y += 20;
			}
			if (observing) {
				button(g, x, height - BOTTOM - 22, iw, 16, "observe", Component.translatable("kingdoms.god.return"), false, mx, my, () -> {
					god("return", 0, 0, 0, 0);
					observing = false;
				});
			}
			return;
		}
		if (town != null && (k == null || town.kingdom() != k.id || selectedTown != 0)) {
			townPanel(g, town, x, y, iw, mx, my);
			return;
		}
		// kingdom header
		g.fill(x, y, x + 6, y + 18, 0xFF000000 | k.color());
		Ui2.fit(g, font, k.title(), x + 9, y, iw - 9, TEXT);
		Ui2.fit(g, font, k.alive ? k.kingTitle() : Component.translatable("kingdoms.map.fallen"), x + 9, y + 10, iw - 9, k.alive ? GOLD : RED, 0.75f);
		y += 22;
		// era
		Component era = Component.translatable(Era.of(k.era).key());
		Ui2.fit(g, font, era, x, y, iw, TEXT, 0.85f);
		y += 9;
		float prog = k.knowNext <= k.knowFrom ? 1 : (k.know - k.knowFrom) / (k.knowNext - k.knowFrom);
		bar(g, x, y, iw, 4, prog, 0xFF5FA8F0);
		y += 8;
		int half = iw / 2;
		stat(g, "people", Component.literal(String.valueOf((int) k.pop)), x, y, half);
		stat(g, "soldier", Component.literal((int) k.soldiers + (k.army >= 1 ? "+" + (int) k.army : "")), x + half, y, half);
		y += 12;
		stat(g, "layer_political", Component.translatable("kingdoms.map.cells", k.cells), x, y, half);
		stat(g, "battle", Component.literal(String.valueOf((int) k.might)), x + half, y, half);
		y += 12;
		icon(g, "stability", x, y, 10);
		bar(g, x + 13, y + 3, iw - 13, 4, k.stability / 100f, k.stability > 50 ? GREEN : k.stability > 25 ? GOLD : RED);
		y += 12;
		int third = iw / 3;
		stat(g, "food", Component.literal(String.valueOf((int) k.food)), x, y, third);
		stat(g, "wood", Component.literal(String.valueOf((int) k.wood)), x + third, y, third);
		stat(g, "stone", Component.literal(String.valueOf((int) k.stone)), x + third * 2, y, third);
		y += 12;
		stat(g, "iron", Component.literal(String.valueOf((int) k.iron)), x, y, third);
		stat(g, "gold", Component.literal(String.valueOf((int) k.gold)), x + third, y, third);
		stat(g, "knowledge", Component.literal(String.valueOf((int) k.know)), x + third * 2, y, third);
		y += 14;
		// character
		Component temper = Component.translatable("kingdoms.map.temper",
				trait("agg", k.agg), trait("exp", k.exp), trait("dip", k.dip));
		y = Ui2.wrap(g, font, temper, x, y, iw, MUTED, 0.7f) + 3;
		// relations
		if (k.wars.length > 0) {
			MutableComponent list = Component.empty();
			for (int i = 0; i < k.wars.length; i++) {
				ClientRealm.KView e = ClientRealm.kingdom(k.wars[i]);
				if (e == null) continue;
				if (!list.getSiblings().isEmpty()) list.append(", ");
				list.append(e.shortName().copy().withColor(e.color()));
			}
			y = Ui2.wrap(g, font, Component.translatable("kingdoms.map.at_war", list), x, y, iw, RED, 0.75f) + 2;
		}
		if (k.allies.length > 0) {
			MutableComponent list = Component.empty();
			for (int a : k.allies) {
				ClientRealm.KView e = ClientRealm.kingdom(a);
				if (e == null) continue;
				if (!list.getSiblings().isEmpty()) list.append(", ");
				list.append(e.shortName().copy().withColor(e.color()));
			}
			y = Ui2.wrap(g, font, Component.translatable("kingdoms.map.allies", list), x, y, iw, GREEN, 0.75f) + 2;
		}
		if (!ClientRealm.god || !k.alive) return;
		// divine acts
		y = Math.max(y + 4, height - BOTTOM - 6 - 5 * 18);
		g.text(font, Component.translatable("kingdoms.god.acts"), x, y - 10, GOLD, true);
		int bw = (iw - 2) / 2;
		int id = k.id;
		button(g, x, y, bw, 16, "bless", Component.translatable("kingdoms.god.bless"), false, mx, my, () -> god("bless", id, 0, 0, 0));
		button(g, x + bw + 2, y, bw, 16, "plague", Component.translatable("kingdoms.god.plague"), false, mx, my, () -> god("plague", id, 0, 0, 0));
		y += 18;
		button(g, x, y, bw, 16, "war", Component.translatable("kingdoms.god.war"), "war".equals(picking), mx, my, () -> picking = "war".equals(picking) ? null : "war");
		button(g, x + bw + 2, y, bw, 16, "peace", Component.translatable("kingdoms.god.peace"), "peace".equals(picking), mx, my, () -> picking = "peace".equals(picking) ? null : "peace");
		y += 18;
		button(g, x, y, bw, 16, "ally", Component.translatable("kingdoms.god.ally"), "ally".equals(picking), mx, my, () -> picking = "ally".equals(picking) ? null : "ally");
		button(g, x + bw + 2, y, bw, 16, "era", Component.translatable("kingdoms.god.era"), false, mx, my, () -> god("era", id, 0, 0, 0));
		y += 18;
		button(g, x, y, bw, 16, "rebel", Component.translatable("kingdoms.god.rebel"), false, mx, my, () -> god("rebel", id, 0, 0, 0));
		button(g, x + bw + 2, y, bw, 16, "raise_army", Component.translatable("kingdoms.tool.army"), tool == Tool.ARMY, mx, my, () -> tool = tool == Tool.ARMY ? Tool.SELECT : Tool.ARMY);
		y += 18;
		boolean confirm = System.currentTimeMillis() - confirmDestroy < 3000;
		button(g, x, y, iw, 16, "destroy", Component.translatable(confirm ? "kingdoms.god.destroy_confirm" : "kingdoms.god.destroy"), false, mx, my, () -> {
			if (System.currentTimeMillis() - confirmDestroy < 3000) {
				god("destroy", id, 0, 0, 0);
				confirmDestroy = 0;
				selected = 0;
			} else confirmDestroy = System.currentTimeMillis();
		}, true);
	}

	private Component trait(String key, float v) {
		int level = v < 0.34f ? 0 : v < 0.67f ? 1 : 2;
		return Component.translatable("kingdoms.trait." + key + "." + level);
	}

	private void townPanel(GuiGraphicsExtractor g, ClientRealm.TView t, int x, int y, int iw, int mx, int my) {
		ClientRealm.KView k = ClientRealm.kingdom(t.kingdom());
		icon(g, t.ruin() ? "ruin" : t.capital() ? "castle" : "town", x, y, 18);
		Ui2.fit(g, font, t.title(), x + 22, y, iw - 22, TEXT);
		Ui2.fit(g, font, k == null ? Component.translatable("kingdoms.map.ruins") : k.title().copy().withColor(k.color()), x + 22, y + 10, iw - 22, MUTED, 0.75f);
		y += 24;
		if (t.capital()) {
			Ui2.fit(g, font, Component.translatable("kingdoms.map.capital"), x, y, iw, GOLD, 0.8f);
			y += 10;
		}
		y = stat(g, "town", Component.translatable("kingdoms.map.buildings", t.done(), t.building()), x, y, iw);
		y += 2;
		BuildingType[] types = BuildingType.values();
		for (int i = 0; i < t.types().length && i < types.length; i++) {
			if (t.types()[i] == 0) continue;
			Ui2.fit(g, font, Component.translatable(types[i].key()).append(" ×" + t.types()[i]), x + 4, y, iw - 4, TEXT, 0.75f);
			y += 8;
		}
		y += 6;
		int bx = blockX(ClientRealm.cellX(t.x())), bz = t.z();
		Ui2.fit(g, font, Component.literal("X " + t.x() + "  Z " + t.z()), x, y, iw, MUTED, 0.75f);
		y += 12;
		if (k != null) {
			button(g, x, y, iw, 16, "crown", Component.translatable("kingdoms.map.open_kingdom"), false, mx, my, () -> {
				selected = t.kingdom();
				selectedTown = 0;
			});
			y += 18;
		}
		button(g, x, y, iw, 16, "observe", Component.translatable("kingdoms.tool.observe"), false, mx, my, () -> observe(t.x(), t.z()));
	}

	private void toolbar(GuiGraphicsExtractor g, int mx, int my) {
		frame(g, "panel", 0, height - BOTTOM, width, BOTTOM);
		int x = 4, y = height - BOTTOM + 4;
		for (Tool t : Tool.values()) {
			boolean godTool = t != Tool.SELECT && t != Tool.OBSERVE;
			if (godTool && !ClientRealm.god) continue;
			boolean needsKingdom = t == Tool.GIFT || t == Tool.ARMY;
			button(g, x, y, 18, 18, t.icon, null, tool == t, mx, my, () -> tool = tool == t ? Tool.SELECT : t);
			if (needsKingdom && selected == 0) g.fill(x + 1, y + 1, x + 17, y + 17, 0x88000000);
			if (Ui2.inside(mx, my, x, y, 18, 18)) tooltip(g, Component.translatable("kingdoms.tool." + t.name().toLowerCase(Locale.ROOT) + ".tip"), mx, my);
			x += 20;
		}
		Component hint = Component.translatable("kingdoms.tool." + tool.name().toLowerCase(Locale.ROOT) + ".tip");
		Ui2.fit(g, font, hint, x + 6, y + 5, width - x - 12, MUTED, 0.8f);
	}

	private void chronicle(GuiGraphicsExtractor g, int mx, int my) {
		if (ClientRealm.news.isEmpty()) return;
		int w = Math.min(190, (mapX2 - mapX1) / 2);
		int x = mapX2 - w - 4;
		int y = mapY2 - 4;
		int shown = 0;
		for (int i = ClientRealm.news.size() - 1; i >= 0 && shown < 4; i--) {
			Chronicle.Entry e = ClientRealm.news.get(i);
			if (e.level() == 0 && shown > 2) continue;
			List<FormattedCharSequence> lines = font.split(e.text(), (int) ((w - 8) / 0.75f));
			int h = (int) (lines.size() * 9 * 0.75f) + 4;
			y -= h + 1;
			if (y < (mapY1 + mapY2) / 2) break;
			boolean hov = Ui2.inside(mx, my, x, y, w, h);
			g.fill(x, y, x + w, y + h, hov ? 0xCC2A2416 : 0xAA0D1220);
			g.fill(x, y, x + 2, y + h, e.level() >= 2 ? GOLD : e.level() == 1 ? 0xFF8FA8D8 : 0xFF666666);
			g.pose().pushMatrix();
			g.pose().translate(x + 5, y + 2);
			g.pose().scale(0.75f, 0.75f);
			for (int l = 0; l < lines.size(); l++) g.text(font, lines.get(l), 0, l * 9, TEXT, false);
			g.pose().popMatrix();
			if (e.x() != 0 || e.z() != 0) {
				int ex = e.x(), ez = e.z();
				buttons.add(new Btn(x, y, w, h, () -> {
					camX = ClientRealm.cellX(ex);
					camZ = ClientRealm.cellZ(ez);
					zoom = Math.max(zoom, 8);
				}));
			}
			shown++;
		}
	}

	private void hover(GuiGraphicsExtractor g, int mx, int my) {
		if (!ClientRealm.hasState || mx < mapX1 || mx >= mapX2 || my < mapY1 || my >= mapY2 || dragging) return;
		float fx = cx(mx), fz = cz(my);
		int cxi = (int) Math.floor(fx), czi = (int) Math.floor(fz);
		if (cxi < 0 || czi < 0 || cxi >= ClientRealm.n || czi >= ClientRealm.n) return;
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
		if (dragging) {
			dragging = false;
			if (!moved) clickMap(e.x(), e.y());
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (!narrow && mx < leftW() && my > TOP) {
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
			case GLFW.GLFW_KEY_TAB -> mode = MapTextures.Mode.values()[(mode.ordinal() + 1) % MapTextures.Mode.values().length];
			case GLFW.GLFW_KEY_SPACE -> {
				if (ClientRealm.god) god("speed", ClientRealm.speed == 0 ? 1 : 0, 0, 0, 0);
			}
			case GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4 -> {
				int[] sp = {1, 2, 5, 10};
				if (ClientRealm.god) god("speed", sp[key - GLFW.GLFW_KEY_1], 0, 0, 0);
			}
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
		int h = lines.size() * 10 + 4;
		int x = mx + 10, y = my + 10;
		if (x + w + 8 > width) x = mx - w - 14;
		if (y + h > height) y = my - h - 4;
		frame(g, "tooltip", x, y, w + 8, h);
		for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), x + 4, y + 3 + i * 10, TEXT, true);
	}
}
