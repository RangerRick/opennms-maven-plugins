package org.opennms.maven.plugins.warmerge;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.archiver.util.DefaultFileSet;

/**
 * Goal which combines 2 or more .war files.
 *
 * @goal warmerge
 * @phase package
 */
public class WarMerge extends AbstractMojo {
	/**
     * The maven project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;


    /**
     * @readonly
     * @required
     * @component
     * */
    private org.apache.maven.artifact.factory.ArtifactFactory artifactFactory;

    /**
     * @readonly
     * @required
     * @component
     * */
    private org.apache.maven.artifact.resolver.ArtifactResolver resolver;

    /**
     * @readonly
     * @required
     * @component
     * */
    private ArchiverManager m_archiverManager;

    /**
     * The directory for the generated WAR.
     *
     * @readonly
     * @required
     * @parameter expression="${project.build.directory}"
     */
    private String m_outputDirectory;

    /**
     * The name of the primary war dependency.
     * 
     * @required
     * @parameter alias="primaryWar"
     */
    private String m_primaryWar;

    /**
     * The directory to store the generated web.xml.  This will
     * generally coincide with the webappDirectory configured
     * in the maven-war-plugin
     * 
     * @required
     * @parameter alias="webappDirectory"
     */
    private String m_webappDirectory;

    private List<Artifact> m_warArchives = new ArrayList<Artifact>();
    private Pattern m_matchToken = Pattern.compile("\\s*\\<\\!\\-\\-\\s*WARMERGE:\\s+(.*?)\\s+(.*?)\\s*\\-\\-\\>\\s*$", Pattern.DOTALL | Pattern.UNIX_LINES);

    public void execute() throws MojoExecutionException {
    	getWarFiles();

    	String primaryXml = null;
    	final File workDir = new File(m_outputDirectory + File.separator + "warmerge-temp");
    	if (!workDir.exists()) {
    		workDir.mkdirs();
    	}

    	final Map<String,List<String>> webXmlTokens = new HashMap<String,List<String>>();

    	for (final Artifact artifact : m_warArchives) {
    		final File warFile = artifact.getFile();

    		getLog().debug("WARMERGE: processing WAR file: " + warFile.getPath());
    		String webXml = processWarFile(workDir, warFile);

    		Map<String,String> contents = tokenizeXml(webXml);

    		for (final String key : contents.keySet()) {
    			List<String> tokenValues = webXmlTokens.get(key);
    			if (tokenValues == null) {
    				tokenValues = new ArrayList<String>();
    				webXmlTokens.put(key, tokenValues);
    			}
    			
    			tokenValues.add(contents.get(key));
    		}

    		final String artifactId = artifact.getGroupId() + ":" + artifact.getArtifactId();
    		if (artifactId.equals(m_primaryWar)) {
    			primaryXml = webXml;
    		}
    	}

    	if (primaryXml == null) {
    		throw new MojoExecutionException("no war files were processed, or " + m_primaryWar + " did not match any war file!");
    	}

    	String finalWebXml = combineWebXmls(primaryXml, webXmlTokens);

    	try {
        	File webXmlFile = new File(m_webappDirectory + File.separator + "WEB-INF" + File.separator + "web.xml");
			FileWriter fw = new FileWriter(webXmlFile);
			fw.write(finalWebXml);
			fw.close();
		} catch (IOException e) {
			throw new MojoExecutionException("unable to write new web.xml", e);
		}

    	String fileName = project.getArtifactId() + "-" + project.getVersion() + "-merged.war";

    	JarArchiver archiver = new JarArchiver();
    	/*
    	try {
    		archiver = m_archiverManager.getArchiver("jar");
		} catch (NoSuchArchiverException e) {
			throw new MojoExecutionException("unable to get jar archiver", e);
		}
		*/

//		File warFile = new File(this.m_outputDirectory, fileName);
    	File warFile = getProject().getArtifact().getFile();
		archiver.setDestFile(warFile);
		archiver.setIncludeEmptyDirs(true);

		DefaultFileSet fileSet = new DefaultFileSet();
		fileSet.setDirectory(workDir);
		try {
			archiver.addFileSet(fileSet);
		} catch (ArchiverException e) {
			throw new MojoExecutionException("an error occurred archiving " + workDir.getPath(), e);
		}
		try {
			archiver.createArchive();
		} catch (ArchiverException e) {
			throw new MojoExecutionException("an error occurred archiving " + workDir.getPath(), e);
		} catch (IOException e) {
			throw new MojoExecutionException("an error occurred writing " + workDir.getPath(), e);
		}
    }

