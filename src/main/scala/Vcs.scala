package sbtrelease

import sbt._
import java.io.File

trait Vcs {
  val commandName: String

  val baseDir: File

  def cmd(args: Any*): ProcessBuilder
  def status: ProcessBuilder
  def currentHash: String
  def add(files: String*): ProcessBuilder
  def commit(message: String): ProcessBuilder
  def existsTag(name: String): Boolean
  def checkRemote(remote: String): ProcessBuilder
  def tag(name: String, comment: String, force: Boolean = false): ProcessBuilder
  def hasUpstream: Boolean
  def trackingRemote: String
  def isBehindRemote: Boolean
  def pushChanges: ProcessBuilder
  def currentBranch: String

  protected def executableName(command: String) = {
    val maybeOsName = sys.props.get("os.name").map(_.toLowerCase)
    val maybeIsWindows = maybeOsName.filter(_.contains("windows"))
    maybeIsWindows.map(_ => command+".exe").getOrElse(command)
  }

  protected val devnull = new ProcessLogger {
    def info(s: => String) {}
    def error(s: => String) {}
    def buffer[T](f: => T): T = f
  }
}

object Vcs {
  def detect(dir: File): Option[Vcs] = {
    Stream(Git, Mercurial, Subversion).flatMap(comp => comp.isRepository(dir).map(comp.mkVcs(_))).headOption
  }
}

trait GitLike extends Vcs {
  private lazy val exec = executableName(commandName)

  def cmd(args: Any*): ProcessBuilder = Process(exec +: args.map(_.toString), baseDir)

  def add(files: String*) = cmd(("add" +: files): _*)

  def commit(message: String) = cmd("commit", "-m", message)
}

trait VcsCompanion {
  protected val markerDirectory: String

  def isRepository(dir: File): Option[File] =
    if (new File(dir, markerDirectory).isDirectory) Some(dir)
    else Option(dir.getParentFile).flatMap(isRepository)

  def mkVcs(baseDir: File): Vcs
}


object Mercurial extends VcsCompanion {
  protected val markerDirectory = ".hg"

  def mkVcs(baseDir: File) = new Mercurial(baseDir)
}

class Mercurial(val baseDir: File) extends Vcs with GitLike {
  val commandName = "hg"

  def status = cmd("status")

  def currentHash = (cmd("identify", "-i") !!) trim

  def existsTag(name: String) = (cmd("tags") !!).linesIterator.exists(_.endsWith(" "+name))

  def tag(name: String, comment: String, force: Boolean) = cmd("tag", if(force) "-f" else "", "-m", comment, name)

  def hasUpstream = cmd("paths", "default") ! devnull == 0

  def trackingRemote = "default"

  def isBehindRemote = cmd("incoming", "-b", ".", "-q") ! devnull == 0

  def pushChanges = cmd("push", "-b", ".")

  def currentBranch = (cmd("branch") !!) trim

  // FIXME: This is utterly bogus, but I cannot find a good way...
  def checkRemote(remote: String) = cmd("id", "-n")
}

object Git extends VcsCompanion {
  protected val markerDirectory = ".git"

  def mkVcs(baseDir: File) = new Git(baseDir)
}

class Git(val baseDir: File) extends Vcs with GitLike {
  val commandName = "git"


  private lazy val trackingBranchCmd = cmd("config", "branch.%s.merge" format currentBranch)
  private def trackingBranch: String = (trackingBranchCmd !!).trim.stripPrefix("refs/heads/")

  private lazy val trackingRemoteCmd: ProcessBuilder = cmd("config", "branch.%s.remote" format currentBranch)
  def trackingRemote: String = (trackingRemoteCmd !!) trim

  def hasUpstream = trackingRemoteCmd ! devnull == 0 && trackingBranchCmd ! devnull == 0

  def currentBranch =  (cmd("symbolic-ref", "HEAD") !!).trim.stripPrefix("refs/heads/")

  def currentHash = revParse(currentBranch)

  private def revParse(name: String) = (cmd("rev-parse", name) !!) trim

  def isBehindRemote = (cmd("rev-list", "%s..%s/%s".format(currentBranch, trackingRemote, trackingBranch)) !! devnull).trim.nonEmpty

  def tag(name: String, comment: String, force: Boolean = false) = cmd("tag", "-a", name, "-m", comment, if(force) "-f" else "")

  def existsTag(name: String) = cmd("show-ref", "--quiet", "--tags", "--verify", "refs/tags/" + name) ! devnull == 0

  def checkRemote(remote: String) = fetch(remote)

  def fetch(remote: String) = cmd("fetch", remote)

  def status = cmd("status", "--porcelain")

  def pushChanges = pushCurrentBranch #&& pushTags

  private def pushCurrentBranch = {
    val localBranch = currentBranch
    cmd("push", trackingRemote, "%s:%s" format (localBranch, trackingBranch))
  }

  private def pushTags = cmd("push", "--tags", trackingRemote)
}

object Subversion extends VcsCompanion {
  override def mkVcs(baseDir: File): Vcs = new Subversion(baseDir)

  override protected val markerDirectory: String = ".svn"
}

class Subversion(val baseDir: File) extends Vcs {
  override val commandName = "svn"
  private lazy val exec = executableName(commandName)

  var addedFiles:Set[String] = Set()

  override def cmd(args: Any*): ProcessBuilder = Process(exec +: args.map(_.toString), baseDir)

  override def add(files: String*) = {
    val filesThatAreNotAddedYet = files.filter(f => addedFiles.contains(f))
    if(!filesThatAreNotAddedYet.isEmpty) {      
      addedFiles ++= filesThatAreNotAddedYet
      cmd(("add" +: filesThatAreNotAddedYet): _*)
    } else noop
  }

  override def commit(message: String) = cmd("commit", "-m", message)

  override def currentBranch: String = "trunk" //TODO get it from "svn info"

  override def pushChanges: ProcessBuilder = commit("push changes")

  override def isBehindRemote: Boolean = false

  override def trackingRemote: String = ""

  override def hasUpstream: Boolean = true //TODO check if versions.sbt already exists?

  override def tag(name: String, comment: String, force: Boolean): ProcessBuilder =
    cmd("copy", getWorkingDirSvnUrl, getRepoRoot + "tags/" + name, "-m", comment)

  private def getWorkingDirSvnUrl:String = {
    val svnInfo = cmd("info").!!
    val urlStartIdx = svnInfo.indexOf("URL: ") + 5
    svnInfo.substring(urlStartIdx, svnInfo.indexOf('\n', urlStartIdx)-1)
  }

  private def getRepoRoot:String = {
    val svnPart = List(
      getWorkingDirSvnUrl.indexOf("/trunk"),
      getWorkingDirSvnUrl.indexOf("/branches"),
      getWorkingDirSvnUrl.indexOf("/tags")
    ).filter(_ >= 0).head
    getWorkingDirSvnUrl.substring(0, svnPart + 1)
  }

  override def checkRemote(remote: String): ProcessBuilder = status

  override def existsTag(name: String): Boolean = false //TODO check if tag exists

  override def currentHash: String = ""

  override def status: ProcessBuilder = cmd("status", "-q")

  def noop:ProcessBuilder = status
}
