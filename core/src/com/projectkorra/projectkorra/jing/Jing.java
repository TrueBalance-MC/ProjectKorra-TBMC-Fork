package com.projectkorra.projectkorra.jing;

import com.projectkorra.projectkorra.Element;

/**
 * The three kinds of jing that armor can carry. Each one is a DynamicTools custom attribute, and has an element that
 * gets extra effects from it.
 */
public enum Jing {

	/** Advancing and attacking. Fire's jing. */
	POSITIVE("positive_jing", Element.FIRE),
	/** Evading and retreating. Air's jing. */
	NEGATIVE("negative_jing", Element.AIR),
	/** Waiting, listening and enduring. Earth's jing. */
	NEUTRAL("neutral_jing", Element.EARTH);

	private final String attributeId;
	private final Element affinity;

	Jing(final String attributeId, final Element affinity) {
		this.attributeId = attributeId;
		this.affinity = affinity;
	}

	/**
	 * @return The ID of the jing's custom attribute in DynamicTools' custom-attributes.yml
	 */
	public String getAttributeId() {
		return this.attributeId;
	}

	/**
	 * @return The element this jing complements. Water uses all three.
	 */
	public Element getAffinity() {
		return this.affinity;
	}
}
