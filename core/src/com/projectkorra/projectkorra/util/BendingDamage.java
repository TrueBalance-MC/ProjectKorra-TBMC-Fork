package com.projectkorra.projectkorra.util;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.keys.DamageTypeKeys;
import net.kyori.adventure.key.Key;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * The damage type ProjectKorra deals all ability damage with, {@code projectkorra:bending}. It is registered by
 * ProjectKorra's bootstrapper, so it exists before any plugin enables.
 * <p>
 * Armor reduces it, scaled by ProjectKorra's {@code Properties.IgnoreArmorPercentage} settings. Enchantments such as
 * Protection don't reduce it at all (it is in {@code #minecraft:bypasses_enchantments}), so other plugins can apply
 * their own protection by checking for it, e.g.:
 * <pre>{@code
 * if (BendingDamage.isBending(event)) {
 *     event.setDamage(event.getDamage() * (1 - myProtection(event.getEntity())));
 * }
 * }</pre>
 * Bending damage never triggers Thorns.
 */
public final class BendingDamage {

	/** The key of the bending damage type, {@code projectkorra:bending} */
	public static final Key KEY = Key.key("projectkorra", "bending");
	/** The typed registry key of the bending damage type */
	public static final TypedKey<DamageType> TYPED_KEY = DamageTypeKeys.create(KEY);

	private static DamageType type;

	private BendingDamage() {}

	/**
	 * @return The bending damage type
	 */
	public static DamageType getType() {
		if (type == null) {
			type = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).getOrThrow(TYPED_KEY);
		}
		return type;
	}

	/**
	 * @param source A damage source
	 * @return Whether the damage is from bending
	 */
	public static boolean isBending(final DamageSource source) {
		return source != null && source.getDamageType().key().equals(KEY);
	}

	/**
	 * @param event A damage event
	 * @return Whether the damage is from bending
	 */
	public static boolean isBending(final EntityDamageEvent event) {
		return isBending(event.getDamageSource());
	}
}
