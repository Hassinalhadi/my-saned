package io.github.libxposed.api;

public interface XposedModuleInterface {
    interface ModuleLoadedParam {
        String getProcessName();
        boolean isSystemServer();
    }
    interface PackageLoadedParam {
        String getPackageName();
        ClassLoader getDefaultClassLoader();
        ClassLoader getClassLoader();
    }
}
