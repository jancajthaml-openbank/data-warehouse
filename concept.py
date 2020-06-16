import os
import time


def get_tenants(root):
  result = list()
  path = root
  if not os.path.isdir(path):
    return []
  for x in os.listdir(root):
    if not x:
      continue
    if not x.startswith('t_'):
      continue
    result.append(x[2:])
  return result


def get_account_names(root, tenant):
  path = root + '/t_' + tenant + '/account'
  if not os.path.isdir(path):
    return []

  result = list()
  for x in os.listdir(path):
    if not x:
      continue
    result.append(x)
  return result


def get_account_snapshots(root, tenant, account):
  path = root + '/t_' + tenant + '/account/' + account + '/snapshot'
  if not os.path.isdir(path):
    return []

  result = list()
  for x in os.listdir(path):
    if not x:
      continue
    result.append(x)
  if result:
    result.sort()
  return result


def get_account_events(root, tenant, account, snapshot):
  path = root + '/t_' + tenant + '/account/' + account + '/events/' + snapshot
  if not os.path.isdir(path):
    return []

  result = list()
  for x in os.listdir(path):
    if not x:
      continue
    kind, amount, transaction = x.split('_', 2)
    result.append((kind, amount, transaction, time.ctime(os.path.getctime(path+'/'+x))))
  return result


def get_transaction_ids(root, tenant, account, snapshot):
  path = root + '/t_' + tenant + '/account/' + account + '/events/' + snapshot
  if not os.path.isdir(path):
    return []

  result = set()
  for x in os.listdir(path):
    if not x:
      continue
    _, _, transaction = x.split('_', 2)
    result.add(transaction)
  return list(result)


def parse_transfer(data):
  chunks = data.split(' ')

  return {
    "id": chunks[0],
    "credit": {
      "tenant": chunks[1],
      "account": chunks[2]
    },
    "debit": {
      "tenant": chunks[3],
      "account": chunks[4]
    },
    "amount": chunks[6],
    "currency": chunks[7],
    "valueDate": chunks[5]
  }


def get_transaction_data(root, tenant, transaction):
  path = root + '/t_' + tenant + '/transaction/' + transaction
  if not os.path.isfile(path):
    return {}

  with open(path, "r") as fd:
    content = fd.read().splitlines()
    return {
      "id": transaction,
      "status": content[0],
      "transfers": [parse_transfer(line) for line in content[1:]]
    }

  return {}


tenants = get_tenants('data')

for tenant in tenants:
  accounts = get_account_names('data', tenant)
  for account in accounts:
    snapshots = get_account_snapshots('data', tenant, account)
    for snapshot in snapshots:
      events = get_account_events('data', tenant, account, snapshot)
      transactions_ids = get_transaction_ids('data', tenant, account, snapshot)
      for transaction_id in transactions_ids:
        transaction_data = get_transaction_data('data', tenant, transaction_id)
