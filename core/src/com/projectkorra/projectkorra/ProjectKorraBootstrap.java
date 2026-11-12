package com.projectkorra.projectkorra;

import com.projectkorra.projectkorra.util.BendingDamage;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.tags.DamageTypeTagKeys;
import io.papermc.paper.tag.TagEntry;
import org.bukkit.damage.DamageEffect;
import org.bukkit.damage.DamageScaling;

import java.util.List;

/**
 * Runs before the server loads its registries. Registers the {@code projectkorra:bending} damage type, see
 * {@link BendingDamage}.
 */
@SuppressWarnings("UnstableApiUsage")
public class ProjectKorraBootstrap implements PluginBootstrap {

	@Override
	public void bootstrap(final BootstrapContext context) {
		context.getLifecycleManager().registerEventHandler(RegistryEvents.DAMAGE_TYPE.compose().newHandler(event ->
				event.registry().register(BendingDamage.TYPED_KEY, builder -> builder
						.messageId("generic") // Death messages use the vanilla "died" / "died because of" text
						.exhaustion(0.1f) // The same hunger cost as a player's attack
						.damageScaling(DamageScaling.WHEN_CAUSED_BY_LIVING_NON_PLAYER)
						.damageEffect(DamageEffect.HURT))));

		// Enchantments like Protection don't reduce bending damage. It isn't in bypasses_armor, so armor still does,
		// scaled by the IgnoreArmorPercentage settings in DamageHandler.
		context.getLifecycleManager().registerEventHandler(LifecycleEvents.TAGS.preFlatten(RegistryKey.DAMAGE_TYPE).newHandler(event ->
				event.registrar().addToTag(DamageTypeTagKeys.BYPASSES_ENCHANTMENTS, List.of(TagEntry.valueEntry(BendingDamage.TYPED_KEY)))));
	}
}
