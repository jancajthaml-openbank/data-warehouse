import json
import decimal
import datetime

from secondary_storage import SecondaryPersistence


def get_tenants(database):
  if not "tenants" in database.data:
    return list()
  return list(database.data["tenants"])


def get_accounts(database, tenant):
  if not "accounts" in database.data:
    return list()
  if not tenant in database.data['accounts']:
    return list()
  return list(database.data["accounts"][tenant].keys())


def get_current_account_state(database, tenant, account):
  if not "accounts" in database.data:
    return dict()
  if not tenant in database.data['accounts']:
    return dict()
  if not account in database.data['accounts'][tenant]:
    return dict()

  balance = decimal.Decimal(0)
  chunk = database.data['accounts'][tenant][account]

  changes = [y for x in chunk["balance_changes"].values() for y in x]

  for change in changes:
    balance += decimal.Decimal(change)

  return {
    "tenant": tenant,
    "name": account,
    "format": chunk["format"],
    "currency": chunk["currency"],
    "balance": '{0:f}'.format(balance)
  }


def get_accounts_by_currency(database, tenant, currency):
  if not "accounts" in database.data:
    return list()
  if not tenant in database.data['accounts']:
    return list()

  result = list()
  for account, chunk in database.data['accounts'][tenant].items():
    if chunk["currency"] != currency:
      continue
    result.append(account)

  return result


def get_accounts_by_format(database, tenant, format):
  if not "accounts" in database.data:
    return list()
  if not tenant in database.data['accounts']:
    return list()

  result = list()
  for account, chunk in database.data['accounts'][tenant].items():
    if chunk["format"] != format:
      continue
    result.append(account)

  return result


def get_account_balance_in_time(database, tenant, account):
  if not "accounts" in database.data:
    return dict()
  if not tenant in database.data['accounts']:
    return dict()
  if not account in database.data['accounts'][tenant]:
    return dict()

  chunk = database.data['accounts'][tenant][account]

  changes = list()

  for valueDate, subset in chunk["balance_changes"].items():
    amount = sum([decimal.Decimal(x) for x in subset])
    changes.append((datetime.datetime.strptime(valueDate, "%Y-%m-%dT%H:%M:%SZ"), amount))

  changes = sorted(changes, key=lambda change: change[0])

  balance = decimal.Decimal(0)

  result = dict()

  for change in changes:
    # fixme do not use isoformat helper be explicit in formating
    key = change[0].isoformat() + "Z"
    next_balance = decimal.Decimal(change[1]) + balance
    if balance != next_balance:
      result[key] = "0" if next_balance.is_zero() else '{0:f}'.format(next_balance)
      balance = next_balance

  return result


def get_accounts_participated_in_transactions_larger_than_amount(database, tenant, amount):
  if not "accounts" in database.data:
    return list()
  if not tenant in database.data['accounts']:
    return list()

  amount = decimal.Decimal(amount)

  result = list()
  for account, chunk in database.data['accounts'][tenant].items():
    changes = [decimal.Decimal(y).copy_abs() for x in chunk["balance_changes"].values() for y in x]

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


if __name__ == '__main__':

  database = SecondaryPersistence('./database.json')
  database.hydrate()

  print_and_run('get_current_account_state', database, 'demo', 'CZK_TYPE_INVESTOR_DEPOSIT')
  print_and_run('get_tenants', database)
  print_and_run('get_accounts', database, 'demo')
  print_and_run('get_accounts_by_currency', database, 'demo', 'CZK')
  print_and_run('get_accounts_by_format', database, 'demo', 'BONDSTER_ORIGINATOR')
  print_and_run('get_accounts_participated_in_transactions_larger_than_amount', database, 'demo', '10000')
  print_and_run('get_account_balance_in_time', database, 'demo', 'CZK_TYPE_INVESTOR_DEPOSIT')
