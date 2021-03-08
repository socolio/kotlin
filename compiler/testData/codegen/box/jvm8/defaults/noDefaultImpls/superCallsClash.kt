// !JVM_DEFAULT_MODE: all
// TARGET_BACKEND: JVM
// JVM_TARGET: 1.8
// WITH_RUNTIME

interface ModuleConfiguratorWithSettings  {
    fun getConfiguratorSettings(): String = "fail"
}

interface ModuleConfiguratorWithTests : ModuleConfiguratorWithSettings {
    override fun getConfiguratorSettings() = "K"
}

interface AndroidModuleConfigurator :
    ModuleConfiguratorWithSettings {
    override fun getConfiguratorSettings() = "O"
}


class AndroidTargetConfigurator :
    AndroidModuleConfigurator,
    ModuleConfiguratorWithTests {

    override fun getConfiguratorSettings(): String =
        { super<AndroidModuleConfigurator>.getConfiguratorSettings() + super<ModuleConfiguratorWithTests>.getConfiguratorSettings()}()
}

fun box(): String {
    return AndroidTargetConfigurator().getConfiguratorSettings()
}