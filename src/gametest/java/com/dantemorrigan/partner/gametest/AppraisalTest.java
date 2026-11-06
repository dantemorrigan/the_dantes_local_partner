package com.dantemorrigan.partner.gametest;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Gifts: armor goes on, weapons get compared, Infinity items are politely refused. */
public class AppraisalTest implements FabricClientGameTest {
	private static void give(TestSingleplayerContext sp, ItemStack stack) {
		sp.getServer().runOnServer(s -> {
			ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
			PartnerEntity p = PartnerManager.find(player);
			player.setItemInHand(InteractionHand.MAIN_HAND, stack);
			player.interactOn(p, InteractionHand.MAIN_HAND, p.position());
		});
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			sp.getServer().runCommand("gamemode survival @p");
			sp.getServer().runCommand("difficulty peaceful");
			sp.getServer().runOnServer(s -> PartnerManager.summon(s.getPlayerList().getPlayers().getFirst()));
			ctx.waitTicks(30);

			give(sp, new ItemStack(Items.IRON_SWORD));
			ctx.waitTicks(90);
			ctx.takeScreenshot("a1_sword_gift");
			give(sp, new ItemStack(Items.DIAMOND_CHESTPLATE));
			ctx.waitTicks(60);
			give(sp, new ItemStack(Items.WOODEN_SWORD)); // worse than the iron one: kept as a spare
			ctx.waitTicks(60);
			ctx.takeScreenshot("a2_armor_gift");
			sp.getServer().runOnServer(s -> {
				PartnerEntity p = PartnerManager.find(s.getPlayerList().getPlayers().getFirst());
				if (!p.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)) throw new AssertionError("Did not put on the chestplate");
				if (p.backpack().countItem(Items.IRON_SWORD) != 1 || p.backpack().countItem(Items.WOODEN_SWORD) != 1) throw new AssertionError("Swords not kept");
			});
			// An Infinity item must be refused and stay with the player.
			Item gauntlet = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("infinity_gauntlet", "infinity_gauntlet"));
			if (gauntlet == Items.AIR) gauntlet = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("infinity_gauntlet", "gauntlet"));
			if (gauntlet != Items.AIR) {
				give(sp, new ItemStack(gauntlet));
				ctx.waitTicks(30);
				ctx.takeScreenshot("a3_refused");
				sp.getServer().runOnServer(s -> {
					ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
					PartnerEntity p = PartnerManager.find(player);
					for (int i = 0; i < p.backpack().getContainerSize(); i++) {
						if (BuiltInRegistries.ITEM.getKey(p.backpack().getItem(i).getItem()).getNamespace().equals("infinity_gauntlet")) {
							throw new AssertionError("The partner took an Infinity item");
						}
					}
				});
			}
		}
	}
}
