package jenkins.plugins.castecho;

import com.cloudbees.plugins.credentials.common.IdCredentials;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.scm.SCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import static jenkins.plugins.castecho.CastEchoInstallation.fromName;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class CastEchoBuilder extends Builder implements SimpleBuildStep {
    
    final static int MAX_DISPLAYED_DETAILS = 10;
    
    static protected class GitMetaData {
        String repositoryUrl;
        String branch;
        String lastCommit;
        
        public GitMetaData(GitSCM git, Run build) throws NullPointerException  {
            org.eclipse.jgit.transport.RemoteConfig repo = git.getRepositories().get(0);
            this.repositoryUrl  = repo.getURIs().get(0).toString();
            this.branch         = git.getBranches().get(0).toString();
            this.lastCommit     = "";
            BuildData buildData = git.getBuildData(build);
            if (buildData != null)  {
                Revision revision = buildData.getLastBuiltRevision();
                if (revision != null)
                    this.lastCommit = revision.getSha1String();
                }
            }
        }
    
    private final String installationName;
    private final String sourcePath;
    private final String applicationName;
    private String qualityGate = DescriptorImpl.defaultQualityGate;
    private String logPath = DescriptorImpl.defaultLogPath;
    private String outputPath = DescriptorImpl.defaultOutputPath;
    private boolean displayLog = DescriptorImpl.defaultDisplayLog;

    @DataBoundConstructor
    public CastEchoBuilder(@Nonnull String installationName, @Nonnull String sourcePath, @Nonnull String applicationName)  {
        this.installationName   = installationName;
        this.sourcePath         = sourcePath.trim();
        this.applicationName    = applicationName.trim();
        }
    
    @DataBoundSetter
    public void setQualityGate(@Nonnull String qualityGate)  {
        this.qualityGate = qualityGate.trim();
        }

    @DataBoundSetter
    public void setLogPath(@Nonnull String logPath)  {
        this.logPath = logPath.trim();
        }

    @DataBoundSetter
    public void setOutputPath(@Nonnull String outputPath)  {
        this.outputPath = outputPath.trim();
        }

    @DataBoundSetter
    public void setDisplayLog(boolean displayLog)  {
        this.displayLog = displayLog;
        }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
        }
    
    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();
        EnvVars env;
        if (run instanceof AbstractBuild)  {
            env = run.getEnvironment(listener);
            env.overrideAll(((AbstractBuild<?,?>) run).getBuildVariables());
            }
        else
            env = new EnvVars();
        Computer computer       = workspace.toComputer();
        Node node               = (computer == null) ? null : computer.getNode();
        String installationName = env.expand(this.installationName);
        String sourcePath       = env.expand(this.sourcePath);
        String logPath          = env.expand(this.logPath);
        String outputPath       = env.expand(this.outputPath);
        
        if (launcher.isUnix())
            throw new AbortException("CastEcho plugin can only work under Windows!");
        if (CastEchoInstallation.fromName(installationName) == null)
            throw new AbortException(String.format("CastEcho plugin configuration \"%s\" no found!", installationName));
        FilePath sourceFile = workspace.child(sourcePath);
        if (!sourceFile.exists())
            throw new AbortException(String.format("Source folder for CastEcho analysis not found at %s", sourcePath));
        FilePath executableFile = CastEchoInstallation.getExecutableFile(installationName, node, env, listener);
        if ((executableFile == null) || !executableFile.exists())
            throw new AbortException("CastEcho executable not found!");
        FilePath logFile    = workspace.child(logPath);
        FilePath outputFile = workspace.child(outputPath);
        if (logFile.exists() || outputFile.exists())  {
            if (displayLog)
                logger.println("Removing previous CastEcho results...");
            logFile.deleteContents();
            outputFile.deleteContents();
            }

        runAnalysis(run, workspace, launcher, listener, env, executableFile);
        }
    
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
        }
    
    @Extension @Symbol("castecho")
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public static final String defaultQualityGate   = "critical";
        public static final String defaultLogPath       = "CastEchoResult\\log";
        public static final String defaultOutputPath    = "CastEchoResult\\output";
        public static final boolean defaultDisplayLog   = true;
        
        @Override
        public String getDisplayName() {
            return Messages.CastEcho_DisplayName();
            }
        
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
            }
        
        public CastEchoInstallation[] getInstallations() {
            return CastEchoInstallation.list();
            }
        }
    
    public String getSourcePath()       { return sourcePath; }
    public String getApplicationName()  { return applicationName; }
    public String getQualityGate()      { return qualityGate; }
    public String getLogPath()          { return logPath; }
    public String getOutputPath()       { return outputPath; }
    public boolean isDisplayLog()       { return displayLog; }
    
    protected @CheckForNull IdCredentials getDashboardCredential(Run run)  {
        CastEchoInstallation installation = fromName(installationName);
        return (installation == null) ? null : installation.getDashboardCredential(run);
        }
    
    protected @CheckForNull GitMetaData getGitMetaData(Run<?, ?> run)  {
        GitSCM git = getFirstGitSCM(run.getParent());
        return ((git == null) ? null : new GitMetaData(git, run));
        }
    
    protected @CheckForNull GitSCM getFirstGitSCM(Job job)  {
        Collection<? extends SCM> scms = getSCMs(job);
        if (scms.isEmpty())
            return null;
        return scms.stream().filter(scm -> scm instanceof GitSCM).map(scm -> (GitSCM)scm).findFirst().get();
        }
    
    protected @Nonnull Collection<? extends SCM> getSCMs(Job job)  {
		Collection<? extends SCM> scms;
        if (job instanceof hudson.model.Project)
            scms = ((hudson.model.Project)job).getSCMs();
        else  if (job instanceof AbstractProject)  {
            List<SCM> scms2 = new ArrayList<>();
            scms2.add(((AbstractProject)job).getScm());
            scms = scms2;
            }
        else  if (job instanceof WorkflowJob)  {
            scms = ((WorkflowJob)job).getSCMs();
            }
        else
            scms = new ArrayList<>();
        return scms;
        }
    
    protected void runAnalysis(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener, EnvVars env, FilePath executableFile) throws IOException, InterruptedException  {
        PrintStream logger = listener.getLogger();
        String sourcePath       = env.expand(this.sourcePath);
        String applicationName  = env.expand(this.applicationName);
        String qualityGate      = env.expand(this.qualityGate);
        String logPath          = env.expand(this.logPath);
        String outputPath       = env.expand(this.outputPath);

        logger.printf("Starting CastEcho analysis of sources located into %s...%n", sourcePath);
        String executablePath   = executableFile.getRemote();
        FilePath sourceFile     = workspace.child(sourcePath);
        FilePath logFile        = workspace.child(logPath);
        FilePath outputFile     = workspace.child(outputPath);
        GitMetaData gitMetadata = null;
        try {
            gitMetadata = getGitMetaData(run);
            }
        catch (NullPointerException e)  { }
        if (gitMetadata != null)  {
            logger.printf("(GIT repo %s found for description.)%n", gitMetadata.repositoryUrl);
//            logger.println(gitMetadata.branch);
//            logger.println(gitMetadata.lastCommit);
            }
        logger.println("");

        ProcStarter ps = launcher.launch().pwd(workspace);
        List<String> params = new ArrayList<>();
        params.add(executablePath);
        params.add("-a");
        params.add(applicationName);
        for (String tag : qualityGate.split(","))  {
            params.add("-q");
            params.add(tag.trim());
            }
        params.add("-s");
        params.add(sourceFile.getRemote());
        params.add("-o");
        params.add(outputFile.getRemote());
        params.add("-t");
        params.add(logFile.getRemote());
        ps./*quiet(true).*/cmds(params);
        if (displayLog)
            ps.stdout(listener.getLogger());
        ps.stderr(listener.getLogger());
        int status = ps.join();
        logger.println("");
        if ( (status == 0) || (status == 2) )  {
            logger.println("CastEcho analysis has finished.");
            FilePath summaryFile = outputFile.child("summary.json");
            CastEchoResult result = summaryFile.act(new CastEchoResult.Collect());
            if (displayLog)  {
                logger.printf("Checked rules       : %d%n", result.checkedRuleCount);
                logger.printf("File count          : %d%n", result.fileCount);
                logger.printf("Issue count         : %d%n%n", result.issueCount);
                FilePath detailFile = outputFile.child("DetailsForCastEchoQG.json");
                CastEchoResultDetail detail = detailFile.act(new CastEchoResultDetail.Collect());
                if (detail == null)
                    logger.printf("Error reading violation detail!%n");
                else  {
                    logger.printf("List of violations:%n-------------------%n");
                    for (CastEchoResultDetail.ViolationType violationType : detail.violationTypes)  {
                        logger.printf("%s (%d):%n", violationType.name, violationType.count);
                        violationType.details.sort( (CastEchoResultDetail.Detail a, CastEchoResultDetail.Detail b) -> (int)(b.count - a.count) );
                        for (int i=0; i<Math.min(MAX_DISPLAYED_DETAILS, violationType.details.size()); i++)  {
                            CastEchoResultDetail.Detail detail2 = violationType.details.get(i);
                            logger.printf("     %s (%d)%n", detail2.name, detail2.count);
                            }
                        if (violationType.details.size() >= 10)
                            logger.printf("     ...%n");
                    }
                    logger.println();
                    }
                }
            if (status == 2)
                throw new AbortException("Too much errors found by CastEcho analysis!");
            }
        else
            throw new AbortException("CastEcho analysis has failed!");
        }
    
    }
