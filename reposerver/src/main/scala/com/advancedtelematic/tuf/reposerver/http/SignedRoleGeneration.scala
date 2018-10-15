package com.advancedtelematic.tuf.reposerver.http

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.http.scaladsl.util.FastFuture
import cats.syntax.either._
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType._
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf.data.TufDataType.{JsonSignedPayload, RepoId, RoleType, TargetFilename}
import com.advancedtelematic.tuf.reposerver.data.RepositoryDataType.{SignedRole, TargetItem}
import com.advancedtelematic.tuf.reposerver.db.FilenameCommentRepository
import com.advancedtelematic.tuf.reposerver.db.SignedRoleRepository.SignedRoleNotFound
import com.advancedtelematic.tuf.reposerver.db.{SignedRoleRepository, SignedRoleRepositorySupport, TargetItemRepositorySupport}
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import slick.jdbc.MySQLProfile.api._

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import cats.syntax.either._
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import org.slf4j.LoggerFactory

// TODO:SM How to not use SinedRole all the time, if we need the signed version ?
// Create separate SignedRole[T] with the same attributes but not linked to db ?
// Do we already have that?
// THERE IS A MIX OF EVERYTHING

class RoleSigner(repoId: RepoId, keyserverClient: KeyserverClient)(implicit ec: ExecutionContext) {
  def apply[T : Encoder](role: T)(implicit tufRole: TufRole[T]): Future[SignedRole] = {
    keyserverClient.sign(repoId, role.roleType, role.asJson).map { signedRole =>
      SignedRole.withChecksum(repoId, role.roleType, signedRole, role.version, role.expires)
    }
  }
}

class RepoRoleRefresh(signFn: RoleSigner)(implicit val db: Database, val ec: ExecutionContext) extends SignedRoleRepositorySupport {
  val roleRefresh = new RoleRefresh(signFn)

  private def findExisting[T](repoId: RepoId)(implicit tufRole: TufRole[T]): Future[SignedRole] = {
    signedRoleRepo.find(repoId, tufRole.roleType)
  }

  private def commitRefresh(refreshedRole: SignedRole, dependencies: List[SignedRole]): Future[SignedRole] = async {
    await(signedRoleRepo.persistAll(refreshedRole :: dependencies))
    refreshedRole
  }

  def refreshTargets(repoId: RepoId): Future[SignedRole] = async {
    val existingTargets = await(findExisting[TargetsRole](repoId))
    val existingSnapshots = await(findExisting[SnapshotRole](repoId))
    val existingTimestamps = await(findExisting[TimestampRole](repoId))
    val (newTargets, dependencies) = await(roleRefresh.refreshTargets(existingTargets, existingTimestamps, existingSnapshots))
    await(commitRefresh(newTargets, dependencies))
  }

  def refreshSnapshots(repoId: RepoId): Future[SignedRole] = async {
    val existingTargets = await(findExisting[TargetsRole](repoId))
    val existingSnapshots = await(findExisting[SnapshotRole](repoId))
    val existingTimestamps = await(findExisting[TimestampRole](repoId))
    val (newSnapshots, dependencies) = await(roleRefresh.refreshSnapshots(existingSnapshots, existingTimestamps, existingTargets))
    await(commitRefresh(newSnapshots, dependencies))
  }

  def refreshTimestamp(repoId: RepoId): Future[SignedRole] = async {
    val existingTimestamp = await(findExisting[TimestampRole](repoId))
    val existingSnapshots = await(findExisting[SnapshotRole](repoId))
    val newTimestamp = await(roleRefresh.refreshTimestamps(existingTimestamp, existingSnapshots))
    await(commitRefresh(newTimestamp, List.empty))
  }
}


class RoleRefresh(signFn: RoleSigner)(implicit ec: ExecutionContext) {

  def refreshTargets(existingTargets: SignedRole, existingTimestamps: SignedRole, existingSnapshots: SignedRole): Future[(SignedRole, List[SignedRole])] = async {
    val newTargetsRole = refreshRole[TargetsRole](existingTargets)
    val signedTargets = await(signFn(newTargetsRole))
    val (newSnapshots, dependencies) = await(refreshSnapshots(existingSnapshots, existingTimestamps, signedTargets))

    (signedTargets, newSnapshots :: dependencies)
  }

