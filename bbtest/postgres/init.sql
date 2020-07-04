CREATE DATABASE openbank;

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

  PRIMARY KEY (tenant, name)
);

GRANT ALL PRIVILEGES ON TABLE account TO postgres;
