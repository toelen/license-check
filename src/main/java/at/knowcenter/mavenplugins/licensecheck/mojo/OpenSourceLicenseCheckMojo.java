/*
The MIT License (MIT)

Copyright (c) 2013 Michael Rice <me@michaelrice.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */
package at.knowcenter.mavenplugins.licensecheck.mojo;

import at.knowcenter.mavenplugins.licensecheck.model.LicenseDescriptor;
import com.google.common.base.Charsets;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.enterprise.inject.InjectionException;
import java.io.*;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * This plugin uses Aether to cruise through the dependencies in the project, find the related pom files,
 * extract the license information, and then convert the licenses into codes.
 * If a license cannot be verified or if the license appears on the user's blacklist, then the build will fail.
 *
 * For more, visit https://github.com/mrice/license-check
 *
 * @author michael.rice
 */
@Mojo(name = "os-check", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class OpenSourceLicenseCheckMojo extends AbstractMojo
{

  private static final Locale LOCALE = Locale.ENGLISH;

  @Component
  private MavenProject project;
  /**
   * This is the repo system required by Aether
   *
   * For more, visit http://blog.sonatype.com/people/2011/01/how-to-use-aether-in-maven-plugins/
   */
  @Component
  private
  RepositorySystem repoSystem;
  /**
   * The current repository and network configuration of Maven
   *
   * For more, visit http://blog.sonatype.com/people/2011/01/how-to-use-aether-in-maven-plugins/
   *
   */
  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
  private
  RepositorySystemSession repoSession;

  /**
   * This is the project's remote repositories that can be used for resolving plugins and their dependencies
   */
  @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
  private
  List<RemoteRepository> remoteRepos;

  /**
   * This is the maximum number of parents to search through (in case there's a malformed pom).
   */
  @Parameter(property = "os-check.recursion-limit", defaultValue = "12")
  private
  int maxSearchDepth;

  /**
   * A list of artifacts that should be excluded from consideration. Example: &lt;configuration&gt; &lt;excludes&gt;
   * &lt;param&gt;full:artifact:coords&lt;/param&gt;>
   * &lt;/excludes&gt; &lt;/configuration&gt;
   */
  @Parameter(property = "os-check.excludes")
  private
  String[] excludes;

  /**
   * A list of artifacts that should be excluded from consideration. Example: &lt;configuration&gt;
   * &lt;excludesRegex&gt; &lt;param&gt;full:artifact:coords&lt;/param&gt;>
   * &lt;/excludesRegex&gt; &lt;/configuration&gt;
   */
  @Parameter(property = "os-check.excludesRegex")
  private
  String[] excludesRegex;

  @Parameter(property = "os-check.excludesNoLicense")
  private
  boolean excludeNoLicense;

  /**
   * A list of blacklisted licenses. Example: &lt;configuration&gt; &lt;blacklist&gt;
   * &lt;param&gt;agpl-3.0&lt;/param&gt; &lt;param&gt;gpl-2.0&lt;/param&gt;
   * &lt;param&gt;gpl-3.0&lt;/param&gt; &lt;/blacklist&gt; &lt;/configuration&gt;
   */
  @Parameter(property = "os-check.blacklist")
  private
  String[] blacklist;

  /**
   * A list of whitelisted licenses. Example: &lt;configuration&gt; &lt;whitelist&gt;
   * &lt;param&gt;agpl-3.0&lt;/param&gt; &lt;param&gt;gpl-2.0&lt;/param&gt;
   * &lt;param&gt;gpl-3.0&lt;/param&gt; &lt;/blacklist&gt; &lt;/configuration&gt;
   */
  @Parameter(property = "os-check.whitelist")
  private
  String[] whitelist;

  /**
   * A list of scopes to exclude. May be used to exclude artifacts with test or provided scope from license check.
   * Example: &lt;configuration&gt; &lt;excludedScopes&gt; &lt;param&gt;test&lt;/param&gt;
   * &lt;param&gt;provided&lt;/param&gt; &lt;/excludedScopes&gt; &lt;/configuration&gt;
   */
  @Parameter(property = "os-check.excludedScopes")
  private
  String[] excludedScopes;

  /**
   * Used to hold the list of license descriptors. Generation is lazy on the first method call to use it.
   */
  private List<LicenseDescriptor> descriptors;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    getLog().info("------------------------------------------------------------------------");
    getLog().info("VALIDATING OPEN SOURCE LICENSES                                         ");
    getLog().info("------------------------------------------------------------------------");

    final Set<String> excludeSet = getAsLowerCaseSet(excludes);
    final Set<String> blacklistSet = getAsLowerCaseSet(blacklist);
    final Set<String> whitelistSet = getAsLowerCaseSet(whitelist);
    final Set<String> excludedScopesSet = getAsLowerCaseSet(excludedScopes);
    final List<Pattern> excludePatternList = getAsPatternList(excludesRegex);

    if (project == null) {
      throw new InjectionException("Maven Project not injected into Plugin!");
    }

    final Set<Artifact> artifacts = project.getArtifacts();
    artifacts.addAll(project.getDependencyArtifacts());
    getLog().info("Validating licenses for " + artifacts.size() + " artifact(s)");

    final Map<String, String> licenses = new HashMap<>();

    boolean buildFails = false;
    for (final Artifact artifact : artifacts) {
      if (!artifactIsOnExcludeList(excludeSet, excludePatternList, excludedScopesSet, artifact)) {
        final ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(RepositoryUtils.toArtifact(artifact));
        request.setRepositories(remoteRepos);

        ArtifactResult result = null;
        try {
          result = repoSystem.resolveArtifact(repoSession, request);
          getLog().info(result.toString());
        } catch (@NotNull final ArtifactResolutionException e) {
          // TODO: figure out how to deal with this one
        }

        String licenseName = "";
        try {
          if (result != null) {
            licenseName = recurseForLicenseName(RepositoryUtils.toArtifact(result.getArtifact()), 0);
          }
        } catch (IOException e) {
          getLog().error("Error reading license information", e);
        }
        String code = convertLicenseNameToCode(licenseName);
        if (code == null) {
          if (!excludeNoLicense) {
            buildFails = true;
            code = "[NULL] LICENSE '" + licenseName + "' IS UNKNOWN";
            getLog().warn("Build will fail because of artifact '" + toCoordinates(artifact) + "' and license'" + licenseName + "'.");
          }
        } else if (!blacklistSet.isEmpty() && isContained(blacklistSet, code)) {
          buildFails = true;
          code += " IS ON YOUR BLACKLIST";
        } else if (!whitelistSet.isEmpty() && !isContained(whitelistSet, code)) {
          buildFails = true;
          code += " IS NOT ON YOUR WHITELIST";
        }
        licenses.put(artifact.getArtifactId(), code);
      } else {
        licenses.put(artifact.getArtifactId(), "SKIPPED because artifact is on your exclude list");
      }

    }

    getLog().info("");
    getLog().info("This plugin validates that the artifacts you're using have a");
    getLog().info("known license declared in the pom.");
    getLog().info("If it can't find a match or if the license is on your");
    getLog().info("declared blacklist or not on your declared whitelist, then the build will fail.");
    getLog().info("");
    getLog().info("--[ Licenses found ]------ ");
    for (final Map.Entry<String, String> artifact : licenses.entrySet()) {
      getLog().info("\t" + artifact.getKey() + ": " + artifact.getValue());
    }

    if (buildFails) {
      getLog().warn("");
      getLog().warn("RESULT: At least one license could not be verified or appears on your blacklist or is not on your whitelist. Build fails.");
      getLog().warn("");
      throw new MojoFailureException("blacklist/whitelist of unverifiable license");
    }
    getLog().info("");
    getLog().info("RESULT: license check complete, no issues found.");
    getLog().info("");

  }

  @NotNull Set<String> getAsLowerCaseSet(@Nullable final String[] src)
  {
    final Set<String> target = new HashSet<>();
    if (src != null) {
      for (final String s : src) {
        target.add(s.toLowerCase(LOCALE));
      }
    }
    return target;
  }

  @NotNull
  private List<Pattern> getAsPatternList(@Nullable final String[] src)
  {
    final List<Pattern> target = new ArrayList<>();
    if (src != null) {
      for (final String s : src) {
        try {
          final Pattern pattern = Pattern.compile(s);
          target.add(pattern);
        } catch (@NotNull final PatternSyntaxException e) {
          getLog().warn("The regex " + s + " is invalid: " + e.getLocalizedMessage());
        }

      }
    }
    return target;
  }

  @NotNull
  private String toCoordinates(@NotNull final Artifact artifact)
  {
    return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
  }

  @Nullable
  private String recurseForLicenseName(@NotNull final Artifact artifact, final int currentDepth) throws IOException, MojoFailureException {

    final String pom = readPomContents(artifact);

    // first, look for a license
    String licenseName = extractLicenseName(pom);
    if (licenseName == null) {
      final String parentArtifactCoords = extractParentCoords(pom);
      if (parentArtifactCoords != null) {
        // search for the artifact
        final Artifact parent = retrieveArtifact(parentArtifactCoords);
        if (parent != null) {
          // check the recursion depth
          if (currentDepth >= maxSearchDepth) {
            return null; // TODO throw an exception
          }
          licenseName = recurseForLicenseName(parent, currentDepth + 1);
        } else {
          return null;
        }
      } else {
        return null;
      }
    }
    return licenseName;
  }

  @NotNull
  private String readPomContents(@NotNull final Artifact artifact) throws IOException, MojoFailureException {
    FileSystem jarFs = null;
    try {

      final File artifactDirectory = artifact.getFile().getParentFile();
      String path = artifactDirectory.getAbsolutePath();
      path += "/" + artifact.getArtifactId() + "-" + artifact.getVersion() + ".pom";


      final StringBuilder buffer = new StringBuilder();
      BufferedReader reader = null;

      //check if the file exists
      File pathFile = new File(path);
      if (!pathFile.exists()) {
        getLog().debug("File " + pathFile + " not found!");
        //read the pom from the jar
        getLog().debug("File " + artifact.getFile() + " will be used instead!");
        if (artifact.getFile().exists()) {
          getLog().debug("File " + artifact.getFile() + " exists!");
          if (artifact.getFile()
                      .toString()
                      .toLowerCase()
                      .endsWith("pom.xml")) { // if the artifact itself is a pom, load it
            getLog().debug("File " + artifact.getFile() + " is a pom, using that!");
            reader = com.google.common.io.Files.newReader(artifact.getFile(), Charsets.UTF_8);
          } else if (artifact.getFile()
                             .toString()
                             .toLowerCase()
                             .endsWith(".jar")) {
            getLog().debug(
                    "File " + artifact.getFile() + " is a jar, trying to find pom inside!");// if the artifact is a jar, try to open the pom inside
            jarFs = FileSystems.newFileSystem(artifact.getFile().toPath(), null);
            Path jarPom = jarFs.getPath(
                    "META-INF/maven/" + artifact.getGroupId() + "/" + artifact.getArtifactId() + "/pom.xml");
            if (Files.exists(jarPom)) {
              getLog().debug("File " + jarPom + " from inside jar will be used!");
              reader = Files.newBufferedReader(jarPom);
            } else {
              getLog().debug("File " + jarPom + " not found!");
            }

          }
        }

      } else {
        getLog().debug("File " + path + " will be used as pom!");
        reader = com.google.common.io.Files.newReader(pathFile, Charsets.UTF_8);
      }
      if (reader == null) {
        throw new MojoFailureException("No pom file for artifact " + artifact.getFile() + " found!");
      }
      String line;
      while ((line = reader.readLine()) != null) {
        buffer.append(line);
      }


      reader.close();


      return buffer.toString();
    } finally {
      if (jarFs != null) {
        jarFs.close();
      }
    }
  }

  /**
   * This function looks for the license textual description. I didn't really want to parse the pom xml since we're
   * just looking for one little snippet of the content.
   *
   * @param raw
   * @return
   */
  // TODO make this more elegant and less brittle
  @Nullable
  private String extractLicenseName(@NotNull final String raw)
  {
    final String licenseTagStart = "<license>", licenseTagStop = "</license>";
    final String nameTagStart = "<name>", nameTagStop = "</name>";
    if (raw.contains(licenseTagStart)) {
      final String licenseContents = raw.substring(raw.indexOf(licenseTagStart) + licenseTagStart.length(), raw.indexOf(licenseTagStop));
      return licenseContents.substring(licenseContents.indexOf(nameTagStart) + nameTagStart.length(), licenseContents.indexOf(nameTagStop));
    }
    return null;
  }

  /**
   * @param raw
   * @return
   */
  // TODO obviously this code needs a lot of error protection and handling
  @Nullable
  private String extractParentCoords(@NotNull final String raw)
  {
    final String parentTagStart = "<parent>", parentTagStop = "</parent>";
    final String groupTagStart = "<groupId>", groupTagStop = "</groupId>";
    final String artifactTagStart = "<artifactId>", artifactTagStop = "</artifactId>";
    final String versionTagStart = "<version>", versionTagStop = "</version>";

    if (!raw.contains(parentTagStart)) {
      return null;
    }
    final String contents = raw.substring(raw.indexOf(parentTagStart) + parentTagStart.length(), raw.indexOf(parentTagStop));
    final String group = contents.substring(contents.indexOf(groupTagStart) + groupTagStart.length(), contents.indexOf(groupTagStop));
    final String artifact = contents.substring(contents.indexOf(artifactTagStart) + artifactTagStart.length(), contents.indexOf(artifactTagStop));
    final String version = contents.substring(contents.indexOf(versionTagStart) + versionTagStart.length(), contents.indexOf(versionTagStop));
    return group + ":" + artifact + ":" + version;
  }

  /**
   * Uses Aether to retrieve an artifact from the repository.
   *
   * @param coordinates as in groupId:artifactId:version
   * @return the located artifact
   */
  @Nullable
  private Artifact retrieveArtifact(final String coordinates)
  {

    final ArtifactRequest request = new ArtifactRequest();
    request.setArtifact(new DefaultArtifact(coordinates));
    request.setRepositories(remoteRepos);

    ArtifactResult result = null;
    try {
      result = repoSystem.resolveArtifact(repoSession, request);
    } catch (@NotNull final ArtifactResolutionException e) {
            try {
              //if the artifact jar is not found, retry the request for the pom instead of the jar
                org.eclipse.aether.artifact.Artifact art = request.getArtifact();
                request
                    .setArtifact(new DefaultArtifact(art.getGroupId(), art.getArtifactId(), "pom", art.getVersion()));
                result = repoSystem.resolveArtifact(repoSession, request);
            } catch (@NotNull final ArtifactResolutionException ex) {
                getLog().error("Could not resolve parent artifact (" + coordinates + "): " + ex.getMessage());
            }
    }

    if (result != null) {
      return RepositoryUtils.toArtifact(result.getArtifact());
    }
    return null;
  }

  /**
   * This is the method that looks at the textual description of the license and returns a code version by running
   * regex. It needs a lot of optimization.
   *
   * @param licenseName
   * @return
   */
  @Nullable
  private String convertLicenseNameToCode(@Nullable final String licenseName)
  {
    if (licenseName == null) {
      return null;
    }
    if (descriptors == null) {
      loadDescriptors();
    }
    for (final LicenseDescriptor descriptor : descriptors) {
      // TODO there's gotta be a faster way to do this
      final Pattern pattern = Pattern.compile(descriptor.getRegex(), Pattern.CASE_INSENSITIVE);
      final Matcher matcher = pattern.matcher(licenseName);
      if (matcher.find()) {
        return descriptor.getCode();
      }
    }
    return null;
  }

  /**
   * This method looks for a resource file bundled with the plugin that contains a tab delimited version of the regex
   * and the codes. Future versions of the plugin may retrieve fresh versions of the file from the server so we can
   * take advantage of improvements in what we know and what we learn.
   */
  // TODO I know, I know... this is really raw... will make it prettier
  private void loadDescriptors()
  {

    final String licensesPath = "/licenses.txt";
    final InputStream is = getClass().getResourceAsStream(licensesPath);
    BufferedReader reader = null;
    descriptors = new ArrayList<>();
    final StringBuilder buffer = new StringBuilder();
    try {
      reader = new BufferedReader(new InputStreamReader(is, Charsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        buffer.append(line).append("\n");
      }
    } catch (@NotNull final Exception e) {
      getLog().error(e);
    } finally {
      try {
        if ( reader != null){
        	reader.close();
        }
      } catch (@NotNull final IOException e) {
        // TODO
        e.printStackTrace();
      }
    }

    final String[] lines = buffer.toString().split("\n");
    for (final String line : lines) {
      final String[] columns = line.split("\\t");
      final LicenseDescriptor descriptor = new LicenseDescriptor();
      descriptor.setCode(columns[0]);
      descriptor.setLicenseName(columns[2]);
      descriptor.setRegex(columns[3]);
      descriptors.add(descriptor);
    }
  }

  /**
   * Checks to see if an artifact is on the user's exclude list
   *
   * @param excludeSet
   * @param patternList
   * @param excludedScopes
   * @param artifact
   * @return
   */
  private boolean artifactIsOnExcludeList(final Set<String> excludeSet, @NotNull final List<Pattern> patternList,
                                          @NotNull final Set<String> excludedScopes, @NotNull final Artifact artifact)
  {
    return isExcludedTemplate(excludeSet, patternList, toCoordinates(artifact)) || excludedScopes.contains(artifact.getScope());
  }

  private boolean isExcludedTemplate(final Set<String> excludeSet, @NotNull final List<Pattern> patternList,
                                     @NotNull final String template)
  {
    if (isContained(excludeSet, template)) {
      return true;
    }
    for (final Pattern pattern : patternList) {
      if (pattern.matcher(template).matches()) {
        return true;
      }
    }
    return false;
  }

  private boolean isContained(@Nullable final Set<String> set, @Nullable final String template) {
    return set != null && template != null && set.contains(template.toLowerCase(LOCALE));
  }

}
