package com.jakewharton.sdkmanager.internal

import com.android.sdklib.repository.FullRevision
import org.apache.log4j.Logger
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.StopExecutionException

import static com.android.SdkConstants.FD_BUILD_TOOLS
import static com.android.SdkConstants.FD_EXTRAS
import static com.android.SdkConstants.FD_M2_REPOSITORY
import static com.android.SdkConstants.FD_PLATFORMS

class PackageResolver {
  static void resolve(Project project, File sdk) {
    new PackageResolver(project, sdk, new AndroidCommand.Real(sdk)).resolve()
  }

  final Logger log = Logger.getLogger PackageResolver
  final Project project
  final File sdk
  final File buildToolsDir
  final File platformsDir
  final File androidRepositoryDir
  final File googleRepositoryDir
  final AndroidCommand androidCommand

  PackageResolver(Project project, File sdk, AndroidCommand androidCommand) {
    this.sdk = sdk
    this.project = project
    this.androidCommand = androidCommand

    buildToolsDir = new File(sdk, FD_BUILD_TOOLS)
    platformsDir = new File(sdk, FD_PLATFORMS)

    def extrasDir = new File(sdk, FD_EXTRAS)
    def androidExtrasDir = new File(extrasDir, 'android')
    androidRepositoryDir = new File(androidExtrasDir, FD_M2_REPOSITORY)
    def googleExtrasDir = new File(extrasDir, 'google')
    googleRepositoryDir = new File(googleExtrasDir, FD_M2_REPOSITORY)
  }

  def resolve() {
    resolveBuildTools()
    resolveCompileVersion()
    resolveSupportLibraryRepository()
    resolvePlayServiceRepository()
  }

  def resolveBuildTools() {
    FullRevision buildToolsRevision = project.android.buildToolsRevision
    log.debug "Build tools version: $buildToolsRevision"

    def buildToolsRevisionDir = new File(buildToolsDir, buildToolsRevision.toString())
    if (buildToolsRevisionDir.exists()) {
      log.debug 'Build tools found!'
      return
    }

    log.info "Build tools $buildToolsRevision missing. Downloading from SDK manager."

    def code = androidCommand.update "build-tools-$buildToolsRevision"
    if (code != 0) {
      throw new StopExecutionException("Build tools download failed with code $code.")
    }
  }

  def resolveCompileVersion() {
    String compileVersion = project.android.compileSdkVersion
    log.debug "Compile API version: $compileVersion"

    def compileVersionDir = new File(platformsDir, compileVersion)
    if (compileVersionDir.exists()) {
      log.debug 'Compilation API found!'
      return
    }

    log.info "Compilation API $compileVersion missing. Downloading from SDK manager."

    def code = androidCommand.update compileVersion
    if (code != 0) {
      throw new StopExecutionException("Compilation API download failed with code $code.")
    }
  }

  def resolveSupportLibraryRepository() {
    def supportLibraryDeps = findDependenciesWithGroup 'com.android.support'
    if (supportLibraryDeps.isEmpty()) {
      log.debug 'No support library dependency found.'
      return
    }

    log.debug "Found support library dependency: $supportLibraryDeps"

    def needsDownload = false;
    if (!androidRepositoryDir.exists()) {
      needsDownload = true
      log.info 'Support library repository missing. Downloading from SDK manager.'

      // Add future repository to the project since the main plugin skips it when missing.
      project.repositories.maven {
        url = androidRepositoryDir
      }
    } else if (!dependenciesAvailable(supportLibraryDeps)) {
      needsDownload = true
      log.info 'Support library repository outdated. Downloading update from SDK manager.'
    }

    if (needsDownload) {
      def code = androidCommand.update 'extra-android-m2repository'
      if (code != 0) {
        throw new StopExecutionException("Support repository download failed with code $code.")
      }
    }
  }

  def resolvePlayServiceRepository() {
    def playServicesDeps = findDependenciesWithGroup 'com.google.android.gms'
    if (playServicesDeps.isEmpty()) {
      log.debug 'No Play services dependency found.'
      return
    }

    log.debug "Found Play services dependency: $playServicesDeps"

    def needsDownload = false;
    if (!googleRepositoryDir.exists()) {
      needsDownload = true
      log.info 'Play services repository missing. Downloading from SDK manager.'

      // Add future repository to the project since the main plugin skips it when missing.
      project.repositories.maven {
        url = googleRepositoryDir
      }
    } else if (!dependenciesAvailable(playServicesDeps)) {
      needsDownload = true
      log.info 'Play services repository outdated. Downloading update from SDK manager.'
    }

    if (needsDownload) {
      def code = androidCommand.update 'extra-google-m2repository'
      if (code != 0) {
        throw new StopExecutionException(
            "Play services repository download failed with code $code.")
      }
    }
  }

  def findDependenciesWithGroup(String group) {
    def deps = []
    for (Configuration configuration : project.configurations) {
      for (Dependency dependency : configuration.dependencies) {
        if (group.equals(dependency.group)) {
          deps.add dependency
        }
      }
    }
    return deps
  }

  def dependenciesAvailable(def deps) {
    try {
      project.configurations.detachedConfiguration(deps as Dependency[]).files
      return true
    } catch (Exception ignored) {
      return false
    }
  }
}
