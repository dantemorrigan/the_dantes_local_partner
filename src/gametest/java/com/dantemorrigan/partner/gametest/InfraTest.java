package com.dantemorrigan.partner.gametest;

import com.dantemorrigan.kingdoms.GodPowers;
import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.client.MapScreen;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Road;
import com.dantemorrigan.kingdoms.sim.Settlement;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Targeted check of the new things: a realm with a past, walls with archer towers, roads, arriving by magic in a
 * town that is already built (and no game-mode change), and hovering the map interface at two window sizes.
 */
public class InfraTest implements FabricClientGameTest {
	private static String report() {
		Realm r = KingdomsMod.realm();
		StringBuilder b = new StringBuilder("day " + r.day + " towns " + r.settlements.size() + " roads " + r.roads.size());
		int walls = 0, towers = 0, rails = 0;
		for (Settlement s : r.settlements.values()) {
			if (s.wall > 0 && s.wallProgress >= 1f) walls++;
			for (Settlement.Building bd : s.buildings) if (bd.type == com.dantemorrigan.kingdoms.sim.BuildingType.WALL_TOWER && bd.done()) towers++;
		}
		for (Road ro : r.roads.values()) if (ro.level >= 4) rails++;
		b.append(" walls ").append(walls).append(" towers ").append(towers).append(" railways ").append(rails);
		for (Kingdom k : r.kingdoms.values()) {
			if (!k.alive) continue;
			b.append("\n  #").append(k.id).append(" era ").append(k.era).append(" pop ").append((int) k.population).append(" cells ").append(k.cells)
					.append(" sold ").append((int) k.soldiers).append(" g").append((int) k.gold).append(" f").append((int) k.food).append(" w").append((int) k.wood).append(" s").append((int) k.stone);
		}
		return b.toString();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("2026")).create()) {
			sp.getClientLevel().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set noon");
			server.runCommand("difficulty peaceful");
			for (int i = 0; i < 20 * 120 && !server.computeOnServer(s -> KingdomsMod.realm() != null && KingdomsMod.realm().seeded); i += 20) ctx.waitTicks(20);
			System.out.println("INFRA at the start: " + server.computeOnServer(s -> report()));
			server.runOnServer(s -> Realm.TIME_SCALE = 600);
			ctx.waitTicks(20 * 14);
			server.runOnServer(s -> Realm.TIME_SCALE = 1);
			System.out.println("INFRA after the realm lived on: " + server.computeOnServer(s -> report()));

			// arrive by magic in the biggest capital: it must already stand, and the game mode must not change
			int[] cap = server.computeOnServer(s -> {
				Realm r = KingdomsMod.realm();
				Kingdom best = null;
				for (Kingdom k : r.kingdoms.values()) if (k.alive && (best == null || k.cells > best.cells)) best = k;
				Settlement c = best == null ? null : r.capitalOf(best);
				return c == null ? new int[]{0, 0} : new int[]{c.x, c.z};
			});
			String modeBefore = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().gameMode().getName());
			server.runOnServer(s -> GodPowers.act(s.getPlayerList().getPlayers().getFirst(), "observe", 0, 0, cap[0], cap[1]));
			ctx.waitTicks(30);
			String modeAfter = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().gameMode().getName());
			System.out.println("INFRA teleport: mode " + modeBefore + " -> " + modeAfter);
			int[] counts = server.computeOnServer(s -> {
				Realm r = KingdomsMod.realm();
				int built = 0, total = 0, citizens = 0;
				for (Settlement st : r.settlements.values()) {
					if (Math.abs(st.x - cap[0]) > 20 || Math.abs(st.z - cap[1]) > 20) continue;
					for (Settlement.Building b : st.buildings) {
						total++;
						if (!com.dantemorrigan.kingdoms.world.Constructor.pending(r, st, b)) built++;
					}
				}
				for (var e : s.overworld().getAllEntities()) if (e instanceof com.dantemorrigan.kingdoms.world.CitizenEntity) citizens++;
				return new int[]{built, total, citizens};
			});
			System.out.println("INFRA capital on arrival: buildings in the world " + counts[0] + "/" + counts[1] + ", citizens " + counts[2]);
			ctx.takeScreenshot("i1_arrival");
			server.runCommand("gamemode creative @p");
			server.runCommand("tp @p " + (cap[0] - 20) + " ~ " + (cap[1] - 24));
			ctx.waitTicks(40);
			int gy = server.computeOnServer(s -> s.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, cap[0], cap[1]));
			server.runCommand("tp @p " + (cap[0] + 30) + " " + (gy + 22) + " " + (cap[1] - 62) + " 20 28");
			ctx.waitTicks(60);
			ctx.takeScreenshot("i2_wall_and_towers");

			// the map interface, hovered, at two window sizes
			ctx.setScreen(MapScreen::new);
			ctx.waitTicks(40);
			for (int[] size : new int[][]{{854, 480}, {1280, 720}}) {
				ctx.getInput().resizeWindow(size[0], size[1]);
				ctx.waitTicks(10);
				double[][] spots = {{size[0] * 0.04, size[1] * 0.2}, {size[0] * 0.93, size[1] * 0.9}, {size[0] * 0.06, size[1] * 0.92}, {size[0] * 0.55, size[1] * 0.45}};
				for (int i = 0; i < spots.length; i++) {
					ctx.getInput().setCursorPos(spots[i][0], spots[i][1]);
					ctx.waitTicks(6);
					ctx.takeScreenshot("i3_ui_" + size[0] + "_" + i);
				}
			}
			ctx.setScreen(() -> null);
			System.out.println("INFRA end");
		}
	}
}
