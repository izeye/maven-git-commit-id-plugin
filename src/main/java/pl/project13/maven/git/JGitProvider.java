package pl.project13.maven.git;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import com.google.common.io.Closeables;
import com.google.common.io.Files;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pl.project13.jgit.DescribeCommand;
import pl.project13.jgit.DescribeResult;
import pl.project13.maven.git.log.LoggerBridge;
import pl.project13.maven.git.log.MavenLoggerBridge;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.google.common.base.Strings.isNullOrEmpty;

/**
*
* @author <a href="mailto:konrad.malawski@java.pl">Konrad 'ktoso' Malawski</a>
*/
public class JGitProvider extends GitDataProvider {

  private File dotGitDirectory;
  private Repository git;
  private ObjectReader objectReader;
  private RevWalk revWalk;
  private RevCommit headCommit;

  @NotNull
  public static JGitProvider on(@NotNull File dotGitDirectory) {
    return new JGitProvider(dotGitDirectory);
  }

  JGitProvider(@NotNull File dotGitDirectory){
    this.dotGitDirectory = dotGitDirectory;
  }

  @NotNull
  public JGitProvider withLoggerBridge(LoggerBridge bridge) {
    super.loggerBridge = bridge;
    return this;
  }

  @NotNull
  public JGitProvider setVerbose(boolean verbose) {
    super.verbose = verbose;
    super.loggerBridge.setVerbose(verbose);
    return this;
  }

  public JGitProvider setPrefixDot(String prefixDot) {
    super.prefixDot = prefixDot;
    return this;
  }

  public JGitProvider setAbbrevLength(int abbrevLength) {
    super.abbrevLength = abbrevLength;
    return this;
  }

  public JGitProvider setDateFormat(String dateFormat) {
    super.dateFormat = dateFormat;
    return this;
  }

  public JGitProvider setGitDescribe(GitDescribeConfig gitDescribe){
    super.gitDescribe = gitDescribe;
    return this;
  }

  @Override
  protected void init() throws MojoExecutionException{
    git = getGitRepository();
    objectReader = git.newObjectReader();
  }

  @Override
  protected String getBuildAuthorName(){
    String userName = git.getConfig().getString("user", null, "name");
    return userName;
  }

  @Override
  protected String getBuildAuthorEmail(){
    String userEmail = git.getConfig().getString("user", null, "email");
    return userEmail;
  }

  @Override
  protected void prepareGitToExtractMoreDetailedReproInformation() throws MojoExecutionException{
    try{
      // more details parsed out bellow
      Ref HEAD = git.getRef(Constants.HEAD);
      if (HEAD == null) {
        throw new MojoExecutionException("Could not get HEAD Ref, are you sure you've set the dotGitDirectory property of this plugin to a valid path?");
      }
      revWalk = new RevWalk(git);
      headCommit = revWalk.parseCommit(HEAD.getObjectId());
      revWalk.markStart(headCommit);
    }catch(Exception e){
      throw new MojoExecutionException("Error", e);
    }
  }

  @Override
  protected String getBranchName() throws IOException{
    String branch = determineBranchName(git, System.getenv());
    return branch;
  }

  @Override
  protected String getGitDescribe() throws MojoExecutionException{
    String gitDescribe = getGitDescribe(git);
    return gitDescribe;
  }

  @Override
  protected String getCommitId(){
    String commitId = headCommit.getName();
    return commitId;
  }

  @Override
  protected String getAbbrevCommitId() throws MojoExecutionException{
    String abbrevCommitId = getAbbrevCommitId(objectReader, headCommit, abbrevLength);
    return abbrevCommitId;
  }

  @Override
  protected String getCommitAuthorName(){
    String commitAuthor = headCommit.getAuthorIdent().getName();
    return commitAuthor;
  }

  @Override
  protected String getCommitAuthorEmail(){
    String commitEmail = headCommit.getAuthorIdent().getEmailAddress();
    return commitEmail;
  }

  @Override
  protected String getCommitMessageFull(){
    String fullMessage = headCommit.getFullMessage();
    return fullMessage;
  }

  @Override
  protected String getCommitMessageShort(){
    String shortMessage = headCommit.getShortMessage();
    return shortMessage;
  }

