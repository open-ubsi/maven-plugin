package rewin.ubsi.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import rewin.ubsi.common.Util;
import rewin.ubsi.container.ServiceContext;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maven-Project的基础信息、依赖以及UBSI插件的配置
 */
public abstract class AbstractUbsiMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter
    private String classifier;

    @Parameter( defaultValue = "${project.build.directory}", required = true, readonly = true )
    private File outputDirectory;

    @Parameter( defaultValue = "${project.build.finalName}", required = true, readonly = true )
    private String finalName;

    @Parameter( defaultValue = "${localRepository}", required = true, readonly = true )
    private ArtifactRepository localRepository;

    @Parameter(property = "services")
    protected List<Config.Service> services;    // POM中的<services>配置项

    @Parameter(property = "filters")
    private List<Config.Service> filters;       // POM中的<filters>配置项

    private File jarFile;     // 项目的打包文件

    /* 需要处理的微服务/过滤器，可以通过 mvn ubsi:??? -Dclass={xxx} 来指定 */
    @Parameter( property = "class" )
    private String className;

    private boolean useProjectJar = false;      // 是否需要项目打包JAR
    private boolean useDependencyJar = false;   // 是否需要依赖项的JAR

    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession session;

    @Component( hint = "default" )
    private DependencyGraphBuilder dependencyGraphBuilder;

    private DependencyNode rootNode;    // 依赖树的根节点

    /* 获得项目打包后的JAR文件名 */
    private String getJarFileName() {
        String fileName = finalName;
        if ( Util.checkEmpty(classifier) != null )
            fileName += "-" + classifier;
        return fileName + ".jar";
    }

    /** 检查文件 */
    protected boolean checkFile(File file) {
        return file != null && file.exists() && file.isFile();
    }

    /* 获得JAR文件 */
    private File getJarFile(Artifact artifact) {
        File file = artifact.getFile();
        if ( checkFile(file) )
            return file;
        artifact = localRepository.find(artifact);
        if ( artifact != null ) {
            file = artifact.getFile();
            if ( checkFile(file) )
                return file;
        }
        return null;
    }

    /* 获得项目打包后的JAR文件 */
    private File getProjectJarFile() {
        File file = new File(outputDirectory, getJarFileName());
        if ( checkFile(file) )
            return file;
        return getJarFile(project.getArtifact());
    }

    // 排除模块
    private void exclusion() throws MojoExecutionException {
        if ( services == null )
            services = new ArrayList<>();
        for ( Config.Service srv : services ) {
            srv.name = Util.checkEmpty(srv.name);
            if ( srv.name == null )
                throw new MojoExecutionException("invalid <name> of service");
        }
        if ( filters != null ) {
            for ( Config.Service srv : filters ) {
                srv.name = Util.checkEmpty(srv.name);
                if ( srv.name != null )
                    throw new MojoExecutionException("invalid <name> of filters");
            }
            services.addAll(filters);
            filters.clear();
        }
        className = Util.checkEmpty(className);
        for ( int i = services.size() - 1; i >= 0; i --) {
            Config.Service srv = services.get(i);
            srv.className = Util.checkEmpty(srv.className);
            if ( srv.className == null )
                throw new MojoExecutionException("invalid <className> of service/filter");
            if ( className != null && !className.equals(srv.className) )
                services.remove(i);
            else if ( className == null && srv.exclusion )
                services.remove(i);
            else {
                srv.artifact = Util.checkEmpty(srv.artifact);
                if ( srv.artifact != null )
                    useDependencyJar = true;
                else
                    useProjectJar = true;
            }
        }
        if ( services.isEmpty() )
            throw new MojoExecutionException("valid service or filter not found, please set <configuration> for ubsi-maven-plugin, eg:\n" +
                    "  <services>\n" +
                    "    <service>\n" +
                    "      <artifact>dependency_artifactId</artifact>\n" +
                    "      <name>service_name</name>\n" +
                    "      <className>class_name</className>\n" +
                    "      <configJson><![CDATA[service_configuration_as_json]]></configJson>\n" +
                    "      <resourcePath>resource_file_path</resourcePath>\n" +
                    "      <exclusion>true|false</exclusion>\n" +
                    "    </service>\n" +
                    "  </services>\n" +
                    "  <filters>\n" +
                    "    <filter>\n" +
                    "      <artifact>dependency_artifactId</artifact>\n" +
                    "      <className>class_name</className>\n" +
                    "      <configJson><![CDATA[filter_configuration_as_json]]></configJson>\n" +
                    "      <resourcePath>resource_file_path</resourcePath>\n" +
                    "      <exclusion>true|false</exclusion>\n" +
                    "    </filter>\n" +
                    "  </filters>\n" +
                    "\n" +
                    "You can use \"-Dclass=xxx\" parameter on \"mvn\" command line to specify one service/filter");
    }

    /** 预处理 */
    protected void prepare() throws MojoExecutionException {
        exclusion();

        if ( useProjectJar )
            jarFile = getProjectJarFile();
        if ( useDependencyJar ) {
            ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setProject(project);
            try {
                rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
            } catch (Exception e) {
                throw new MojoExecutionException("build project dependency-tree error", e);
            }
            if ( rootNode == null )
                throw new MojoExecutionException("build project dependency-tree error");
        }
    }

    /** 获得JAR包名字 */
    protected String getArtifactName(Artifact artifact) {
        return artifact.getGroupId() + ":" +
                artifact.getArtifactId() + ":" +
                artifact.getVersion();
    }

    /** 检查JAR包 */
    protected File checkArtifact(Artifact artifact) throws MojoExecutionException {
        File file;
        if ( project.getArtifact().getDependencyConflictId().equals(artifact.getDependencyConflictId()) )
            file = jarFile;
        else
            file = getJarFile(artifact);
        if ( !checkFile(file) )
            throw new MojoExecutionException(getArtifactName(artifact) + " jar-file not found");
        return file;
    }

    /** 是否UBSI核心JAR包 */
    protected boolean isUbsiCore(String artifactId) {
        if ( "rewin.ubsi.core".equals(artifactId) )
            return true;
        if ( "ubsi-core-ce".equals(artifactId) )
            return true;
        return false;
    }

    /** 是否容器的JAR包 */
    protected boolean isSysLib(String groupId, String artifactId) {
        if ( isUbsiCore(artifactId) )
            return true;
        return ServiceContext.isSysLib(groupId, artifactId, "");
    }

    /** 获得服务的依赖 */
    protected Set<Artifact> getDependency(Config.Service srv) throws MojoExecutionException {
        if ( srv.artifact == null )
            return project.getArtifacts();
        DependencyNode node = findNode(rootNode, srv.artifact);
        if ( node == null )
            throw new MojoExecutionException("artifact not found for \"" + srv.className + "\"");
        return findDependency(node, new HashSet<>());
    }

    /** 获得服务的JAR包 */
    protected Artifact getArtifact(Config.Service srv) throws MojoExecutionException {
        if ( srv.artifact == null )
            return project.getArtifact();
        DependencyNode node = findNode(rootNode, srv.artifact);
        if ( node == null )
            throw new MojoExecutionException("artifact not found for \"" + srv.className + "\"");
        return node.getArtifact();
    }

    // 在依赖树中查找指定的JAR包
    private DependencyNode findNode(DependencyNode node, String artifact) {
        if ( node.getArtifact().getArtifactId().equals(artifact) )
            return node;
        for ( DependencyNode child : node.getChildren() ) {
            DependencyNode res = findNode(child, artifact);
            if ( res != null )
                return res;
        }
        return null;
    }
    // 在依赖树中添加所有子节点
    private Set<Artifact> findDependency(DependencyNode node, Set<Artifact> res) {
        if ( res == null )
            res = new HashSet<>();
        for ( DependencyNode child : node.getChildren() ) {
            res.add(child.getArtifact());
            findDependency(child, res);
        }
        return res;
    }
}
