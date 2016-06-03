/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.intellij.build.impl

import com.intellij.util.PathUtilRt
import org.apache.tools.ant.types.Path
import org.apache.tools.ant.util.SplitClassLoader
import org.codehaus.gant.GantBuilder
import org.jetbrains.intellij.build.BuildContext
import org.jetbrains.intellij.build.MacHostProperties

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MacDmgBuilder {
  private final BuildContext buildContext
  private final GantBuilder ant
  private final String artifactsPath
  private final MacHostProperties macHostProperties
  private final String remoteDir

  private MacDmgBuilder(BuildContext buildContext, String remoteDir, MacHostProperties macHostProperties) {
    this.buildContext = buildContext
    ant = buildContext.ant
    artifactsPath = buildContext.paths.artifacts
    this.macHostProperties = macHostProperties
    this.remoteDir = remoteDir
  }

  public static void signAndBuildDmg(BuildContext buildContext, MacHostProperties macHostProperties, String macZipPath) {
    defineTasks(buildContext.ant, "${buildContext.paths.communityHome}/lib")

    String remoteDir = "intellij-builds/${buildContext.fullBuildNumber}"
    if (remoteDir.toLowerCase().endsWith("snapshot")) {
      remoteDir += "-" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace(':', '-')
    }
    def dmgBuilder = new MacDmgBuilder(buildContext, remoteDir, macHostProperties)
    if (buildContext.paths.macJreTarGz != null && new File(buildContext.paths.macJreTarGz).exists()) {
      dmgBuilder.doSignAndBuildDmg(macZipPath, true)
    }
    else {
      buildContext.messages.info("Skipping building Mac OS X distribution with bundled JRE because JRE archive doesn't exist: $buildContext.paths.macJreTarGz")
    }
    if (buildContext.options.buildDmgWithoutBundledJre) {
      dmgBuilder.doSignAndBuildDmg(macZipPath, false)
    }
  }

  private void doSignAndBuildDmg(String macZipPath, boolean bundleJre) {
    def suffix = bundleJre ? "" : "-no-jdk"
    String targetFileName = buildContext.productProperties.archiveName(buildContext.buildNumber) + suffix
    def sitFilePath = "$artifactsPath/${targetFileName}.sit"
    ant.copy(file: macZipPath, tofile: sitFilePath)
    ftpAction("mkdir") {}
    signMacZip(sitFilePath, targetFileName, bundleJre)
    buildDmg(targetFileName)
  }

  private void buildDmg(String targetFileName) {
    buildContext.messages.progress("Building ${targetFileName}.dmg")
    def dmgImageCopy = "$artifactsPath/${buildContext.fullBuildNumber}.png"
    //todo[nik] IDEA have special dmgImage for EAPs
    ant.copy(file: buildContext.productProperties.mac.dmgImagePath, tofile: dmgImageCopy)
    ftpAction("put") {
      ant.fileset(file: dmgImageCopy)
    }
    ant.delete(file: dmgImageCopy)

    ftpAction("put", false, "777") {
      ant.fileset(dir: "${buildContext.paths.communityHome}/build/mac") {
        include(name: "makedmg.sh")
        include(name: "makedmg.pl")
      }
    }

    sshExec("$remoteDir/makedmg.sh ${targetFileName} ${buildContext.fullBuildNumber}")
    ftpAction("get", true, null, 3) {
      ant.fileset(dir: artifactsPath) {
        include(name: "${targetFileName}.dmg")
      }
    }
    ftpAction("delete") {
      ant.fileset() {
        include(name: "**")
      }
    }
    ftpAction("rmdir", true, null, 0, PathUtilRt.getParentPath(remoteDir)) {
      ant.fileset() {
        include(name: "${PathUtilRt.getFileName(remoteDir)}/**")
      }
    }
    def dmgFilePath = "$artifactsPath/${targetFileName}.dmg"
    if (!new File(dmgFilePath).exists()) {
      buildContext.messages.error("Failed to build .dmg file")
    }
    buildContext.notifyArtifactBuilt(dmgFilePath)
  }

  private def signMacZip(String sitFilePath, String targetFileName, boolean bundleJre) {
    buildContext.messages.progress("Signing ${targetFileName}.sit")

    if (bundleJre) {
      ftpAction("put") {
        ant.fileset(file: buildContext.paths.macJreTarGz)
      }
    }

    buildContext.messages.progress("Sending $sitFilePath to ${this.macHostProperties.host}")
    ftpAction("put") {
      ant.fileset(file: sitFilePath)
    }
    ant.delete(file: sitFilePath)
    ftpAction("put", false, "777") {
      ant.fileset(dir: "${buildContext.paths.communityHome}/build/mac") {
        include(name: "signapp.sh")
      }
    }

    String jreFileNameArgument = bundleJre ? " \"${PathUtilRt.getFileName(buildContext.paths.macJreTarGz)}\"" : ""
    sshExec("$remoteDir/signapp.sh ${targetFileName} ${buildContext.fullBuildNumber} ${this.macHostProperties.userName}"
              + " ${this.macHostProperties.password} \"${this.macHostProperties.codesignString}\"$jreFileNameArgument")
    ftpAction("get", true, null, 3) {
      ant.fileset(dir: artifactsPath) {
        include(name: "${targetFileName}.sit")
      }
    }
    if (!new File(sitFilePath).exists()) {
      buildContext.messages.error("Failed to build .sit file")
    }
  }

  static boolean tasksDefined
  static private def defineTasks(AntBuilder ant, String communityLib) {
    if (tasksDefined) return
    tasksDefined = true

    /*
      We need this to ensure that FTP task class isn't loaded by the main Ant classloader, otherwise Ant will try to load FTPClient class
      by the main Ant classloader as well and fail because 'commons-net-*.jar' isn't included to Ant classpath.
      Probably we could call FTPClient directly to avoid this hack.
     */
    def ftpTaskLoaderRef = "FTP_TASK_CLASS_LOADER";
    Path ftpPath = new Path(ant.project)
    ftpPath.createPathElement().setLocation(new File("$communityLib/commons-net-3.3.jar"))
    ftpPath.createPathElement().setLocation(new File("$communityLib/ant/lib/ant-commons-net.jar"))
    ant.project.addReference(ftpTaskLoaderRef, new SplitClassLoader(ant.project.getClass().getClassLoader(), ftpPath, ant.project,
                                                                    ["FTP", "FTPTaskConfig"] as String[]))
    ant.taskdef(name: "ftp", classname: "org.apache.tools.ant.taskdefs.optional.net.FTP", loaderRef: ftpTaskLoaderRef)

    def sshTaskLoaderRef = "SSH_TASK_CLASS_LOADER";
    Path pathSsh = new Path(ant.project)
    pathSsh.createPathElement().setLocation(new File("$communityLib/jsch-0.1.53.jar"))
    pathSsh.createPathElement().setLocation(new File("$communityLib/ant/lib/ant-jsch.jar"))
    ant.project.addReference(sshTaskLoaderRef, new SplitClassLoader(ant.project.getClass().getClassLoader(), pathSsh, ant.project,
                                                                    ["SSHExec", "SSHBase", "LogListener", "SSHUserInfo"] as String[]))
    ant.taskdef(name: "sshexec", classname: "org.apache.tools.ant.taskdefs.optional.ssh.SSHExec", loaderRef: sshTaskLoaderRef)
  }

  private void sshExec(String command) {
    ant.sshexec(
      host: this.macHostProperties.host,
      username: this.macHostProperties.userName,
      password: this.macHostProperties.password,
      trust: "yes",
      command: command
    )
  }

  def ftpAction(String action, boolean binary = true, String chmod = null, int retriesAllowed = 0, String overrideRemoteDir = null, Closure filesets) {
    Map<String, String> args = [
      server        : this.macHostProperties.host,
      userid        : this.macHostProperties.userName,
      password      : this.macHostProperties.password,
      action        : action,
      remotedir     : overrideRemoteDir ?: remoteDir,
      binary        : binary ? "yes" : "no",
      passive       : "yes",
      retriesallowed: "$retriesAllowed"
    ]
    if (chmod != null) {
      args["chmod"] = chmod
    }
    ant.ftp(args, filesets)
  }
}
