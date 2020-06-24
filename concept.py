import os
import time
import json
import decimal

decimal.getcontext().prec = 35


def get_tenants(root):
  # example:
  # /data

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
  # example:
  # /data/t_demo/account

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
  # example:
  # /data/t_demo/account/NOSTRO/snapshot

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
  # example:
  # /data/t_demo/account/NOSTRO/events/0000000000

  path = root + '/t_' + tenant + '/account/' + account + '/events/' + snapshot
  if not os.path.isdir(path):
    return []

  result = list()
  for x in os.listdir(path):
    if not x:
      continue
    kind, amount, transaction = x.split('_', 2)
    result.append((kind, amount, transaction, time.ctime(os.path.getctime(path+'/'+x))))
  return sorted(result, key=lambda event: event[3])


def get_transaction_ids(root, tenant, account, snapshot):
  # example:
  # /data/t_demo/account/NOSTRO/events/0000000000

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
  # format:
  # "id credit_tenant credit_account debit_tenant debit_account valueDate amount currency"

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
    "valueDate": chunks[5],
    "amount": chunks[6],
    "currency": chunks[7]
  }


def get_transaction_data(root, tenant, transaction):
  # example:
  # /data/t_demo/transaction/xxx-yyy-zzz

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


all_data = {
  "accounts": {},
  "transfers": {},
}


tenants = get_tenants('data')


for tenant in tenants:
  accounts = get_account_names('data', tenant)
  for account in accounts:
    all_data["accounts"][account] = {
      "balances": {}
    }
    snapshots = get_account_snapshots('data', tenant, account)
    # fixme missing initial balance when account was created
    balance = decimal.Decimal(0)
    for snapshot in snapshots:
      events = get_account_events('data', tenant, account, snapshot)

      for event in events:
        if event[0] == '1':
          transfers = get_transaction_data('data', tenant, event[2])["transfers"]

          next_balance = balance + decimal.Decimal(event[1])
          amount = "0" if next_balance.is_zero() else '{0:f}'.format(next_balance)

          if next_balance != balance:
            all_data["accounts"][account]["balances"][transfers[0]["valueDate"]] = amount
            balance = next_balance


with open('out.json', 'w') as fd:
    json.dump(all_data, fd, indent=2, sort_keys=True)
