package rewin.ubsi.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import rewin.ubsi.common.Codec;
import rewin.ubsi.common.Util;
import rewin.ubsi.consumer.Context;
import rewin.ubsi.container.Bootstrap;
import rewin.ubsi.container.Info;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 部署微服务/过滤器到指定的容器：mvn ubsi:deploy -Dcontainer={host#port} -Dclass={微服务/过滤器的className}
 */
@Mojo(
        name = "deploy",
        requiresDependencyResolution = ResolutionScope.RUNTIME
)
@Execute(phase = LifecyclePhase.PACKAGE)
public class DeployMojo extends AbstractUbsiMojo {

    /* 目标容器配置，可以使用 -Dcontainer={xxx} 来指定 */
    @Parameter( property = "container", defaultValue = "localhost#7112")
    private String container;

    String host = "localhost";
    int port = Bootstrap.DEFAULT_PORT;

    public void execute() throws MojoExecutionException {
        prepare();

        container = Util.checkEmpty(container);
        if ( container != null ) {
            int index = container.indexOf("#");
            if ( index >= 0 && index < container.length() - 1 )
                try {
                    port = Integer.parseInt(container.substring(index + 1));
                } catch (Exception e) {
                    throw new MojoExecutionException("invalid container's address");
                }
            if ( index > 0 )
                host = container.substring(0, index);
            else if ( index < 0 && !container.isEmpty() )
                host = container;
        }

        getLog().info("");
        getLog().info("====== start deploy, container=\"" + host + "#" + port + "\" ======");
        try {
            Context.startup(".");
            for ( Config.Service srv : services ) {
                getLog().info("");
                getLog().info(">>> deploy " + (srv.name != null ? "service" : "filter") + " \"" + (srv.name != null ? srv.name : srv.className) + "\" >>>");
                deploy(srv);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("deploy error, " + e);
        } finally {
            try { Context.shutdown(); } catch (Exception e) {}
        }
        getLog().info("");
    }

    /* 部署一个微服务/过滤器 */
    void deploy(Config.Service srv) throws Exception {
        Artifact mainArtifact = getArtifact(srv);
        // 检查是否需要卸载
        Context context = Context.request("", "getRuntime", null);
        Info.Runtime info = Codec.toType(context.direct(host, port), Info.Runtime.class);
        boolean has = false;
        if ( srv.name == null && info.filters != null ) {
            for ( Info.FRuntime fr : info.filters ) {
                if ( fr.class_name.equals(srv.className) ) {
                    has = true;
                    break;
                }
            }
        }
        if ( srv.name != null && info.services.containsKey(srv.name) )
            has = true;
        String mname = srv.name == null ? srv.className : srv.name;
        if ( has ) {
            getLog().info("\"" + mname + "\" founded, uninstall ...");
            Integer res = (Integer)Context.request("", "uninstall", mname).direct(host, port);
            if ( res != null && res != 0 )
                throw new Exception(getArtifactName(mainArtifact) + " jar-file still in use by other service/filter");
        }

        // 安装依赖的JAR包
        List<Object[]> depends = new ArrayList<>();
        for ( Artifact artifact : getDependency(srv) ) {
            String gid = artifact.getGroupId();
            String aid = artifact.getArtifactId();
            if ( isSysLib(gid, aid) )
                continue;
            String ver = artifact.getVersion();
            depends.add(new Object[] { gid, aid, ver });
            installJar(gid, aid, ver, checkArtifact(artifact), null);
        }
        // 安装主JAR包
        String gid = mainArtifact.getGroupId();
        String aid = mainArtifact.getArtifactId();
        String ver = mainArtifact.getVersion();
        installJar(gid, aid, ver, checkArtifact(mainArtifact), depends.toArray());

        uploadResource(mname, srv.resourcePath);
        getLog().info("register \"" + mname + "\" ...");
        try {
            Context.request("", "install", srv.name, srv.className, new Object[]{gid, aid, ver}).direct(host, port);
        } catch (Exception e) {
            try {
                Context.request("", "unregisterJar", gid, aid, ver).direct(host, port);
            } catch (Exception ee) {
            }
            throw e;
        }

        setConfig(mname, srv.configJson);
        getLog().info("start \"" + mname + "\" ...");
        boolean res_start = (Boolean)Context.request("", "setStatus", mname, 1).direct(host, port);
        if ( !res_start )
            getLog().warn("start \"" + mname + "\" failure!");
        else
            getLog().info("start \"" + mname + "\" ok, deploy over.");
    }

    /* 安装一个JAR包 */
    void installJar(String gid, String aid, String ver, File file, Object[] depends) throws Exception {
        int installed = (Integer)Context.request("", "hasJar", gid, aid, ver).direct(host, port);
        if ( installed > 0 )
            return;
        // 上传JAR包
        String fname = file.getName();
        getLog().info("install " + fname + " ...");
        byte[] buf = null;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long left = raf.length();
            for ( long offset = 0; offset < left; offset += 1024*1024 ) {
                if ( left - offset <= 1024*1024 )
                    buf = new byte[(int)(left - offset)];
                else if ( buf == null )
                    buf = new byte[1024*1024];
                raf.seek(offset);
                raf.readFully(buf);
                Context.request("", "uploadJar", fname, offset, buf).direct(host, port);
            }
        }
        // 注册JAR包
        if ( installed < 0 )
            Context.request("", "registerJar", gid, aid, ver, fname, depends).direct(host, port);
    }

    /* 上传资源文件 */
    void uploadResource(String mname, String path) throws Exception {
        path = Util.checkEmpty(path);
        if ( path == null )
            return;
        File dir = new File(path);
        if ( !dir.exists() || !dir.isDirectory() )
            throw new Exception("invalid resource path \"" + path + "\".");
        getLog().info("upload resource files ...");
        uploadDir(dir, "", mname);
    }

    /* 上传资源目录 */
    void uploadDir(File dir, String path, String mname) throws Exception {
        for ( File file : dir.listFiles() ) {
            if ( file.isDirectory() )
                uploadDir(file, (path.isEmpty() ? "" : (path + "/")) + file.getName(), mname);
            if ( !file.isFile() )
                continue;
            byte[] buf = null;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long left = raf.length();
                for ( long offset = 0; offset < left; offset += 1024*1024 ) {
                    if ( left - offset <= 1024*1024 )
                        buf = new byte[(int)(left - offset)];
                    else if ( buf == null )
                        buf = new byte[1024*1024];
                    raf.seek(offset);
                    raf.readFully(buf);
                    Context.request("", "putResourceFile", mname,
                            path, file.getName(), offset, buf).direct(host, port);
                }
            }
        }
    }

    /* 设置配置参数 */
    void setConfig(String mname, String config) throws Exception {
        config = Util.checkEmpty(config);
        if ( config == null )
            return;
        getLog().info("set configuration ...");
        Context.request("", "setConfig", mname, config).direct(host, port);
    }

}
