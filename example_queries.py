import os
import json
import decimal
import datetime


def load_database():
  if not os.path.isfile('database.json'):
    return {}

  with open('database.json', 'r') as fd:
    return json.load(fd)


def get_tenants(database):
  return database["tenants"]


def get_accounts(database, tenant):
  if not tenant in database['accounts']:
    return []
  return list(database["accounts"][tenant].keys())


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


def get_accounts_by_format(database, tenant, format):
  if not tenant in database['accounts']:
    return []

  result = []
  for account, data in database['accounts'][tenant].items():
    if data["format"] != format:
      continue
    result.append(account)

  return result


def get_account_balance_in_time(database, tenant, account):
  if not tenant in database['accounts']:
    return {}

  if not account in database['accounts'][tenant]:
    return {}


  data = database['accounts'][tenant][account]

  changes = []

  for valueDate, subset in data["balance_changes"].items():
    amount = sum([decimal.Decimal(x) for x in subset])
    changes.append((datetime.datetime.strptime(valueDate, "%Y-%m-%dT%H:%M:%SZ"), amount))

  changes = sorted(changes, key=lambda change: change[0])

  balance = decimal.Decimal(0)

  result = {}

  for change in changes:
    key = change[0].isoformat() + "Z"
    next_balance = decimal.Decimal(change[1]) + balance
    if balance != next_balance:
      result[key] = "0" if next_balance.is_zero() else '{0:f}'.format(next_balance)
      balance = next_balance

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


def print_and_run(*args):
  print()
  print('{}({})'.format(args[0],', '.join(['database']+["'{}'".format(x) for x in list(args[2:])])))
  result = globals()[args[0]](*args[1:])
  print(json.dumps(result, indent=2, sort_keys=True))


database = load_database()


################################################################################

print_and_run('get_current_account_state', database, 'demo', 'CZK_TYPE_INVESTOR_DEPOSIT')
print_and_run('get_tenants', database)
print_and_run('get_accounts', database, 'demo')
print_and_run('get_accounts_by_currency', database, 'demo', 'CZK')
print_and_run('get_accounts_by_format', database, 'demo', 'IBAN')
print_and_run('get_accounts_participated_in_transactions_larger_than_amount', database, 'demo', '100000')
print_and_run('get_account_balance_in_time', database, 'demo', 'CZK_TYPE_INVESTOR_DEPOSIT')
