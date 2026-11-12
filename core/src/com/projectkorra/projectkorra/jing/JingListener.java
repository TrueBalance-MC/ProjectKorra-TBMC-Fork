package com.projectkorra.projectkorra.jing;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.AttributeModification;
import com.projectkorra.projectkorra.attribute.AttributeModifier;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.event.AbilityRecalculateAttributeEvent;
import com.projectkorra.projectkorra.event.AbilityVelocityAffectEntityEvent;
import com.projectkorra.projectkorra.event.PlayerCooldownChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Applies jing bonuses to bending:
 * <ul>
 *     <li>Positive: more ability damage, faster charging and projectiles. Fire: longer burns.</li>
 *     <li>Negative: shorter cooldowns, less fall damage, glancing blows while moving, stronger self-propulsion.
 *     Air: longer and higher abilities. Chi: higher chiblock chance.</li>
 *     <li>Neutral: less ability damage and knockback taken, longer select range and radius. Earth: longer
 *     abilities and chiblock resistance.</li>
 *     <li>Water: all of the above, anchored to the lowest jing, plus a stronger night bonus.</li>
 * </ul>
 * Ability attributes are changed through {@link AbilityRecalculateAttributeEvent}, so they work for any addon
 * ability that marks its fields with {@link Attribute}. Damage, cooldowns and knockback use the events fired by
 * DamageHandler, BendingPlayer#addCooldown and GeneralMethods#setVelocity, which addons use too.
 */
public class JingListener implements Listener {

	private static final NamespacedKey CHARGE_TIME = key("charge_time");
	private static final NamespacedKey SPEED = key("speed");
	private static final NamespacedKey FIRE_TICKS = key("fire_ticks");
	private static final NamespacedKey SELF_PUSH = key("self_push");
	private static final NamespacedKey RANGE = key("range");
	private static final NamespacedKey DURATION = key("duration");
	private static final NamespacedKey HEIGHT = key("height");
	private static final NamespacedKey NIGHT_FACTOR = key("night_factor");