  // TODO:SM Extract generate to other site, use same code,
  // TODO:SM this gets weird, wtf is up with this return type?
  def refreshSnapshots(existingSnapshots: SignedRole, existingTimestamps: SignedRole, newTargets: SignedRole): Future[(SignedRole, List[SignedRole])] = async {
    val parsed = existingSnapshots.content.signed.as[SnapshotRole].valueOr(throw _)
    val refreshed = refreshRole[SnapshotRole](existingSnapshots)

    val newMeta: Map[MetaPath, MetaItem] = parsed.meta + newTargets.asMetaRole
    val newSnapshot = SnapshotRole(newMeta, refreshed.expires, refreshed.version)

    val signedSnapshot = await(signFn(newSnapshot))

    val timestampRole = await(refreshTimestamps(existingTimestamps, signedSnapshot))

    (signedSnapshot, List(timestampRole))
  }

  def refreshTimestamps(existingTimestamps: SignedRole, newSnapshots: SignedRole): Future[SignedRole] = async {
    val refreshed = refreshRole[TimestampRole](existingTimestamps)
    val newTimestamp = TimestampRole(Map(newSnapshots.asMetaRole), refreshed.expires, refreshed.version)
    val signedTimestamps = await(signFn(newTimestamp))
    signedTimestamps
  }

  private def refreshRole[T : Decoder](existing: SignedRole)(implicit tufRole: TufRole[T]): T = {
    val versionedRole = existing.content.signed.as[T].valueOr(throw _)
    val nextExpires = Instant.now.plus(1, ChronoUnit.DAYS)
    tufRole.refreshRole(versionedRole, _ + 1, nextExpires)
  }
}


