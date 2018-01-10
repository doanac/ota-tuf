package com.advancedtelematic.tuf.cli.repo

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.time.Instant

import io.circe.jawn._
import com.advancedtelematic.libtuf.data.TufDataType.{EdKeyType, EdTufKey, EdTufPrivateKey, SignedPayload, TufKey, TufPrivateKey}
import com.advancedtelematic.tuf.cli.DataType.{AuthConfig, KeyName, RepoName}
import com.advancedtelematic.tuf.cli.{CliSpec, RandomNames}
import cats.syntax.either._
import com.advancedtelematic.libtuf.data.ClientDataType.RootRole
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.tuf.cli.repo.TufRepo.{MissingCredentialsZipFile, RepoAlreadyInitialized}
import io.circe.Json
import io.circe.syntax._

import scala.util.{Failure, Success, Try}

class RepoManagementSpec extends CliSpec {

  lazy val credentialsZip: Path = Paths.get(this.getClass.getResource("/credentials.zip").toURI)
  lazy val credentialsZipNoTargets: Path = Paths.get(this.getClass.getResource("/credentials_no_targets.zip").toURI)
  lazy val credentialsZipNoTufRepo: Path = Paths.get(this.getClass.getResource("/credentials_no_tufrepo.zip").toURI)
  lazy val credentialsZipNoAuth: Path = Paths.get(this.getClass.getResource("/credentials_no_auth.zip").toURI)

  import scala.concurrent.ExecutionContext.Implicits.global

  val fakeRepoUri = Some(new URI("https://test-reposerver"))

  def randomName = RepoName(RandomNames() + "-repo")

  def randomRepoPath = Files.createTempDirectory("tuf-repo").resolve("repo")

  test("credentials.zip without tufrepo.url throws proper error") {
    val repoT = RepoManagement.initialize(randomName, randomRepoPath, credentialsZipNoTufRepo)
    repoT.failed.get shouldBe MissingCredentialsZipFile("tufrepo.url")
  }

  test("throws error for already initialized repos") {
    val path = randomRepoPath

    val repoT = RepoManagement.initialize(randomName, path, credentialsZip)
    repoT shouldBe a[Success[_]]

    val repoF = RepoManagement.initialize(randomName, path, credentialsZip)
    repoF shouldBe Failure(RepoAlreadyInitialized(path))
  }

  test("can initialize repo from ZIP file") {
    val repoT = RepoManagement.initialize(randomName, randomRepoPath, credentialsZip)
    repoT shouldBe a[Success[_]]
  }

  test("can initialize repo from ZIP file specifying custom repo") {
    val repoT = RepoManagement.initialize(randomName, randomRepoPath, credentialsZip, repoUri = Some(new URI("https://ats.com")))
    repoT shouldBe a[Success[_]]
    repoT.flatMap(_.repoServerUri).get.toString shouldBe "https://ats.com"
  }

  test("can initialize repo from ZIP file without targets keys") {
    val repoT = RepoManagement.initialize(randomName, randomRepoPath, credentialsZipNoTargets)
    repoT shouldBe a[Success[_]]
    repoT.get.repoPath.resolve("keys/targets.pub").toFile.exists() shouldBe false
  }

  test("reads targets keys from credentials.zip if present") {
    val repoT = RepoManagement.initialize(randomName, randomRepoPath, credentialsZip)
    repoT shouldBe a[Success[_]]

    val repo = repoT.get

    repo.authConfig.get.get shouldBe a[AuthConfig]
    parseFile(repo.repoPath.resolve("keys/targets.pub").toFile).flatMap(_.as[TufKey]).valueOr(throw _) shouldBe a[EdTufKey]
    parseFile(repo.repoPath.resolve("keys/targets.sec").toFile).flatMap(_.as[TufPrivateKey]).valueOr(throw _) shouldBe a[EdTufPrivateKey]
  }

  test("reads targets root.json from credentials.zip if present") {
    val repoT = RepoManagement.initialize(randomName, randomRepoPath, credentialsZip)
    repoT shouldBe a[Success[_]]

    val repo = repoT.get

    repo.readSignedRole[RootRole].get.signed shouldBe a[RootRole]
  }


  test("export includes root.json") {
    val repo = RepoManagement.initialize(randomName, randomRepoPath, credentialsZip).get
    val tempPath = Files.createTempFile("tuf-repo-spec-export", ".zip")

    repo.genKeys(KeyName("targets"), EdKeyType, 256).get

    val rootRole = SignedPayload(Seq.empty,
      RootRole(Map.empty, Map.empty, 2, expires = Instant.now()))

    repo.writeSignedRole(rootRole).get

    RepoManagement.export(repo, KeyName("targets"), tempPath) shouldBe a[Success[_]]
    val repoFromExported = RepoManagement.initialize(randomName, randomRepoPath, tempPath).get

    repoFromExported.readSignedRole[RootRole].get.asJson shouldBe rootRole.asJson
  }

  test("can export zip file") {
    val repo = RepoManagement.initialize(randomName, randomRepoPath, credentialsZip).get
    repo.genKeys(KeyName("default-key"), EdKeyType, 256)

    val tempPath = Files.createTempFile("tuf-repo-spec-export", ".zip")
    RepoManagement.export(repo, KeyName("default-key"), tempPath) shouldBe Try(())

    // test the exported zip file by creating another repo from it:
    val repoFromExported = RepoManagement.initialize(randomName, randomRepoPath, tempPath).get
    repoFromExported.authConfig.get.map(_.client_id).get shouldBe "8f505046-bf38-4e17-a0bc-8a289bbd1403"
    val server = repoFromExported.treehubConfig.get.ostree.hcursor.downField("server").as[String].valueOr(throw _)
    server shouldBe "https://treehub-pub.gw.staging.atsgarage.com/api/v3"
  }

  test("export uses configured tuf url, not what came in the original file") {
    val repo = RepoManagement.initialize(randomName, randomRepoPath, credentialsZip, repoUri = Some(new URI("https://someotherrepo.com"))).get
    repo.genKeys(KeyName("default-key"), EdKeyType, 256)

    val tempPath = Files.createTempFile("tuf-repo-spec-export", ".zip")
    RepoManagement.export(repo, KeyName("default-key"), tempPath) shouldBe Try(())

    // test the exported zip file by creating another repo from it:
    val repoFromExported = RepoManagement.initialize(randomName, randomRepoPath, tempPath).get
    repoFromExported.repoServerUri.get.toString shouldBe "https://someotherrepo.com"
  }

  test("creates base credentials.zip if one does not exist") {
    val repo = RepoManagement.initialize(randomName, randomRepoPath, credentialsZip).get
    val tempPath = Files.createTempFile("cli-export", ".zip")

    Files.delete(repo.repoPath.resolve("credentials.zip"))

    repo.genKeys(KeyName("targets"), EdKeyType, 256).get

    RepoManagement.export(repo, KeyName("targets"), tempPath) shouldBe a[Success[_]]

    val repoFromExported = RepoManagement.initialize(randomName, randomRepoPath, tempPath).get

    repoFromExported.repoPath.toFile.exists() shouldBe true
  }

  test("can read auth config for an initialized repo") {
    val repoT = RepoManagement.initialize(randomName, randomRepoPath, credentialsZip)

    repoT shouldBe a[Success[_]]

    repoT.get.authConfig.get.get shouldBe a[AuthConfig]
  }


  test("skips auth when no_auth: true") {
    val repoT = RepoManagement.initialize(randomName, randomRepoPath, credentialsZipNoAuth)

    repoT shouldBe a[Success[_]]

    repoT.get.authConfig.get shouldBe None
  }
}
