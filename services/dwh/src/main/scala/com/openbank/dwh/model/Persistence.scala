package com.openbank.dwh.model

import scala.math.BigDecimal


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
  status: Int,  // FIXME enum
  transaction: String,
  snapshotVersion: Int,
  version: Int
)

case class Transfer(
  tenant: String,
  transaction: String,
  transfer: String,
  status: String,  // FIXME enum
  creditTenant: String,
  creditAccount: String,
  debitTenant: String,
  debitAccount: String,
  amount: BigDecimal,
  currency: String,
  valueDate: String,
  isPristine: Boolean
)
