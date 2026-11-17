package com.dantemorrigan.partner.gametest;

import com.dantemorrigan.kingdoms.GodPowers;
import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.client.MapScreen;
import com.dantemorrigan.kingdoms.sim.Army;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Kingdoms end to end in a real client on a normal world: the realm is surveyed, kingdoms rise and grow on their
 * own, a capital is built in the world, the strategy map shows it all (both languages), divine acts start a war.
 * Screenshots: build/run/clientGameTest/screenshots.
 */
public class KingdomsTest implements FabricClientGameTest {
	private static String summary() {
		Realm r = KingdomsMod.realm();
		if (r == null) return "no realm";
		StringBuilder b = new StringBuilder("day " + r.day + " towns " + r.settlements.size() + " armies " + r.armies.size() + " caravans " + r.caravans.size()
				+ " trade-events " + r.chronicle.stream().filter(e -> e.key().contains("caravan") || e.key().contains("trade_done")).count());
		for (Kingdom k : r.kingdoms.values()) {
			b.append("\n  #").append(k.id).append(k.alive ? "" : " (dead)").append(" era ").append(k.era).append(" pop ").append((int) k.population)
					.append(" cells ").append(k.cells).append(" sold ").append((int) k.soldiers).append(" wars ").append(k.wars)
					.append(" allies ").append(k.allies).append(" stab ").append((int) k.stability)
					.append(" res f").append((int) k.food).append(" w").append((int) k.wood).append(" s").append((int) k.stone).append(" g").append((int) k.gold);
		}
		return b.toString();
	}

