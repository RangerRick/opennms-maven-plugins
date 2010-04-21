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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

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
     * The directory for the generated WAR.
     *
     * @parameter expression="${project.build.directory}"
     * @required
     */
    private String m_outputDirectory;

    /**
     * The name of the primary war dependency.
     * 
     * @parameter alias="primaryWar"
     * @required
     */
    private String m_primaryWar;
    
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
        	File webXmlFile = new File(workDir.getPath() + File.separator + "WEB-INF" + File.separator + "web.xml");
			FileWriter fw = new FileWriter(webXmlFile);
			fw.write(finalWebXml);
			fw.close();
		} catch (IOException e) {
			throw new MojoExecutionException("unable to write new web.xml", e);
		}
    	
    	String fileName = project.getArtifactId() + "-" + project.getVersion() + "-merged.war";
    	File warFile = new File(this.m_outputDirectory, fileName);
    	FileOutputStream fos = null;
    	JarOutputStream jos = null;
    	try {
    		fos = new FileOutputStream(warFile);
    		jos = new JarOutputStream(fos);
    		
    		addEntry(workDir, workDir, jos);
		} catch (IOException e) {
			throw new MojoExecutionException("unable to write war file " + warFile.getPath(), e);
		} finally {
			IOUtils.closeQuietly(jos);
			IOUtils.closeQuietly(fos);
		}
		
		getProject().getArtifact().setFile(warFile);
    }

    private void addEntry(File root, File path, JarOutputStream stream) throws IOException {
    	String rootPath = root.getCanonicalPath();
    	String filePath = path.getCanonicalPath();
    	filePath = filePath.replace(rootPath, "").replaceFirst("^[/\\\\]*", "").replace("\\", "/");
    	if (path.isDirectory() && !filePath.endsWith("/")) {
    		filePath = filePath + "/";
        	JarEntry entry = new JarEntry(filePath);
        	entry.setTime(path.lastModified());
        	if (!filePath.equals("/")) {
        		stream.putNextEntry(entry);
        		stream.closeEntry();
        	}
        	for (final File nestedFile : path.listFiles()) {
        		addEntry(root, nestedFile, stream);
        	}
    	} else {
    		JarEntry entry = new JarEntry(filePath);
    		entry.setTime(path.lastModified());
    		stream.putNextEntry(entry);
    		FileInputStream fis = null;
    		BufferedInputStream bis = null;
    		try {
    			fis = new FileInputStream(path);
    			bis = new BufferedInputStream(fis);
    		    byte[] buffer = new byte[1024];
    		    while (true) {
    		    	int count = bis.read(buffer);
    		    	if (count == -1) break;
    		    	stream.write(buffer, 0, count);
    		    }
    		    stream.closeEntry();
    		} finally {
    			IOUtils.closeQuietly(bis);
    			IOUtils.closeQuietly(fis);
    		}
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
		InputStream entryInputStream = null;
		Writer writer = null;
		String fileName = null;
		String rawXml = "";

		try {
			final JarFile jarFile = new JarFile(warFile);
			final Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				final JarEntry entry = entries.nextElement();
				fileName = entry.getName();
				
				if (entry.isDirectory()) {
					final File directory = new File(workDir.getPath() + File.separator + fileName);
					directory.mkdirs();
					continue;
				}

				entryInputStream = jarFile.getInputStream(entry);
				final File entryFile = new File(fileName);
				final boolean isWebXml = entryFile.getName().equalsIgnoreCase("web.xml");

				if (isWebXml) {
					writer = new StringWriter();
				} else {
					writer = new FileWriter(workDir.getPath() + File.separator + fileName);
				}

				IOUtils.copy(entryInputStream, writer);
				if (isWebXml) {
					rawXml = writer.toString();
				}

				writer.close();
				writer = null;

				entryInputStream.close();
				entryInputStream = null;
			}
		} catch (Exception jfException) {
			throw new MojoExecutionException("unable to access " + warFile.getPath() + " as a JarFile", jfException);
		} finally {
			IOUtils.closeQuietly(writer);
			IOUtils.closeQuietly(entryInputStream);
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