    @SuppressWarnings("unchecked")
	private String combineWebXmls(final String templateXml, final Map<String, List<String>> webXmlTokens) throws MojoExecutionException {
    	final StringReader stringReader = new StringReader(templateXml);
    	StringBuffer finalWebXml = new StringBuffer();

    	Matcher matcher = null;
    	boolean skip = false;
    	
    	try {
	    	for (final String line : (List<String>)IOUtils.readLines(stringReader)) {
	
				matcher = m_matchToken.matcher(line);
				String command = null;
				String argument = null;
				boolean matches = matcher.matches();
				if (matches) {
					command = matcher.group(1);
					argument = matcher.group(2);
	
					if (command.equalsIgnoreCase("begin")) {
						skip = true;
						getLog().warn("WARMERGE: " + m_primaryWar + " is your primary war file, but it contains begin/end capture tokens!");
					} else if (command.equalsIgnoreCase("end") && argument.equalsIgnoreCase("context-param")) {
						skip = false;
						getLog().warn("WARMERGE: " + m_primaryWar + " is your primary war file, but it contains begin/end capture tokens!");
					} else if (command.equalsIgnoreCase("insert")) {
						final List<String> entries = webXmlTokens.get(argument);
						if (entries == null) {
							getLog().debug("WARMERGE: insert '" + argument + "' found, but there are no tokens");
							continue;
						}
						for (final String entry : entries) {
							finalWebXml.append(entry);
						}
						finalWebXml.append("\n");
					}
				} else {
					if (!skip) {
						finalWebXml.append(line).append("\n");
					}
				}
	    		
	    	}
    	} catch (IOException e) {
    		throw new MojoExecutionException("an error occurred reading the primary web.xml", e);
    	} finally {
    		IOUtils.closeQuietly(stringReader);
    	}
    	return finalWebXml.toString();
    }

    private Map<String,String> tokenizeXml(final String xml) throws MojoExecutionException {
    	final Map<String,String> sections = new HashMap<String,String>();
    	if (xml == null || xml.matches("^\\s*$")) {
    		return sections;
    	}

		final StringBuffer tokenizedXml = new StringBuffer();

		StringReader stringReader = new StringReader(xml);
		BufferedReader reader = new BufferedReader(stringReader);
		String line = null;
		Matcher matcher = null;
		boolean inSection = false;
		try {
			while ((line = reader.readLine()) != null) {
				matcher = m_matchToken.matcher(line);
				String command = null;
				String argument = null;
				boolean matches = matcher.matches();
				if (matches) {
					command = matcher.group(1);
					argument = matcher.group(2);
					if ("begin".equals(command)) {
						inSection = true;
					} else if ("end".equals(command)) {
						inSection = false;
						if (argument != null && !argument.matches("^\\s*$")) {
							sections.put(argument, tokenizedXml.toString());
							tokenizedXml.setLength(0);
						}
					} else if ("insert".equals(command)) {
						// ignored when tokenizing source web.xml's
					} else {
						getLog().warn("WARMERGE: unknown token: " + command + "(" + argument + ")");
					}
				} else {
					if (inSection) {
						tokenizedXml.append(line).append("\n");
					}
				}
			}
		} catch (IOException e) {
			throw new MojoExecutionException("unable to read web.xml", e);
		}

		return sections;
    }
    
	private String processWarFile(final File workDir, final File warFile) throws MojoExecutionException {
		String rawXml = "";

		StringWriter sw = null;
		FileReader fr = null;

		try {
			UnArchiver unarchiver = m_archiverManager.getUnArchiver(warFile);
			unarchiver.setSourceFile(warFile);
			unarchiver.setDestDirectory(workDir);
			unarchiver.extract("WEB-INF/web.xml", workDir);
			File tempFile = new File(workDir, "WEB-INF/web.xml");
			sw = new StringWriter();
			fr = new FileReader(tempFile);
			IOUtils.copy(fr, sw);
			fr.close();
			sw.close();
			tempFile.delete();
			rawXml = sw.toString();
		} catch (IOException e) {
			IOUtils.closeQuietly(fr);
			IOUtils.closeQuietly(sw);
			throw new MojoExecutionException("unable to create temporary directory", e);
		} catch (ArchiverException e) {
			throw new MojoExecutionException("unable to unarchive " + warFile.getPath(), e);
		} catch (NoSuchArchiverException e) {
			throw new MojoExecutionException("unable to determine unarchiver for " + warFile.getPath(), e);
		}
		return rawXml;
	}

	@SuppressWarnings("unchecked")
	private void getWarFiles() {
		final ArtifactFilter filter = new WarArtifactFilter();
    	for (final Iterator<Artifact> iter = getProject().getArtifacts().iterator(); iter.hasNext();) {
    		final Artifact artifact = (Artifact) iter.next();
    		if (filter.include(artifact)) {
    			m_warArchives.add(artifact);
    		}
    	}
	}

    /**
     * get the configured project
     * @return the maven project
     */
    public MavenProject getProject()
    {
        return this.project;
    }

    /**
     * set the configured project
     * @param project the project to use
     */
    public void setProject(final MavenProject project)
    {
        this.project = project;
    }

    private static class WarArtifactFilter implements ArtifactFilter {
		private ScopeArtifactFilter m_scopeFilter;
		public WarArtifactFilter() {
			m_scopeFilter = new ScopeArtifactFilter(Artifact.SCOPE_RUNTIME);
		}
		public boolean include(final Artifact artifact) {
			if (!m_scopeFilter.include(artifact)) return false;
			if (artifact.isOptional()) return false;
			if ("war".equals(artifact.getType())) {
				return true;
			}
			return false;
		}
    }
}
