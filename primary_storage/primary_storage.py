import os
import time


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