  @Override
  protected String getCommitTime(){
    long timeSinceEpoch = headCommit.getCommitTime();
    Date commitDate = new Date(timeSinceEpoch * 1000); // git is "by sec" and java is "by ms"
    SimpleDateFormat smf = new SimpleDateFormat(dateFormat);
    return smf.format(commitDate);
  }

  @Override
  protected String getRemoteOriginUrl(){
    String remoteOriginUrl = git.getConfig().getString("remote", "origin", "url");
    return remoteOriginUrl;
  }

  @Override
  protected void finalCleanUp(){
    revWalk.dispose();
  }


  @VisibleForTesting
  String getGitDescribe(@NotNull Repository repository) throws MojoExecutionException {
    try {
      DescribeResult describeResult = DescribeCommand
          .on(repository)
          .withLoggerBridge(super.loggerBridge)
          .setVerbose(super.verbose)
          .apply(super.gitDescribe)
          .call();

      return describeResult.toString();
    } catch (GitAPIException ex) {
      ex.printStackTrace();
      throw new MojoExecutionException("Unable to obtain git.commit.id.describe information", ex);
    }
  }

  private String getAbbrevCommitId(ObjectReader objectReader, RevCommit headCommit, int abbrevLength) throws MojoExecutionException {
    validateAbbrevLength(abbrevLength);

    try {
      AbbreviatedObjectId abbreviatedObjectId = objectReader.abbreviate(headCommit, abbrevLength);
      return abbreviatedObjectId.name();
    } catch (IOException e) {
      throw new MojoExecutionException("Unable to abbreviate commit id! " +
                                           "You may want to investigate the <abbrevLength/> element in your configuration.", e);
    }
  }

  /**
   * If running within Jenkins/Hudosn, honor the branch name passed via GIT_BRANCH env var.  This
   * is necessary because Jenkins/Hudson alwways invoke build in a detached head state.
   *
   * @param git
   * @param env
   * @return results of git.getBranch() or, if in Jenkins/Hudson, value of GIT_BRANCH
   */
  protected String determineBranchName(Repository git, Map<String, String> env) throws IOException {
    if (runningOnBuildServer(env)) {
      return determineBranchNameOnBuildServer(git, env);
    } else {
      return git.getBranch();
    }
  }

  /**
   * Detects if we're running on Jenkins or Hudson, based on expected env variables.
   * <p/>
   * TODO: How can we detect Bamboo, TeamCity etc? Pull requests welcome.
   *
   * @return true if running
   * @see <a href="https://wiki.jenkins-ci.org/display/JENKINS/Building+a+software+project#Buildingasoftwareproject-JenkinsSetEnvironmentVariables">JenkinsSetEnvironmentVariables</a>
   * @param env
   */
  private boolean runningOnBuildServer(Map<String, String> env) {
    return env.containsKey("HUDSON_URL") || env.containsKey("JENKINS_URL");
  }

  /**
   * Is "Jenkins aware", and prefers {@code GIT_BRANCH} to getting the branch via git if that enviroment variable is set.
   * The {@GIT_BRANCH} variable is set by Jenkins/Hudson when put in detached HEAD state, but it still knows which branch was cloned.
   */
  protected String determineBranchNameOnBuildServer(Repository git, Map<String, String> env) throws IOException {
    String enviromentBasedBranch = env.get("GIT_BRANCH");
    if(isNullOrEmpty(enviromentBasedBranch)) {
      log("Detected that running on CI enviroment, but using repository branch, no GIT_BRANCH detected.");
      return git.getBranch();
    }else {
      log("Using environment variable based branch name.", "GIT_BRANCH =", enviromentBasedBranch);
      return enviromentBasedBranch;
    }
  }

  @NotNull
  private Repository getGitRepository() throws MojoExecutionException {
    Repository repository;

    FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
    try {
      repository = repositoryBuilder
          .setGitDir(dotGitDirectory)
          .readEnvironment() // scan environment GIT_* variables
          .findGitDir() // scan up the file system tree
          .build();
    } catch (IOException e) {
      throw new MojoExecutionException("Could not initialize repository...", e);
    }

    if (repository == null) {
      throw new MojoExecutionException("Could not create git repository. Are you sure '" + dotGitDirectory + "' is the valid Git root for your project?");
    }

    return repository;
  }
}
