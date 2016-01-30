package com.jakewharton.sdkmanager.internal

import com.android.sdklib.repository.FullRevision
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.StopExecutionException

import java.util.regex.Pattern

import static com.android.SdkConstants.FD_BUILD_TOOLS
import static com.android.SdkConstants.FD_EXTRAS
import static com.android.SdkConstants.FD_M2_REPOSITORY
import static com.android.SdkConstants.FD_PLATFORMS
import static com.android.SdkConstants.FD_ADDONS
import static com.android.SdkConstants.FD_PLATFORM_TOOLS
import static com.android.SdkConstants.FD_SYSTEM_IMAGES
import static com.android.SdkConstants.FD_TOOLS

class PackageResolver {
  static void resolve(Project project, File sdk) {
    new PackageResolver(project, sdk, new AndroidCommand.Real(sdk, new System.Real())).resolve()
  }

  static boolean folderExists(File folder) {
    return folder.exists() && folder.list().length != 0
  }

  static final String GOOGLE_API_PREFIX = "Google Inc.:Google APIs:"
  static final String GOOGLE_GDK_PREFIX = "Google Inc.:Glass Development Kit Preview:"

  final Logger log = Logging.getLogger PackageResolver
  final Project project
  final File sdk
  final File buildToolsDir
  final File platformToolsDir
  final File platformsDir
  final File addonsDir
  final File androidRepositoryDir
  final File googleRepositoryDir
  final AndroidCommand androidCommand

  PackageResolver(Project project, File sdk, AndroidCommand androidCommand) {
    this.sdk = sdk
    this.project = project
    this.androidCommand = androidCommand

    buildToolsDir = new File(sdk, FD_BUILD_TOOLS)
    platformToolsDir = new File(sdk, FD_PLATFORM_TOOLS)
    platformsDir = new File(sdk, FD_PLATFORMS)
    addonsDir = new File(sdk, FD_ADDONS)

    def extrasDir = new File(sdk, FD_EXTRAS)
    def androidExtrasDir = new File(extrasDir, 'android')
    androidRepositoryDir = new File(androidExtrasDir, FD_M2_REPOSITORY)
    def googleExtrasDir = new File(extrasDir, 'google')
    googleRepositoryDir = new File(googleExtrasDir, FD_M2_REPOSITORY)
  }

  def resolve() {
    resolveSdkTools()
    resolveBuildTools()
    resolvePlatformTools()
    resolveCompileVersion()
    resolveSupportLibraryRepository()
    resolvePlayServiceRepository()
    resolveEmulator()
  }

  def resolveSdkTools() {
    def minSdkToolsVersion = project.sdkManager.minSdkToolsVersion
    if (minSdkToolsVersion == null) {
      log.debug 'No minSdkToolsVersion defined'
      return
    }
    log.debug "Found minSdkToolsVersion: $minSdkToolsVersion"

    def sdkToolsDir = new File(sdk, FD_TOOLS)
    def sdkToolsVersion = getPackageRevision(sdkToolsDir)
    log.debug "Found sdkToolsVersion: $sdkToolsVersion"

    def minSdkToolsRevision = FullRevision.parseRevision(minSdkToolsVersion)
    def sdkToolsRevision = FullRevision.parseRevision(sdkToolsVersion)
    def needsDownload = sdkToolsRevision < minSdkToolsRevision

    if (!needsDownload) {
      log.debug "SDK tools are up to date."
      return
    }

    def sdkToolsPackage = "tools"
    def currentSdkToolsInfo = androidCommand.list sdkToolsPackage
    if (currentSdkToolsInfo == null || currentSdkToolsInfo.isEmpty()) {
      throw new StopExecutionException('Could not get the current SDK tools revision.')
    }

    def matcher = Pattern.compile("revision\\ (.+)").matcher(currentSdkToolsInfo)
    if (!matcher.find()) {
      throw new StopExecutionException("Could not find the current SDK tools revision." +
              " currentSdkToolsInfo: $currentSdkToolsInfo")
    }

    def currentSdkToolsVersion = matcher.group(1)
    log.debug "currentSdkToolsVersion: $currentSdkToolsVersion"

    def currentSdkToolsRevision = FullRevision.parseRevision(currentSdkToolsVersion)
    if (currentSdkToolsRevision < minSdkToolsRevision) {
      throw new StopExecutionException("Currently available SDK tools version($currentSdkToolsVersion)" +
              " is smaller than defined in minSdkToolsVersion: $minSdkToolsVersion")
    }

    log.lifecycle "SDK tools $sdkToolsVersion outdated. Downloading update..."

    def code = androidCommand.update sdkToolsPackage
    if (code != 0) {
      throw new StopExecutionException("SDK tools download failed with code $code.")
    }
  }

