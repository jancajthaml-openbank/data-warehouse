GRANT ALL PRIVILEGES ON DATABASE openbank TO postgres;

\c openbank;

CREATE TABLE tenant
(
  name              VARCHAR(50) NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  PRIMARY KEY (name)
);

GRANT ALL PRIVILEGES ON TABLE tenant TO postgres;

CREATE TABLE account
(
  tenant            VARCHAR(50) NOT NULL,
  name              VARCHAR(50) NOT NULL,
  format            VARCHAR(50),
  currency          CHAR(3),
  last_syn_snapshot INTEGER,
  last_syn_event    INTEGER,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  FOREIGN KEY (tenant) REFERENCES tenant(name),
  PRIMARY KEY (tenant, name)
);

GRANT ALL PRIVILEGES ON TABLE account TO postgres;

CREATE TABLE transfer
(
  tenant            VARCHAR(50) NOT NULL,
  transaction       VARCHAR(100) NOT NULL,
  transfer          VARCHAR(100) NOT NULL,
  credit_tenant     VARCHAR(50) NOT NULL,
  credit_name       VARCHAR(50) NOT NULL,
  debit_tenant      VARCHAR(50) NOT NULL,
  debit_name        VARCHAR(50) NOT NULL,
  currency          CHAR(3) NOT NULL,
  amount            NUMERIC NOT NULL,
  value_date        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  FOREIGN KEY (tenant) REFERENCES tenant(name),
  FOREIGN KEY (credit_tenant, credit_name) REFERENCES account(tenant, name),
  FOREIGN KEY (debit_tenant, debit_name) REFERENCES account(tenant, name),
  PRIMARY KEY (tenant, transaction, transfer)
);

GRANT ALL PRIVILEGES ON TABLE transfer TO postgres;

CREATE VIEW account_balance_change AS (
  SELECT
    account.tenant,
    account.name,
    date_trunc('day', transfer.value_date) as value_date,
    SUM(transfer.amount) as amount
  FROM account
  INNER JOIN transfer
  ON
  (
    (account.tenant = transfer.credit_tenant AND account.name = transfer.credit_name) OR
    (account.tenant = transfer.debit_tenant AND account.name = transfer.debit_name)
  )
  GROUP BY
    (account.tenant, account.name, transfer.value_date)
);
