package com.projectkorra.projectkorra.jing;

/**
 * The total jing a player has from the armor they are wearing
 */
public record JingStats(double positive, double negative, double neutral) {

	public static final JingStats NONE = new JingStats(0, 0, 0);

	/**
	 * @param jing The jing to get
	 * @return The total of that jing
	 */
	public double get(final Jing jing) {
		return switch (jing) {
			case POSITIVE -> this.positive;
			case NEGATIVE -> this.negative;
			case NEUTRAL -> this.neutral;
		};
	}

	/**
	 * @return The lowest of the three jings, which anchors water's bonuses
	 */
	public double lowest() {
		return Math.min(this.positive, Math.min(this.negative, this.neutral));
	}

	public boolean isEmpty() {
		return this.positive <= 0 && this.negative <= 0 && this.neutral <= 0;
	}
}