  def resolveBuildTools() {
    def buildToolsRevision = project.android.buildToolsRevision
    log.debug "Build tools version: $buildToolsRevision"

    def buildToolsRevisionDir = new File(buildToolsDir, buildToolsRevision.toString())
    if (folderExists(buildToolsRevisionDir)) {
      log.debug 'Build tools found!'
      return
    }

    log.lifecycle "Build tools $buildToolsRevision missing. Downloading..."

    def code = androidCommand.update "build-tools-$buildToolsRevision"
    if (code != 0) {
      throw new StopExecutionException("Build tools download failed with code $code.")
    }
  }

  def resolvePlatformTools() {
//    if (folderExists(platformToolsDir)) {
//      log.debug 'Platform tools found!'
//      return
//    }
//
//    log.lifecycle "Platform tools missing. Downloading..."

    log.lifecycle "Platform tools force update..."

    def code = androidCommand.update "platform-tools"
    if (code != 0) {
      throw new StopExecutionException("Platform tools download failed with code $code.")
    }
  }

  def resolveCompileVersion() {
    String compileVersion = project.android.compileSdkVersion
    log.debug "Compile API version: $compileVersion"

    if (compileVersion.startsWith(GOOGLE_API_PREFIX)) {
      // The google SDK requires the base android SDK as a prerequisite, but
      // the SDK manager won't follow dependencies automatically.
      def baseVersion = compileVersion.replace(GOOGLE_API_PREFIX, "android-")
      installIfMissing(platformsDir, baseVersion)
      def addonVersion = compileVersion.replace(GOOGLE_API_PREFIX, "addon-google_apis-google-")
      installIfMissing(addonsDir, addonVersion);
    } else if (compileVersion.startsWith(GOOGLE_GDK_PREFIX)) {
      def gdkVersion = compileVersion.replace(GOOGLE_GDK_PREFIX, "addon-google_gdk-google-")
      installIfMissing(platformsDir, gdkVersion);
    } else {
      installIfMissing(platformsDir, compileVersion);
    }
  }

  def installIfMissing(baseDir, version) {
    def existingDir = new File(baseDir, version)
    if (folderExists(existingDir)) {
      log.debug "Compilation API $version found!"
      return
    }

    log.lifecycle "Compilation API $version missing. Downloading..."

    def code = androidCommand.update version
    if (code != 0) {
      throw new StopExecutionException("Compilation API $version download failed with code $code.")
    }
  }

