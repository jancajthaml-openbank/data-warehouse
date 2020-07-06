GRANT ALL PRIVILEGES ON DATABASE openbank TO postgres;

\c openbank;

CREATE TABLE tenant
(
  name              VARCHAR(50) NOT NULL,

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

  FOREIGN KEY (tenant) REFERENCES tenant(name),
  PRIMARY KEY (tenant, name)
);

GRANT ALL PRIVILEGES ON TABLE account TO postgres;

CREATE TABLE transfer
(
  tenant            VARCHAR(50) NOT NULL,
  transaction       VARCHAR(100) NOT NULL,
  transfer          VARCHAR(100) NOT NULL,
  status            VARCHAR(10) NOT NULL,
  credit_tenant     VARCHAR(50) NOT NULL,
  credit_name       VARCHAR(50) NOT NULL,
  debit_tenant      VARCHAR(50) NOT NULL,
  debit_name        VARCHAR(50) NOT NULL,
  currency          CHAR(3) NOT NULL,
  amount            NUMERIC NOT NULL,
  value_date        VARCHAR(50) NOT NULL,

  FOREIGN KEY (tenant) REFERENCES tenant(name),
  FOREIGN KEY (credit_tenant, credit_name) REFERENCES account(tenant, name),
  FOREIGN KEY (debit_tenant, debit_name) REFERENCES account(tenant, name),
  PRIMARY KEY (tenant, transaction, transfer)
);

GRANT ALL PRIVILEGES ON TABLE transfer TO postgres;
