package com.advancedtelematic.tuf.reposerver.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.libats.data.ErrorCode
import com.advancedtelematic.libats.http.Errors.RawError


object ErrorCodes {
  val KeysNotReady = ErrorCode("keys_not_ready")
  val RoleKeysNotFound = ErrorCode("role_keys_not_found")
  val TargetNotFound = ErrorCode("target_not_found")
  val EtagNotFound = ErrorCode("etag_not_found")
}

object Errors {
  val KeysNotReady = RawError(ErrorCodes.KeysNotReady, StatusCodes.Locked, "A key generation request exists")
  val RoleKeysNotFound = RawError(ErrorCodes.RoleKeysNotFound, StatusCodes.NotFound, "There are no keys for this repoid/roletype")
  val TargetNotFoundError = RawError(ErrorCodes.TargetNotFound, StatusCodes.NotFound, "TargetNotFound")
  val EtagNotFound = RawError(ErrorCodes.EtagNotFound, StatusCodes.PreconditionRequired, "A targets role already exists, but no etag was sent")
}
