package io.github.libxposed.api;

public abstract class XposedModule {
    public XposedModule() {}
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {}
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {}
}
