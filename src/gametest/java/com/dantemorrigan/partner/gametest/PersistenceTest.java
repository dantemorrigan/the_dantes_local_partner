package com.dantemorrigan.partner.gametest;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The partner and everything it carries must survive leaving the world and coming back, several times. */
public class PersistenceTest implements FabricClientGameTest {
	private static PartnerEntity partner(MinecraftServer s) {
		return PartnerManager.find(s.getPlayerList().getPlayers().getFirst());
	}

	private static String dump(MinecraftServer s) {
		PartnerEntity p = partner(s);
		if (p == null) return "no partner";
		return "gold=" + p.backpack().countItem(Items.GOLD_INGOT) + " bread=" + p.backpack().countItem(Items.BREAD)
				+ " shards=" + p.backpack().countItem(Items.AMETHYST_SHARD) + " chest=" + p.getItemBySlot(EquipmentSlot.CHEST).getItem()
				+ " hand=" + p.getMainHandItem().getItem() + " hp=" + p.getHealth() + " food=" + p.foodLevel();
	}

	private static void expect(ClientGameTestContext ctx, TestSingleplayerContext sp, String when, int diamonds, int bread) {
		for (int i = 0; i < 400 && sp.getServer().computeOnServer(s -> partner(s) == null); i += 10) ctx.waitTicks(10);
		ctx.waitTicks(30);
		String d = sp.getServer().computeOnServer(PersistenceTest::dump);
		System.out.println("PERSIST " + when + ": " + d);
		boolean ok = sp.getServer().computeOnServer(s -> {
			PartnerEntity p = partner(s);
			return p != null && p.backpack().countItem(Items.GOLD_INGOT) == diamonds && p.backpack().countItem(Items.BREAD) == bread
					&& p.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE) && p.getMainHandItem().is(Items.IRON_PICKAXE);
		});
		if (!ok) throw new AssertionError("Partner state lost " + when + ": " + d);
	}

	private static void fill(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(s -> {
			PartnerEntity p = partner(s);
			p.backpack().clearContent();
			p.backpack().addItem(new ItemStack(Items.GOLD_INGOT, 5));
			p.backpack().addItem(new ItemStack(Items.BREAD, 7));
			p.backpack().addItem(new ItemStack(Items.AMETHYST_SHARD, 3));
			p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
			p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		});
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave save;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			save = sp.getWorldSave();
			sp.getClientLevel().waitForChunksRender();
			sp.getServer().runCommand("gamemode survival @p");
			sp.getServer().runCommand("difficulty peaceful");
			sp.getServer().runOnServer(s -> PartnerManager.summon(s.getPlayerList().getPlayers().getFirst()));
			ctx.waitTicks(30);
			fill(sp);
			ctx.waitTicks(10); // leave right after: the state must be saved even if it changed a moment ago
			expect(ctx, sp, "before leaving", 5, 7);
		}
		for (int round = 1; round <= 2; round++) {
			try (TestSingleplayerContext sp = save.open()) {
				sp.getClientLevel().waitForChunksRender();
				expect(ctx, sp, "after re-entering #" + round, 5, 7);
				// The client must actually SEE it (an entity that exists only on the server looks like "no partner").
				ctx.waitTicks(20);
				int visible = ctx.computeOnClient(mc -> {
					int n = 0;
					for (var e : mc.level.entitiesForRendering()) if (e instanceof PartnerEntity) n++;
					return n;
				});
				System.out.println("PERSIST client sees " + visible + " partner(s) after re-entering #" + round);
				ctx.takeScreenshot("p" + round + "_after_reenter");
				if (visible != 1) throw new AssertionError("The client sees " + visible + " partners after re-entering");
			}
		}
		// Knocked out: it comes back with everything it carried.
		try (TestSingleplayerContext sp = save.open()) {
			sp.getClientLevel().waitForChunksRender();
			expect(ctx, sp, "before the knock-out", 5, 7);
			sp.getServer().runCommand("kill @e[type=local_partner:partner]");
			ctx.waitTicks(20 * 33);
			expect(ctx, sp, "after being knocked out", 5, 7);
		}
		// Far away on its own (free roaming) when the world is closed.
		try (TestSingleplayerContext sp = save.open()) {
			sp.getClientLevel().waitForChunksRender();
			expect(ctx, sp, "before roaming", 5, 7);
			sp.getServer().runOnServer(s -> {
				PartnerEntity p = partner(s);
				p.setOrderQuiet(com.dantemorrigan.partner.PartnerOrder.FREE);
				p.teleportTo(260, p.getY(), 260);
			});
			ctx.waitTicks(60);
			System.out.println("PERSIST roaming: " + sp.getServer().computeOnServer(PersistenceTest::dump));
		}
		try (TestSingleplayerContext sp = save.open()) {
			sp.getClientLevel().waitForChunksRender();
			expect(ctx, sp, "after leaving while roaming far away", 5, 7);
		}
	}
}
