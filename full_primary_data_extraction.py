import os
import time
import json
import decimal
import datetime

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
  #return


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


def get_account_meta_data(root, tenant, account):
  # example:
  # /data/t_demo/account/NOSTRO/snapshot/0000000000

  path = root + '/t_' + tenant + '/account/' + account + '/snapshot/0000000000'
  if not os.path.isfile(path):
    return {}

  with open(path, 'r') as fd:
    line = fd.readline().rstrip().split(' ')
    return {
      "currency": line[0],
      "format": line[1][:-2]
    }

  return {}


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


################################################################################


def get_account_balance_changes(tenant, account):
  result = {}

  snapshots = get_account_snapshots(root_storage, tenant, account)
  balance_changes_set = {}
  # fixme missing initial balance when account was created

  for snapshot in snapshots:
    events = get_account_events(root_storage, tenant, account, snapshot)
    for event in events:
      if event[0] == '1':
        transfers = get_transaction_data(root_storage, tenant, event[2])["transfers"]
        for transfer in filter(lambda x: (x["debit"]["account"] == account and x["debit"]["tenant"] == tenant) or (x["credit"]["account"] == account and x["debit"]["tenant"] == tenant), transfers):
          if transfer["debit"]["account"] == account:
            amount = decimal.Decimal(transfer["amount"]).copy_negate()
          else:
            amount = decimal.Decimal(transfer["amount"])
          valueDate = datetime.datetime.strptime(transfer["valueDate"], "%Y-%m-%dT%H:%M:%SZ")
          if valueDate in balance_changes_set:
            balance_changes_set[valueDate] += amount
          else:
            balance_changes_set[valueDate] = amount

  balance_changes = []
  for valueDate, amount in balance_changes_set.items():
    balance_changes.append((amount, valueDate))

  for change in sorted(balance_changes, key=lambda event: event[1]):
    if change[0].is_zero():
      continue

    if not change[1].isoformat() in result:
      result[change[1].isoformat()] = []
    result[change[1].isoformat()].append('{0:f}'.format(change[0]))

  return result


################################################################################


all_data = {
  "tenants": [],
  "accounts": {},
  "transfers": {},
}

root_storage = 'openbank'

tenants = get_tenants(root_storage)

all_data["tenants"] = tenants

for tenant in tenants:
  accounts = get_account_names(root_storage, tenant)
  all_data["accounts"][tenant] = {}
  all_data["transfers"][tenant] = {}

  for account in accounts:
    all_data["accounts"][tenant][account] = {
      **get_account_meta_data(root_storage, tenant, account),
      "balance_changes": get_account_balance_changes(tenant, account),
    }

with open('database.json', 'w') as fd:
    json.dump(all_data, fd, indent=2, sort_keys=True)
