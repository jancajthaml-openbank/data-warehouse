import json
import decimal
#import itertools


def load_database():
  with open('database.json', 'r') as fd:
    return json.load(fd)


def get_tenants(database):
  return database["tenants"]


def get_current_account_state(database, tenant, account):
  if not tenant in database['accounts']:
    return {}

  if not account in database['accounts'][tenant]:
    return {}

  balance = decimal.Decimal(0)

  data = database['accounts'][tenant][account]

  changes = [y for x in data["balance_changes"].values() for y in x]

  for change in changes:
    balance += decimal.Decimal(change)

  return {
    "tenant": tenant,
    "name": account,
    "format": data["format"],
    "currency": data["currency"],
    "balance": '{0:f}'.format(balance)
  }


def get_accounts_by_currency(database, tenant, currency):
  if not tenant in database['accounts']:
    return []

  result = []
  for account, data in database['accounts'][tenant].items():
    if data["currency"] != currency:
      continue
    result.append(account)

  return result


def get_accounts_participated_in_transactions_larger_than_amount(database, tenant, amount):
  if not tenant in database['accounts']:
    return []

  amount = decimal.Decimal(amount)

  result = []
  for account, data in database['accounts'][tenant].items():
    changes = [decimal.Decimal(y).copy_abs() for x in data["balance_changes"].values() for y in x]

    for change in changes:
      if change > amount:
        result.append(account)
        break
    else:
      continue

  return result


################################################################################


database = load_database()


################################################################################


print(get_current_account_state(database, 'demo', 'CZK_TYPE_INVESTOR_DEPOSIT'))
print(get_tenants(database))
print(get_accounts_by_currency(database, 'demo', 'CZK'))
print(get_accounts_participated_in_transactions_larger_than_amount(database, 'demo', '100000'))

