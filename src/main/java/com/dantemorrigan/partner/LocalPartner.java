package com.dantemorrigan.partner;

import com.dantemorrigan.partner.compat.InfinityCompat;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalPartner implements ModInitializer {
	public static final String MOD_ID = "local_partner";
	public static final Logger LOG = LoggerFactory.getLogger("LocalPartner");

	private static final ResourceKey<EntityType<?>> PARTNER_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("partner"));
	/** The companion: player-sized, player-like stats, drawn with the player model. */
	public static final EntityType<PartnerEntity> PARTNER = Registry.register(BuiltInRegistries.ENTITY_TYPE, PARTNER_KEY,
			EntityType.Builder.<PartnerEntity>of(PartnerEntity::new, MobCategory.MISC)
					.sized(0.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10).noSummon().build(PARTNER_KEY));

	/** Keeps the chunks around a roaming partner loaded and ticking while no player is near (refreshed every second). */
	public static final net.minecraft.server.level.TicketType ROAM_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, id("roaming_partner"),
			new net.minecraft.server.level.TicketType(60L, 2 | 4 | 8));

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(PARTNER, PartnerEntity.createAttributes());
		PartnerFx.init();
		PartnerNetwork.init();
		PartnerManager.init();
		PartnerCommand.init();
		Territory.init();
		InfinityCompat.init();
		LOG.info("Local Partner ready{}", InfinityCompat.LOADED ? " (The Dante's Infinity detected)" : "");
	}
}
