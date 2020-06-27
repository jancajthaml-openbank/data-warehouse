import os
import time
import json
import decimal
import datetime

decimal.getcontext().prec = 35

class SecondaryPersistence():

  # represents stub implementation of secondary partitioned data

  def __init__(self, filename):
    self.__filename = filename
    self.__data = dict()

  def hydrate(self):
    try:
      with open(self.__filename, 'r') as fd:
        self.__data = json.loads(fd.read())
    except (FileNotFoundError, json.decoder.JSONDecodeError):
      self.__data = dict()

  def update_account(self, account, primary_persistence):
    if not "accounts" in self.__data:
      self.__data["accounts"] = dict()
    if not account.tenant in self.__data["accounts"]:
      self.__data["accounts"][account.tenant] = dict()

    self.__data["accounts"][account.tenant][account.name] = {
      **primary_persistence.get_account_meta_data(account.tenant, account.name),
      "balance_changes": account.get_account_balance_changes(primary_persistence),
      "last_syn_snapshot": 0,
      "last_syn_event": "???"
    }

  def get_account(self, tenant, account):
    if not "accounts" in self.__data:
      return None
    if not tenant in self.__data["accounts"]:
      return None
    if not account in self.__data["accounts"][tenant]:
      return None
    return self.__data["accounts"][tenant][account]

  def update_tenant(self, tenant):
    if not "tenants" in self.__data:
      self.__data["tenants"] = list()

    if tenant in self.__data["tenants"]:
      return

    self.__data["tenants"].append(tenant)

  def persist(self):
    with open(self.__filename, 'w') as fd:
      json.dump(self.__data, fd, indent=2, sort_keys=True)

  def get_tenants(self, primary_persistence):
    for tenant in primary_persistence.get_tenants():
      self.update_tenant(tenant)
    return self.__data["tenants"]


class PrimaryPersistence():

  # represents fascade over primary data

  def __init__(self, root):
    self.__root = root

  def get_tenants(self):
    # example:
    # /data

    result = list()
    if not os.path.isdir(self.__root):
      return []
    for x in os.listdir(self.__root):
      if not x:
        continue
      if not x.startswith('t_'):
        continue
      result.append(x[2:])
    return result

  def get_account_names(self, tenant):
    # example:
    # /data/t_demo/account

    result = list()

    path = self.__root + '/t_' + tenant + '/account'
    if not os.path.isdir(path):
      return result

    for x in os.listdir(path):
      if not x:
        continue
      result.append(x)
    return result

  def get_account_snapshots(self, tenant, account):
    # example:
    # /data/t_demo/account/NOSTRO/snapshot

    result = list()

    path = self.__root + '/t_' + tenant + '/account/' + account + '/snapshot'
    if not os.path.isdir(path):
      return result

    for x in os.listdir(path):
      if not x:
        continue
      result.append(x)
    if result:
      result.sort()
    return result

  def get_account_events(self, tenant, account, snapshot):
    # example:
    # /data/t_demo/account/NOSTRO/events/0000000000

    result = list()

    path = self.__root + '/t_' + tenant + '/account/' + account + '/events/' + snapshot

    if not os.path.isdir(path):
      return result

    for x in os.listdir(path):
      if not x:
        continue
      kind, amount, transaction = x.split('_', 2)
      result.append((kind, amount, transaction, time.ctime(os.path.getctime(path+'/'+x))))
    return sorted(result, key=lambda event: event[3])

  def get_transaction_ids(self, tenant, account, snapshot):
    # example:
    # /data/t_demo/account/NOSTRO/events/0000000000

    path = self.__root + '/t_' + tenant + '/account/' + account + '/events/' + snapshot
    if not os.path.isdir(path):
      return list()

    result = set()
    for x in os.listdir(path):
      if not x:
        continue
      _, _, transaction = x.split('_', 2)
      result.add(transaction)
    return list(result)

  def get_transaction_data(self, tenant, transaction):
    # example:
    # /data/t_demo/transaction/xxx-yyy-zzz

    path = self.__root + '/t_' + tenant + '/transaction/' + transaction

    if not os.path.isfile(path):
      return dict()

    with open(path, "r") as fd:
      content = fd.read().splitlines()

      transfers = list()
      for transfer in content[1:]:
        # format:
        # "id credit_tenant credit_account debit_tenant debit_account valueDate amount currency"

        chunks = transfer.split(' ')
        transfers.append({
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
        })

      return {
        "id": transaction,
        "status": content[0],
        "transfers": transfers
      }

  def get_account_meta_data(self, tenant, account):
    # example:
    # /data/t_demo/account/NOSTRO/snapshot/0000000000

    path = self.__root + '/t_' + tenant + '/account/' + account + '/snapshot/0000000000'
    if not os.path.isfile(path):
      return dict()

    with open(path, 'r') as fd:
      line = fd.readline().rstrip()
      return {
        "currency": line[0:3],
        "format": line[4:-2]
      }


class Account():

  def __init__(self, tenant, name):
    self.__tenant = tenant
    self.__name = name
    pass

  @property
  def tenant(self):
    return self.__tenant

  @property
  def name(self):
    return self.__name

  def hydrate(self, secondary_persistence):
    data = secondary_persistence.get_account(self.__tenant, self.__name)
    if not data:
      return
    # fixme actually implement
    #print(data['last_syn_snapshot'], data['last_syn_event'])

  def explore(self, primary_persistence):
    pass

  def get_account_balance_changes(self, persistence):
    result = dict()

    snapshots = persistence.get_account_snapshots(self.__tenant, self.__name)
    balance_changes_set = dict()
    # fixme missing initial balance when account was created

    for snapshot in snapshots:
      events = persistence.get_account_events(self.__tenant, self.__name, snapshot)
      for event in events:
        if event[0] == '1':
          transfers = persistence.get_transaction_data(self.__tenant, event[2])["transfers"]
          for transfer in filter(lambda x: (x["debit"]["account"] == self.__name and x["debit"]["tenant"] == self.__tenant) or (x["credit"]["account"] == self.__name and x["debit"]["tenant"] == self.__tenant), transfers):
            if transfer["debit"]["account"] == account:
              amount = decimal.Decimal(transfer["amount"]).copy_negate()
            else:
              amount = decimal.Decimal(transfer["amount"])
            valueDate = datetime.datetime.strptime(transfer["valueDate"], "%Y-%m-%dT%H:%M:%SZ")
            if valueDate in balance_changes_set:
              balance_changes_set[valueDate] += amount
            else:
              balance_changes_set[valueDate] = amount

    balance_changes = list()
    for valueDate, amount in balance_changes_set.items():
      balance_changes.append((amount, valueDate))

    for change in sorted(balance_changes, key=lambda event: event[1]):
      if change[0].is_zero():
        continue

      key = change[1].isoformat() + "Z"
      if not key in result:
        result[key] = list()
      result[key].append('{0:f}'.format(change[0]))

    return result


################################################################################


if __name__ == '__main__':

  primary_persistence = PrimaryPersistence('./data')
  secondary_persistence = SecondaryPersistence('./database.json')
  secondary_persistence.hydrate()

  tenants = secondary_persistence.get_tenants(primary_persistence)

  for tenant in tenants:
    for account in [Account(tenant, name) for name in primary_persistence.get_account_names(tenant)]:
      account.hydrate(secondary_persistence)
      account.explore(primary_persistence)
      secondary_persistence.update_account(account, primary_persistence)

    #for transaction_id in get_transaction_ids(root_storage, tenant):
    #  print(transaction_id)

  secondary_persistence.persist()
