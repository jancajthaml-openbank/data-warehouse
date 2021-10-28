package com.openbank.dwh.model

import scala.math.BigDecimal
import akka.http.scaladsl.model.DateTime
import sangria.validation.ValueCoercionViolation

case object DateTimeCoerceViolation extends ValueCoercionViolation("DateTime value expected")
case object StatusCoerceViolation extends ValueCoercionViolation("Status value expected")
case object NaturalNumberCoerceViolation
    extends ValueCoercionViolation("NaturalNumber value expected")

case class Tenant(
    name: String
)

case class Account(
    tenant: String,
    name: String,
    currency: String,
    format: String,
    balance: Option[BigDecimal]
)

case class AccountBalance(
    tenant: String,
    name: String,
    valueDate: DateTime,
    amount: BigDecimal
)

case class Transfer(
    tenant: String,
    transaction: String,
    transfer: String,
    status: Int,
    creditTenant: String,
    creditAccount: String,
    debitTenant: String,
    debitAccount: String,
    amount: BigDecimal,
    currency: String,
    valueDate: DateTime
)