class SignedRoleGeneration(keyserverClient: KeyserverClient)
                          (implicit val db: Database, val ec: ExecutionContext) extends SignedRoleRepositorySupport {

  private val log = LoggerFactory.getLogger(this.getClass)

  val targetRoleGeneration = new TargetRoleGeneration(keyserverClient)

  def regenerateAllSignedRoles(repoId: RepoId): Future[JsonSignedPayload] = {
    async {
      await(fetchRootRole(repoId))

      val expireAt = defaultExpire

      val targetVersion = await(nextVersion(repoId, RoleType.TARGETS))
      val targetRole = await(targetRoleGeneration.generate(repoId, expireAt, targetVersion))
      val signedTarget = await(signRole(repoId, targetRole))

      val dependent = await(regenerateSignedDependent(repoId, signedTarget, expireAt))

      await(signedRoleRepo.persistAll(signedTarget :: dependent))

      signedTarget.content
    }
  }

  def regenerateSignedDependent(repoId: RepoId, targetRole: SignedRole, expireAt: Instant): Future[List[SignedRole]] = async {
    val signedRoot = await(fetchRootRole(repoId))

    val snapshotVersion = await(nextVersion(repoId, RoleType.SNAPSHOT))
    val snapshotRole = genSnapshotRole(signedRoot, targetRole, expireAt, snapshotVersion)
    val signedSnapshot = await(signRole(repoId, snapshotRole))

    val timestampVersion = await(nextVersion(repoId, RoleType.TIMESTAMP))
    val timestampRole = genTimestampRole(signedSnapshot, expireAt, timestampVersion)
    val signedTimestamp = await(signRole(repoId, timestampRole))

    List(signedSnapshot, signedTimestamp)
  }

  def addTargetItem(targetItem: TargetItem): Future[JsonSignedPayload] =
    targetRoleGeneration.addTargetItem(targetItem).flatMap(_ ⇒ regenerateAllSignedRoles(targetItem.repoId))

  def deleteTargetItem(repoId: RepoId, filename: TargetFilename): Future[Unit] = for {
    _ <- ensureTargetsCanBeSigned(repoId)
    _ <- targetRoleGeneration.deleteTargetItem(repoId, filename)
    _ <- regenerateAllSignedRoles(repoId)
  } yield ()


  private def signRole[T : Encoder : TufRole](repoId: RepoId, role: T): Future[SignedRole] = {
    new RoleSigner(repoId, keyserverClient).apply(role)
  }

  private def ensureTargetsCanBeSigned(repoId: RepoId): Future[SignedRole] = async {
    val rootRole = await(signedRoleRepo.find(repoId, RoleType.TARGETS)).content.signed.as[TargetsRole].valueOr(throw _)
    await(signRole(repoId, rootRole))
  }

  private def fetchRootRole(repoId: RepoId): Future[SignedRole] =
    keyserverClient.fetchRootRole(repoId).map { rootRole =>
      SignedRole.withChecksum(repoId, RoleType.ROOT, rootRole.asJsonSignedPayload, rootRole.signed.version, rootRole.signed.expires)
    }

  private def findFreshRole[T](repoId: RepoId)(refreshFn: => Future[SignedRole])(implicit tufRole: TufRole[T]): Future[SignedRole] = {
    signedRoleRepo.find(repoId, tufRole.roleType).flatMap { role =>
      if (role.expireAt.isBefore(Instant.now.plus(1, ChronoUnit.HOURS))) {
        refreshFn.recoverWith {
          case KeyserverClient.RoleKeyNotFound =>
            log.info(s"Could not update ${tufRole.roleType} (for $repoId) because the keys are missing, returning expired version")
            FastFuture.successful(role)
        }
      } else
        FastFuture.successful(role)

    }.recoverWith { // TODO:SM Move this out
      case SignedRoleRepository.SignedRoleNotFound =>
        generateAndCacheRole(repoId, tufRole.roleType)
    }
  }

  private def generateAndCacheRole(repoId: RepoId, roleType: RoleType): Future[SignedRole] = {
    regenerateAllSignedRoles(repoId)
      .recoverWith { case err => log.warn("Could not generate signed roles", err) ; FastFuture.failed(SignedRoleNotFound) }
      .flatMap(_ => signedRoleRepo.find(repoId, roleType))
  }


  def findRole(repoId: RepoId, roleType: RoleType): Future[SignedRole] = {
    lazy val refresher = new RepoRoleRefresh(new RoleSigner(repoId, keyserverClient))

    roleType match {
      case RoleType.ROOT =>
        fetchRootRole(repoId)
      case RoleType.TARGETS =>
        findFreshRole[TargetsRole](repoId)(refresher.refreshTargets(repoId))
      case RoleType.SNAPSHOT =>
        findFreshRole[SnapshotRole](repoId)(refresher.refreshSnapshots(repoId))
      case RoleType.TIMESTAMP =>
        findFreshRole[TimestampRole](repoId)(refresher.refreshTimestamp(repoId))
      case r =>
        throw new IllegalArgumentException(s"Unknown role type $r")
    }
  }

  private def nextVersion(repoId: RepoId, roleType: RoleType): Future[Int] =
    signedRoleRepo
      .find(repoId, roleType)
      .map(_.version + 1)
      .recover {
        case SignedRoleNotFound => 1
      }

  private def genSnapshotRole(root: SignedRole, target: SignedRole, expireAt: Instant, version: Int): SnapshotRole = {
    val meta = List(root.asMetaRole, target.asMetaRole).toMap
    SnapshotRole(meta, expireAt, version)
  }

  private def genTimestampRole(snapshotRole: SignedRole, expireAt: Instant, version: Int): TimestampRole = {
    val meta = Map(snapshotRole.asMetaRole)
    TimestampRole(meta, expireAt, version)
  }

  private def defaultExpire: Instant =
    Instant.now().plus(31, ChronoUnit.DAYS)
}

protected class TargetRoleGeneration(roleSigningClient: KeyserverClient)
                          (implicit val db: Database, val ec: ExecutionContext)
  extends TargetItemRepositorySupport with FilenameCommentRepository.Support {

  def addTargetItem(targetItem: TargetItem): Future[TargetItem] =
    targetItemRepo.persist(targetItem)

  def deleteTargetItem(repoId: RepoId, filename: TargetFilename): Future[Unit] =
    targetItemRepo.deleteItemAndComments(filenameCommentRepo)(repoId, filename)

  def generate(repoId: RepoId, expireAt: Instant, version: Int): Future[TargetsRole] = {
    targetItemRepo.findFor(repoId).map { targetItems =>
      val targets = targetItems.map { item =>
        val hashes = Map(item.checksum.method -> item.checksum.hash)
        item.filename -> ClientTargetItem(hashes, item.length, item.custom.map(_.asJson))
      }.toMap

      TargetsRole(expireAt, targets, version)
    }
  }
}