	private static void log(TestSingleplayerContext sp, String what) {
		System.out.println("KINGDOMS " + what + ": " + sp.getServer().computeOnServer(s -> summary()));
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("2026")).create()) {
			sp.getClientLevel().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set noon");
			server.runCommand("difficulty peaceful");

			// 1. The land is surveyed and the first kingdoms rise.
			for (int i = 0; i < 20 * 120 && !server.computeOnServer(s -> KingdomsMod.realm() != null && KingdomsMod.realm().seeded); i += 20) ctx.waitTicks(20);
			log(sp, "seeded");
			if (!server.computeOnServer(s -> KingdomsMod.realm().seeded && !KingdomsMod.realm().kingdoms.isEmpty())) throw new AssertionError("No kingdoms rose");

			// 2. Time flies: they build, grow, expand.
			server.runOnServer(s -> KingdomsMod.realm().speed = 20);
			ctx.waitTicks(20 * 25);
			log(sp, "after ~250 days");

			// 3. Look at the map.
			ctx.setScreen(MapScreen::new);
			ctx.waitTicks(20 * 4);
			ctx.takeScreenshot("k1_map");

			// 4. Visit the biggest capital in the world.
			int[] cap = server.computeOnServer(s -> {
				Realm r = KingdomsMod.realm();
				Kingdom best = null;
				for (Kingdom k : r.kingdoms.values()) if (k.alive && (best == null || k.cells > best.cells)) best = k;
				Settlement c = best == null ? null : r.capitalOf(best);
				return c == null ? new int[]{0, 0} : new int[]{c.x, c.z};
			});
			ctx.setScreen(() -> null);
			server.runOnServer(s -> KingdomsMod.realm().speed = 2);
			server.runCommand("gamemode spectator @p");
			server.runCommand("tp @p " + (cap[0] - 26) + " 140 " + (cap[1] - 26) + " -45 35");
			ctx.waitTicks(20 * 6);
			server.runCommand("tp @p " + (cap[0] - 26) + " ~ " + (cap[1] - 26) + " -45 35");
			sp.getClientLevel().waitForChunksRender();
			// put the camera above the ground by the town
			int ground = server.computeOnServer(s -> s.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, cap[0], cap[1]));
			server.runCommand("tp @p " + (cap[0] - 24) + " " + (ground + 22) + " " + (cap[1] - 24) + " -45 32");
			ctx.waitTicks(20 * 12);
			sp.getClientLevel().waitForChunksRender();
			log(sp, "at the capital " + cap[0] + "," + cap[1] + " ground " + ground);
			ctx.takeScreenshot("k2_capital");
			server.runCommand("tp @p " + (cap[0] + 6) + " " + (ground + 5) + " " + (cap[1] - 18) + " 0 15");
			ctx.waitTicks(20 * 5);
			ctx.takeScreenshot("k3_street");
			int citizens = server.computeOnServer(s -> {
				int n = 0;
				for (var e : s.overworld().getAllEntities()) if (e instanceof com.dantemorrigan.kingdoms.world.CitizenEntity) n++;
				return n;
			});
			System.out.println("KINGDOMS citizens alive near the capital: " + citizens);

			// 5. Divine acts: war between the two strongest neighbours, a blessing.
			List<Integer> ids = server.computeOnServer(s -> {
				List<Integer> l = new ArrayList<>();
				for (Kingdom k : KingdomsMod.realm().kingdoms.values()) if (k.alive) l.add(k.id);
				return l;
			});
			if (ids.size() >= 2) {
				server.runOnServer(s -> {
					var p = s.getPlayerList().getPlayers().getFirst();
					GodPowers.act(p, "war", ids.get(0), ids.get(1), 0, 0);
					GodPowers.act(p, "bless", ids.get(0), 0, 0, 0);
					KingdomsMod.realm().speed = 10;
				});
			}
			server.runCommand("gamemode creative @p");
			if (!ids.isEmpty()) MapScreen.select(ids.get(0));
			ctx.setScreen(MapScreen::new);
			ctx.waitTicks(20 * 15);
			log(sp, "after the war started");
			ctx.takeScreenshot("k4_map_war");

			// 5b. An army in the flesh: soldiers stand in the world, march and besiege.
			server.runOnServer(s -> KingdomsMod.realm().speed = 2);
			// Two armies at war meet next to the first kingdom's capital: a real battle.
			int[] ar = ids.size() < 2 ? null : server.computeOnServer(s -> {
				Realm r = KingdomsMod.realm();
				Settlement c = r.capitalOf(r.kingdoms.get(ids.get(0)));
				if (c == null) return null;
				var p = s.getPlayerList().getPlayers().getFirst();
				GodPowers.act(p, "war", ids.get(0), ids.get(1), 0, 0);
				GodPowers.act(p, "army", ids.get(0), ids.get(1), c.x + 24, c.z + 24);
				GodPowers.act(p, "army", ids.get(1), ids.get(0), c.x + 40, c.z + 24);
				r.speed = 2;
				return new int[]{c.x + 30, c.z + 24};
			});
			if (ar != null) {
				ctx.setScreen(() -> null);
				final int[] fa = ar;
				server.runCommand("gamemode spectator @p");
				int gy = server.computeOnServer(s -> s.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, fa[0], fa[1]));
				server.runCommand("tp @p " + (fa[0] - 18) + " " + (gy + 16) + " " + (fa[1] - 18) + " -45 30");
				ctx.waitTicks(20 * 15);
				int[] soldiers = server.computeOnServer(s -> {
					int w = 0, g = 0;
					for (var e : s.overworld().getAllEntities()) if (e instanceof com.dantemorrigan.kingdoms.world.CitizenEntity c && c.role() == 2) {
						if (c.armyId != 0) w++;
						else g++;
					}
					return new int[]{w, g};
				});
				System.out.println("KINGDOMS soldiers in the world: army=" + soldiers[0] + " garrison=" + soldiers[1]);
				ctx.takeScreenshot("k6_army");
				server.runCommand("gamemode creative @p");
			} else System.out.println("KINGDOMS no army to look at");

			// 6. Russian.
			ctx.setScreen(() -> null);
			ctx.runOnClient(mc -> {
				mc.getLanguageManager().setSelected("ru_ru");
				mc.options.languageCode = "ru_ru";
			});
			ctx.runOnClient(mc -> mc.reloadResourcePacks());
			ctx.waitTicks(60);
			ctx.setScreen(MapScreen::new);
			ctx.waitTicks(20 * 3);
			ctx.takeScreenshot("k5_map_ru");
			ctx.setScreen(() -> null);
			ctx.runOnClient(mc -> {
				mc.getLanguageManager().setSelected("en_us");
				mc.options.languageCode = "en_us";
			});
			ctx.runOnClient(mc -> mc.reloadResourcePacks());
			ctx.waitTicks(40);
			log(sp, "end");
		}
	}
}
