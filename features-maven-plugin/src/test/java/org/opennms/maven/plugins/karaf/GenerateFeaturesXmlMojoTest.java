package org.opennms.maven.plugins.karaf;

import java.io.File;
import java.io.FileWriter;

import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.codehaus.plexus.util.IOUtil;




public class GenerateFeaturesXmlMojoTest extends AbstractMojoTestCase {

	public void testInitialization() throws Exception {
		final GenerateFeaturesXmlMojo mojo = getMojo("initialization",
			"  <build>\n" + 
			"    <plugins>\n" + 
			"      <plugin>\n" + 
			"        <groupId>org.opennms.maven.plugins</groupId>\n" + 
			"        <artifactId>features-maven-plugin</artifactId>\n" + 
			"        <version>1.0-SNAPSHOT</version>\n" + 
			"        <configuration></configuration>" +
			"      </plugin>\n" + 
			"    </plugins>\n" + 
			"  </build>\n"
		);

		assertNotNull(mojo);
		mojo.execute();
	}


	private GenerateFeaturesXmlMojo getMojo(final String artifactId, final String contents) throws Exception {
		final String pomText = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
		"<project xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://maven.apache.org/POM/4.0.0\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd\">\n" + 
		"\n" + 
		"  <modelVersion>4.0.0</modelVersion>\n" + 
		"  <groupId>com.example.test</groupId>\n" + 
		"  <artifactId>" + artifactId + "</artifactId>\n" + 
		"\n" + 
		"  <packaging>pom</packaging>\n" + 
		"\n" +
		contents +
		"\n" + 
		"</project>\n" + 
		"";
		
		final File tempFile = File.createTempFile("pom", "xml");
		FileWriter writer = null;
		try {
			writer = new FileWriter(tempFile);
			writer.write(pomText);
		} finally {
			IOUtil.close(writer);
		}
		
		tempFile.deleteOnExit();
		
		return (GenerateFeaturesXmlMojo) lookupMojo("generate-features-xml", tempFile);
	}

}
