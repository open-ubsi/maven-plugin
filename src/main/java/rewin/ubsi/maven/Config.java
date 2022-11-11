package rewin.ubsi.maven;

/** 插件的配置参数 */
public class Config {

    /** 过滤器的参数 */
    public static class Filter {
        public String artifact;             // 依赖项的ArtifactId
        public String className;            // 模块的类名
        public String configJson;           // 模块配置
        public String resourcePath;         // 模块的资源文件路径
        public boolean exclusion = false;   // 是否排除
    }

    /** 微服务的参数 */
    public static class Service extends Filter {
        public String name;                 // 服务名，不能为空
    }

}
