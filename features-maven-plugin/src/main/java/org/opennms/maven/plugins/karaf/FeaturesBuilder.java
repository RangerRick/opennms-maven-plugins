package org.opennms.maven.plugins.karaf;

import org.apache.karaf.features.internal.model.Features;

public class FeaturesBuilder {
	private Features m_features = new Features();

	public FeaturesBuilder() {
	}

	public FeaturesBuilder(final String name) {
		m_features.setName(name);
	}

	public FeaturesBuilder setName(final String name) {
		m_features.setName(name);
		return this;
	}

	public Features getFeatures() {
		return m_features;
	}

	public FeatureBuilder createFeature(final String name) {
		return createFeature(name, null);
	}

	public FeatureBuilder createFeature(final String name, final String version) {
		final FeatureBuilder featureBuilder = new FeatureBuilder(name, version);
		m_features.getFeature().add(featureBuilder.getFeature());
		return featureBuilder;
	}

}
