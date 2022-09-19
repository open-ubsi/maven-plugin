package rewin.ubsi.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import rewin.ubsi.common.Util;
import rewin.ubsi.container.ServiceContext;

import java.io.File;
import java.util.List;

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
    protected List<Config.Service> services;

    @Parameter(property = "filters")
    protected List<Config.Filter> filters;

    /* 获得打包后的JAR文件名 */
    private String getJarFileName() {
        String fileName = finalName;
        if ( Util.checkEmpty(classifier) != null )
            fileName += "-" + classifier;
        return fileName + ".jar";
    }

    /* 检查文件 */
    private boolean checkFile(File file) {
        return file != null && file.exists() && file.isFile();
    }

    /* 获得打包后的JAR文件 */
    private File getJarFile() {
        Artifact artifact = project.getArtifact();
        File file = artifact.getFile();
        if ( checkFile(file) )
            return file;

        file = new File(outputDirectory, getJarFileName());
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

    // 排除指定的模块
    private void exclusion(List<? extends Config.Filter> list) {
        if ( list != null )
            for ( int i = list.size() - 1; i >= 0; i -- )
                if ( list.get(i).exclusion )
                    list.remove(i);
    }

    protected File jarFile;     // 项目的打包文件

    /** 准备处理 */
    protected void prepare(String goal) throws MojoExecutionException {
        exclusion(services);
        exclusion(filters);
        if ( (services == null || services.isEmpty()) && (filters == null || filters.isEmpty()) )
            throw new MojoExecutionException("valid service or filter not found, please set <configuration> for ubsi-maven-plugin, eg:\n" +
                    "<services>\n" +
                    "  <service>\n" +
                    "    <name>service_name</name>\n" +
                    "    <className>class_name</className>\n" +
                    "    <configJson><![CDATA[service_configuration_as_json]]></configJson>\n" +
                    "    <resourcePath>resource_file_path</resourcePath>\n" +
                    "    <exclusion>true|false</exclusion>\n" +
                    "  </service>\n" +
                    "</services>\n" +
                    "<filters>\n" +
                    "  <filter>\n" +
                    "    <className>class_name</className>\n" +
                    "    <configJson><![CDATA[filter_configuration_as_json]]></configJson>\n" +
                    "    <resourcePath>resource_file_path</resourcePath>\n" +
                    "    <exclusion>true|false</exclusion>\n" +
                    "  </filter>\n" +
                    "</filters>");

        jarFile = getJarFile();
        if ( jarFile == null )
            throw new MojoExecutionException(getJarFileName() + " not found! please retry with \"mvn install ubsi:" + goal + "\".");
    }

    /** 检查JAR包 */
    protected File checkArtifact(Artifact artifact, String goal) throws MojoExecutionException {
        File file = artifact.getFile();
        if ( !checkFile(file) )
            throw new MojoExecutionException(artifact.getArtifactId() + "-" + artifact.getVersion() + " jar file not found! please retry with \"mvn install ubsi:" + goal + "\".");
        return file;
    }

    /** 是否容器的JAR包 */
    protected boolean isSysLib(String groupId, String artifactId) {
        if ( "rewin.ubsi.core".equals(artifactId) )
            return true;
        if ( "ubsi-core-ce".equals(artifactId) )
            return true;
        return ServiceContext.isSysLib(groupId, artifactId, "");
    }
}

