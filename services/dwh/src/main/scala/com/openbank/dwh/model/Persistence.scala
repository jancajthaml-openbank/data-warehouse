package com.openbank.dwh.model

import scala.math.BigDecimal
import java.time.ZonedDateTime

object Status extends Enumeration {

  val New = Value("new")
  val Accepted = Value("accepted")
  val Rejected = Value("rejected")
  val Committed = Value("committed")
  val Rollbacked = Value("rollbacked")

  def toShort(v: Value): Short = v match {
    case New | Accepted | Rejected => 0
    case Committed => 1
    case Rollbacked => 2
  }

  def fromShort(v: Short): Value = v match {
    case 1 => Committed
    case 2 => Rollbacked
    case _ => New
  }

}

case class Tenant(
  name: String,
  isPristine: Boolean
)

case class Account(
  tenant: String,
  name: String,
  currency: String,
  format: String,
  lastSynchronizedSnapshot: Int,
  lastSynchronizedEvent: Int,
  isPristine: Boolean
)

case class AccountSnapshot(
  tenant: String,
  account: String,
  version: Int
)

case class AccountEvent(
  tenant: String,
  account: String,
  status: Status.Value,
  transaction: String,
  snapshotVersion: Int,
  version: Int
)

case class Transfer(
  tenant: String,
  transaction: String,
  transfer: String,
  creditTenant: String,
  creditAccount: String,
  debitTenant: String,
  debitAccount: String,
  amount: BigDecimal,
  currency: String,
  valueDate: ZonedDateTime,
  isPristine: Boolean
)