	private static NamespacedKey key(final String name) {
		return new NamespacedKey(ProjectKorra.plugin, "jing_" + name);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(final PlayerJoinEvent event) {
		JingManager.update(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(final PlayerQuitEvent event) {
		JingManager.clear(event.getPlayer().getUniqueId());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onArmorChange(final PlayerArmorChangeEvent event) {
		// The event fires before the inventory reflects the change in some cases, so read it on the next tick
		final Player player = event.getPlayer();
		Bukkit.getScheduler().runTask(ProjectKorra.plugin, () -> {
			if (player.isOnline()) {
				JingManager.update(player);
			}
		});
	}

	@EventHandler
	public void onAttributeRecalc(final AbilityRecalculateAttributeEvent event) {
		final CoreAbility ability = event.getAbility();
		final Player player = ability.getPlayer();
		if (player == null || !JingManager.isEnabled() || JingManager.getStats(player).isEmpty()) {
			return;
		}
		final Element group = JingManager.getGroup(ability.getElement());

		switch (classify(event.getAttribute())) {
			case Attribute.CHARGE_DURATION ->reduce(event, JingManager.getBonus(ability, Jing.POSITIVE, "Positive.ChargeTime"), CHARGE_TIME);
			case Attribute.SPEED -> increase(event, JingManager.getBonus(ability, Jing.POSITIVE, "Positive.Speed"), SPEED);
			case Attribute.FIRE_TICK -> {
				if (group == Element.FIRE) {
					increase(event, JingManager.getBonus(ability, Jing.POSITIVE, "Positive.Fire.FireTicks"), FIRE_TICKS);
				}
			}
			case Attribute.SELF_PUSH -> increase(event, JingManager.getBonus(ability, Jing.NEGATIVE, "Negative.SelfPush"), SELF_PUSH);
			case Attribute.SELECT_RANGE, Attribute.RADIUS -> increase(event, JingManager.getBonus(ability, Jing.NEUTRAL, "Neutral.Range"), RANGE);
			case Attribute.DURATION -> {
				if (group == Element.AIR) {
					increase(event, JingManager.getBonus(ability, Jing.NEGATIVE, "Negative.Air.Duration"), DURATION);
				} else if (group == Element.EARTH || group == Element.WATER) {
					increase(event, JingManager.getBonus(ability, Jing.NEUTRAL, "Neutral.Earth.Duration"), DURATION);
				}
			}
			case Attribute.HEIGHT -> {
				if (group == Element.AIR) {
					increase(event, JingManager.getBonus(ability, Jing.NEGATIVE, "Negative.Air.Height"), HEIGHT);
				}
			}
			default -> {}
		}

		// Water's own bonus: balanced jing strengthens the night factor
		if (group == Element.WATER && event.hasMarker(DayNightFactor.class) && WaterAbility.isNight(player.getWorld())
				&& player.hasPermission("bending.water.nightfactor")) {
			final double bonus = JingManager.getExclusiveBonus(player, Jing.NEUTRAL, "Water.NightFactor", Element.WATER);
			if (bonus > 0) {
				if (event.getMarker(DayNightFactor.class).invert()) {
					reduce(event, 1 - 1 / (1 + bonus), NIGHT_FACTOR);
				} else {
					increase(event, bonus, NIGHT_FACTOR);
				}
			}
		}
	}

	/**
	 * Many abilities name their attributes with a prefix, e.g. "MaxRadius" or "ShieldDuration". This maps those to
	 * the base attribute they are a kind of, so jing applies to them too.
	 * @param attribute The attribute name
	 * @return The base attribute, or the name unchanged if it isn't one jing affects
	 */
	private static String classify(final String attribute) {
		if (attribute.endsWith(Attribute.CHARGE_DURATION) || attribute.endsWith("ChargeDuration")) {
			return Attribute.CHARGE_DURATION; // Checked first so "ChargeDuration" gets shorter, not longer
		}
		for (final String base : new String[] { Attribute.SELECT_RANGE, Attribute.SELF_PUSH, Attribute.FIRE_TICK, Attribute.RADIUS, Attribute.DURATION, Attribute.HEIGHT, Attribute.SPEED }) {
			if (attribute.endsWith(base)) {
				return base;
			}
		}
		return attribute;
	}

	private static void increase(final AbilityRecalculateAttributeEvent event, final double bonus, final NamespacedKey key) {
		if (bonus > 0 && event.getOriginalValue() instanceof Number) {
			event.addModification(AttributeModification.of(AttributeModifier.MULTIPLICATION, 1 + bonus, key));
		}
	}

	private static void reduce(final AbilityRecalculateAttributeEvent event, final double bonus, final NamespacedKey key) {
		if (bonus > 0 && event.getOriginalValue() instanceof Number) {
			event.addModification(AttributeModification.of(AttributeModifier.MULTIPLICATION, 1 - bonus, key));
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onAbilityDamage(final AbilityDamageEntityEvent event) {
		if (!JingManager.isEnabled()) {
			return;
		}
		double damage = event.getDamage();

		// The attacker's positive jing adds damage
		final Player source = event.getSource();
		if (source != null && event.getAbility() instanceof CoreAbility ability) {
			damage *= 1 + JingManager.getBonus(source, Jing.POSITIVE, "Positive.Damage", ability.getElement());
		}

		// The target's neutral jing resists it, and negative jing turns it into a glancing blow while they're moving
		if (event.getEntity() instanceof Player target && !target.equals(source)) {
			double reduction = JingManager.getBestBonus(target, Jing.NEUTRAL, "Neutral.DamageResistance");
			if (target.isSprinting() || target.isGliding() || target.isFlying() || target.getFallDistance() > 0) {
				reduction += JingManager.getBestBonus(target, Jing.NEGATIVE, "Negative.GlancingBlow");
			}
			damage *= 1 - Math.min(reduction, JingManager.getConfig("MaxDamageReduction"));
		}

		event.setDamage(damage);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onCooldown(final PlayerCooldownChangeEvent event) {
		if (event.getResult() != PlayerCooldownChangeEvent.Result.ADDED || !event.isOnline() || !JingManager.isEnabled()) {
			return;
		}
		final CoreAbility ability = CoreAbility.getAbility(event.getAbility());
		if (ability == null) {
			return; // Not an ability cooldown, e.g. choosing an element
		}
		final double bonus = JingManager.getBonus(event.getPlayer().getPlayer(), Jing.NEGATIVE, "Negative.Cooldown", ability.getElement());
		if (bonus > 0) {
			event.setCooldown((long) (event.getCooldown() * (1 - bonus)));
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onKnockback(final AbilityVelocityAffectEntityEvent event) {
		if (!(event.getAffected() instanceof Player target) || !JingManager.isEnabled() || event.getVelocity() == null) {
			return;
		}
		if (event.getAbility() == null || target.equals(event.getAbility().getPlayer())) {
			return; // Only resist other benders' knockback, not your own movement
		}
		final double bonus = JingManager.getBestBonus(target, Jing.NEUTRAL, "Neutral.KnockbackResistance");
		if (bonus > 0) {
			event.setVelocity(event.getVelocity().clone().multiply(1 - bonus));
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFall(final EntityDamageEvent event) {
		if (event.getCause() != DamageCause.FALL || !(event.getEntity() instanceof Player player) || !JingManager.isEnabled()) {
			return;
		}
		final double bonus = JingManager.getBestBonus(player, Jing.NEGATIVE, "Negative.FallDamage");
		if (bonus > 0) {
			event.setDamage(event.getDamage() * (1 - bonus));
		}
	}
}
