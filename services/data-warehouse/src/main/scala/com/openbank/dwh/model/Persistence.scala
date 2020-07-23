package com.openbank.dwh.model

import scala.math.BigDecimal
import java.time.ZonedDateTime


case class PersistentTenant(
  name: String
)

case class PersistentAccount(
  tenant: String,
  name: String,
  currency: String,
  format: String,
  lastSynchronizedSnapshot: Int,
  lastSynchronizedEvent: Int
)

case class PersistentAccountSnapshot(
  tenant: String,
  account: String,
  version: Int
)

case class PersistentAccountEvent(
  tenant: String,
  account: String,
  status: Short,
  transaction: String,
  snapshotVersion: Int,
  version: Int
)

case class PersistentTransfer(
  tenant: String,
  transaction: String,
  transfer: String,
  creditTenant: String,
  creditAccount: String,
  debitTenant: String,
  debitAccount: String,
  amount: BigDecimal,
  currency: String,
  valueDate: ZonedDateTime
)