  def resolveSupportLibraryRepository() {
    def supportDeps = findDependenciesStartingWith 'com.android.support'

    if (supportDeps.isEmpty()) {
      log.debug 'No support library dependency found.'
      return
    }

    log.debug "Found support library dependencies: $supportDeps"

    project.repositories.maven {
      url = androidRepositoryDir
    }

    def needsDownload = false;
    if (!folderExists(androidRepositoryDir)) {
      needsDownload = true
      log.lifecycle 'Support library repository missing. Downloading...'
    } else if (!dependenciesAvailable(supportDeps)) {
      needsDownload = true
      log.lifecycle 'Support library repository outdated. Downloading update...'
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
      log.debug 'No Google Play Services dependency found.'
      return
    }

    log.debug "Found Google Play Services dependencies: $playServicesDeps"

    project.repositories {
      maven {
        url = androidRepositoryDir
      }
      maven {
        url = googleRepositoryDir
      }
    }

    def needsDownload = false;
    if (!folderExists(googleRepositoryDir)) {
      needsDownload = true
      log.lifecycle 'Google Play Services repository missing. Downloading...'
    } else if (!dependenciesAvailable(playServicesDeps)) {
      needsDownload = true
      log.lifecycle 'Google Play Services repository outdated. Downloading update...'
    }

    if (needsDownload) {
      def code = androidCommand.update 'extra-google-m2repository'
      if (code != 0) {
        throw new StopExecutionException(
            "Google Play Services repository download failed with code $code.")
      }
    }
  }

  def resolveEmulator() {
    def emulatorVersion = project.sdkManager.emulatorVersion
    if (emulatorVersion == null) {
      log.debug 'No emulator defined'
      return
    }

    def emulatorArchitecture = project.sdkManager.emulatorArchitecture
    if (emulatorArchitecture == null) {
      emulatorArchitecture = 'armeabi-v7a'
      log.debug 'No architecture specified, defaulting to armeabi-v7a'
    }

    log.debug "Found emulator: $emulatorVersion $emulatorArchitecture"

    def emulatorDir = new File(sdk, FD_SYSTEM_IMAGES + "/$emulatorVersion/$emulatorArchitecture")
    def alternativeEmulatorDir = new File(sdk, FD_SYSTEM_IMAGES + "/$emulatorVersion/default/$emulatorArchitecture")
    def emulatorPackage = "sys-img-$emulatorArchitecture-$emulatorVersion"
    def needsDownload = false
    if (!folderExists(emulatorDir) && !folderExists(alternativeEmulatorDir)) {
      needsDownload = true
      log.lifecycle "Emulator $emulatorVersion $emulatorArchitecture missing. Downloading..."
    } else {
      def emulatorRevision = getPackageRevision(emulatorDir, alternativeEmulatorDir)

      def currentEmulatorInfo = androidCommand.list emulatorPackage
      if (currentEmulatorInfo == null || currentEmulatorInfo.isEmpty()) {
        throw new StopExecutionException('Could not get the current emulator revision for ' +
            emulatorPackage)
      }

      def matcher = Pattern.compile("Revision\\ ([0-9]+)").matcher(currentEmulatorInfo)
      if (!matcher.find()) {
        throw new StopExecutionException('Could not find the current emulator revision for ' +
            emulatorPackage)
      }

      if ((emulatorRevision as int) < (matcher.group(1) as int)) {
        needsDownload = true
        log.lifecycle "Emulator $emulatorVersion $emulatorArchitecture outdated. Downloading update..."
      }
    }

    if (needsDownload) {
      def code = androidCommand.update emulatorPackage
      if (code != 0) {
        throw new StopExecutionException(
            "Emulator $emulatorVersion $emulatorArchitecture download failed with code $code.")
      }
    }
  }

  def getPackageRevision(File[] packageDirs) {
    def propertiesFileName = 'source.properties'
    def propertiesFile = packageDirs.collect { new File(it, propertiesFileName) }.find { it.canRead() }
    if (propertiesFile == null) {
      throw new StopExecutionException("Could not read '$propertiesFileName' from: $packageDirs")
    }

    def properties = new Properties()
    properties.load(new FileInputStream(propertiesFile))
    def revision = properties.getProperty('Pkg.Revision')
    if (revision == null) {
      throw new StopExecutionException("Could not read the revision from: ${propertiesFile.absolutePath}")
    }

    log.debug "Found revision for package ${propertiesFile.absolutePath}: $revision"
    return revision
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

  def findDependenciesStartingWith(String prefix) {
    def deps = []
    for (Configuration configuration : project.configurations) {
      for (Dependency dependency : configuration.dependencies) {
        if (dependency.group != null && dependency.group.startsWith(prefix)) {
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
