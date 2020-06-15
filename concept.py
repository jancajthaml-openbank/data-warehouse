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


tenants = get_tenants('data')

for tenant in tenants:
  accounts = get_account_names('data', tenant)
  for account in accounts:
    snapshots = get_account_snapshots('data', tenant, account)
    for snapshot in snapshots:
      events = get_account_events('data', tenant, account, snapshot)
      transactions = get_transaction_ids('data', tenant, account, snapshot)
