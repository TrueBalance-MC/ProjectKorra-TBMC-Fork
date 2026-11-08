package com.projectkorra.projectkorra.chiblocking;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;

public class Smokescreen extends ChiAbility {

	private static final Map<Integer, Smokescreen> SNOWBALLS = new ConcurrentHashMap<>();
	/**
	 * When each snowball was thrown. Snowballs that never hit anything (e.g. lost in the void) are forgotten after
	 * {@link #SNOWBALL_LIFETIME} so SNOWBALLS doesn't grow forever.
	 */
	private static final Map<Integer, Long> SNOWBALL_TIMES = new ConcurrentHashMap<>();
	private static final long SNOWBALL_LIFETIME = 60_000;
	private static final Map<String, Long> BLINDED_TIMES = new ConcurrentHashMap<>();
	private static final Map<String, Smokescreen> BLINDED_TO_ABILITY = new ConcurrentHashMap<>();

	@Attribute(Attribute.DURATION)
	private int duration;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute(Attribute.RADIUS)
	private double radius;

	public Smokescreen(final Player player) {
		super(player);
		if (!this.bPlayer.canBend(this)) {
			return;
		}
		this.cooldown = getConfig().getLong("Abilities.Chi.Smokescreen.Cooldown");
		this.duration = getConfig().getInt("Abilities.Chi.Smokescreen.Duration");
		this.radius = getConfig().getDouble("Abilities.Chi.Smokescreen.Radius");
		this.start();
	}

	@Override
	public void progress() {
		final long now = System.currentTimeMillis();
		SNOWBALL_TIMES.entrySet().removeIf(entry -> {
			final boolean expired = now - entry.getValue() > SNOWBALL_LIFETIME || !SNOWBALLS.containsKey(entry.getKey());
			if (expired) {
				SNOWBALLS.remove(entry.getKey());
			}
			return expired;
		});
		final int id = this.player.launchProjectile(Snowball.class).getEntityId();
		SNOWBALLS.put(id, this);
		SNOWBALL_TIMES.put(id, now);
		this.bPlayer.addCooldown(this);
		this.remove();
	}

	public static void playEffect(final Location loc) {
		int z = -2;
		int x = -2;
		final int y = 0;

		for (int i = 0; i < 125; i++) {
			final Location newLoc = new Location(loc.getWorld(), loc.getX() + x, loc.getY() + y, loc.getZ() + z);
			for (int direction = 0; direction < 8; direction++) {
				newLoc.getWorld().spawnParticle(Particle.SMOKE, newLoc, 4, 0.5, 0.5, 0.5, 0, null, true);
			}
			if (z == 2) {
				z = -2;
			}
			if (x == 2) {
				x = -2;
				z++;
			}
			x++;
		}
	}

	public void applyBlindness(final Entity entity) {
		if (entity instanceof Player) {
			if (Commands.invincible.contains(((Player) entity).getName())) {
				return;
			} else if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
				return;
			}
			final Player p = (Player) entity;
			p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, this.duration * 20, 2));
			BLINDED_TIMES.put(p.getName(), System.currentTimeMillis());
			BLINDED_TO_ABILITY.put(p.getName(), this);
		}
	}

	public static void removeFromHashMap(final Entity entity) {
		if (entity instanceof Player) {
			final Player p = (Player) entity;
			if (BLINDED_TIMES.containsKey(p.getName())) {
				final Smokescreen smokescreen = BLINDED_TO_ABILITY.get(p.getName());
				// duration is in seconds; clear the record once the blindness has worn off
				if (BLINDED_TIMES.get(p.getName()) + smokescreen.duration * 1000L < System.currentTimeMillis()) {
					BLINDED_TIMES.remove(p.getName());
					BLINDED_TO_ABILITY.remove(p.getName());
				}
			}
		}
	}

	@Override
	public String getName() {
		return "Smokescreen";
	}

	@Override
	public Location getLocation() {
		return this.player != null ? this.player.getLocation() : null;
	}

	@Override
	public long getCooldown() {
		return this.cooldown;
	}

	@Override
	public boolean isSneakAbility() {
		return false;
	}

	@Override
	public boolean isHarmlessAbility() {
		return false;
	}

	public void setCooldown(final long cooldown) {
		this.cooldown = cooldown;
	}

	public int getDuration() {
		return this.duration;
	}

	public void setDuration(final int duration) {
		this.duration = duration;
	}

	public double getRadius() {
		return this.radius;
	}

	public void setRadius(final double radius) {
		this.radius = radius;
	}

	public static Map<Integer, Smokescreen> getSnowballs() {
		return SNOWBALLS;
	}

	public static Map<String, Long> getBlindedTimes() {
		return BLINDED_TIMES;
	}

	public static Map<String, Smokescreen> getBlindedToAbility() {
		return BLINDED_TO_ABILITY;
	}

}
