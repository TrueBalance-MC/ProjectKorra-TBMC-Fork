package com.projectkorra.projectkorra.jing;

import org.bukkit.entity.LivingEntity;
import us.to.midensthings.dynamicTools.tools.CustomAttribute;

/**
 * Reads jing through DynamicTools' custom attribute API. Kept separate so the DynamicTools classes are only loaded
 * when the plugin is installed.
 */
final class DynamicToolsHook {

	private DynamicToolsHook() {}

	/**
	 * @param entity The entity to read
	 * @return The entity's total jing from the armor and tools it's using
	 */
	static JingStats read(final LivingEntity entity) {
		return new JingStats(
				CustomAttribute.getValue(entity, Jing.POSITIVE.getAttributeId()),
				CustomAttribute.getValue(entity, Jing.NEGATIVE.getAttributeId()),
				CustomAttribute.getValue(entity, Jing.NEUTRAL.getAttributeId()));
	}
}
