package com.dantemorrigan.partner.gametest;

import com.dantemorrigan.partner.LocalPartner;
import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Every item of the game (and of the loaded mods) must survive the partner's save -> bytes -> load cycle. */
public class ItemRoundTripTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			sp.getServer().runOnServer(s -> PartnerManager.summon(s.getPlayerList().getPlayers().getFirst()));
			ctx.waitTicks(20);
			List<String> bad = sp.getServer().computeOnServer(s -> {
				List<String> failures = new ArrayList<>();
				ServerLevel level = s.overworld();
				PartnerEntity p = PartnerManager.find(s.getPlayerList().getPlayers().getFirst());
				List<Item> items = new ArrayList<>();
				BuiltInRegistries.ITEM.forEach(i -> {
					if (i != Items.AIR) items.add(i);
				});
				for (int start = 0; start < items.size(); start += 27) {
					p.backpack().clearContent();
					List<Item> batch = items.subList(start, Math.min(items.size(), start + 27));
					for (Item i : batch) p.backpack().addItem(new ItemStack(i, 1));
					try {
						var problems = new ProblemReporter.Collector();
						TagValueOutput out = TagValueOutput.createWithContext(problems, p.registryAccess());
						p.saveWithoutId(out);
						CompoundTag tag = out.buildResult();
						// through real bytes, as on disk
						ByteArrayOutputStream bytes = new ByteArrayOutputStream();
						net.minecraft.nbt.NbtIo.write(tag, new DataOutputStream(bytes));
						CompoundTag back = net.minecraft.nbt.NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
						PartnerEntity copy = LocalPartner.PARTNER.create(level, EntitySpawnReason.LOAD);
						var inProblems = new ProblemReporter.Collector();
						copy.load(TagValueInput.create(inProblems, p.registryAccess(), back));
						if (!problems.isEmpty() || !inProblems.isEmpty()) failures.add("batch " + start + " problems: " + problems.getReport() + " / " + inProblems.getReport());
						for (Item i : batch) {
							if (copy.backpack().countItem(i) != 1) failures.add("lost: " + BuiltInRegistries.ITEM.getKey(i));
						}
					} catch (Throwable t) {
						failures.add("batch " + start + " threw " + t);
					}
				}
				return failures;
			});
			System.out.println("ROUNDTRIP failures: " + bad.size());
			for (String b : bad) System.out.println("ROUNDTRIP " + b);
			if (!bad.isEmpty()) throw new AssertionError("Items lost in save/load: " + bad.size() + " first: " + bad.get(0));
		}
	}
}
