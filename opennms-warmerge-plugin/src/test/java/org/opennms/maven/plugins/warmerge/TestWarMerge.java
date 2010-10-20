package org.opennms.maven.plugins.warmerge;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class TestWarMerge {

	@Before
	public void setUp() throws Exception {
	}

	@Test
	@Ignore
	public void testBasics() throws Exception {
		final WarMerge warmerge = new WarMerge();
		warmerge.execute();
	}
	
}
