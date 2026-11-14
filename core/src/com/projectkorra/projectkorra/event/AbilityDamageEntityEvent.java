package com.projectkorra.projectkorra.event;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import com.projectkorra.projectkorra.ability.Ability;

/**
 * Called when an ability damages an {@link Entity}
 *
 * @author kingbirdy
 *
 */
public class AbilityDamageEntityEvent extends Event implements Cancellable {

	private static final HandlerList handlers = new HandlerList();

	private boolean cancelled = false;
	private final Entity entity;
	private final Ability ability;
	private double damage;
	private double damageBonus = 0;
	private final boolean ignoreArmor;

	/**
	 * Create a new AbilityDamageEntityEvent
	 *
	 * @param entity The entity that was damaged
	 * @param ability The damaging ability
	 * @param damage The amount of damage done
	 */
	public AbilityDamageEntityEvent(final Entity entity, final Ability ability, final double damage, final boolean ignoreArmor) {
		this.entity = entity;
		this.ability = ability;
		this.damage = damage;
		this.ignoreArmor = ignoreArmor;
	}

	/**
	 * Returns the damage dealt to the entity
	 *
	 * @return the amount of damage done
	 */
	public double getDamage() {
		return this.damage;
	}

	/**
	 * Sets the damage dealt to the entity
	 *
	 * @param damage the amount of damage done
	 */
	public void setDamage(final double damage) {
		this.damage = damage;
	}

	/**
	 * Adds a percentage bonus to the damage, e.g. 0.1 for 10% more damage (negative values reduce it). Bonuses from
	 * every source (Positive Jing, other plugins' buffs, etc.) are added together and applied once, so they stack
	 * additively instead of multiplying each other: a 30% and a 10% bonus give 40% more damage, not 43%.
	 * This is the way to add a damage bonus, use {@link #setDamage(double)} only for changes that should apply
	 * before the bonuses.
	 *
	 * @param bonus the bonus as a fraction of the damage
	 */
	public void addDamageBonus(final double bonus) {
		this.damageBonus += bonus;
	}

	/**
	 * @return the total of every bonus added with {@link #addDamageBonus(double)}
	 */
	public double getDamageBonus() {
		return this.damageBonus;
	}

	/**
	 * @return the damage that will be dealt: {@link #getDamage()} with the bonuses applied, never below 0
	 */
	public double getFinalDamage() {
		return Math.max(0, this.damage * (1 + this.damageBonus));
	}

	/**
	 * Gets the entity that was damaged
	 *
	 * @return the damaged entity
	 */
	public Entity getEntity() {
		return this.entity;
	}

	/**
	 * Gets the ability used
	 *
	 * @return ability used
	 */
	public Ability getAbility() {
		return this.ability;
	}

	/**
	 * Returns whether the ability ignores armor
	 *
	 * @return true if the ability ignores armor
	 */
	public boolean doesIgnoreArmor() {
		return this.ignoreArmor;
	}

	/**
	 * Gets the player that used the ability
	 *
	 * @return player that used ability
	 */
	public Player getSource() {
		return this.ability.getPlayer();
	}

	@Override
	public HandlerList getHandlers() {
		return handlers;
	}

	public static HandlerList getHandlerList() {
		return handlers;
	}

	@Override
	public boolean isCancelled() {
		return this.cancelled;
	}

	@Override
	public void setCancelled(final boolean cancelled) {
		this.cancelled = cancelled;
	}
}
