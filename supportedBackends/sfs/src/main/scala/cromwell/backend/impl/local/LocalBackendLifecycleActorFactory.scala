package cromwell.backend.impl.local

import com.typesafe.config.ConfigFactory
import cromwell.backend.BackendConfigurationDescriptor
import cromwell.backend.impl.local.LocalBackendLifecycleActorFactory._
import cromwell.backend.impl.sfs.config.ConfigBackendLifecycleActorFactory
import cromwell.core.{ErrorOr, WorkflowOptions}
import wdl4s.values.WdlValue

@deprecated("Remains until travis/centaur is updated to stop using this class.", "SFS")
class LocalBackendLifecycleActorFactory(configurationDescriptor: BackendConfigurationDescriptor)
  extends ConfigBackendLifecycleActorFactory(reconfig(configurationDescriptor)) {
}


@deprecated("Remains until travis/centaur is updated to stop using this class.", "SFS")
object LocalBackendLifecycleActorFactory {
  def reconfig(configurationDescriptor: BackendConfigurationDescriptor): BackendConfigurationDescriptor = {
    val backendConfig = configurationDescriptor.backendConfig
    val globalConfig = configurationDescriptor.globalConfig
    BackendConfigurationDescriptor(backendConfig.withFallback(localConfig), globalConfig)
  }

  val localConfig = ConfigFactory.parseString(
    """
      |run-in-background = true
      |runtime-attributes = "String? docker"
      |submit = "/bin/bash ${script}"
      |submit-docker = "docker run --rm -v ${cwd}:${docker_cwd} -i ${docker} /bin/bash < ${script}"
    """.stripMargin)
}
