import os
import time
import datetime


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

  def get_account_snapshots(self, tenant, account, last_snapshot):
    # example:
    # /data/t_demo/account/NOSTRO/snapshot

    result = list()

    path = self.__root + '/t_' + tenant + '/account/' + account + '/snapshot'
    if not os.path.isdir(path):
      return result

    for x in os.listdir(path):
      if not x:
        continue
      x = int(x)
      # skip snapshots that are older that what we already processed
      if x < last_snapshot:
        continue
      result.append(x)
    return sorted(result)

  def get_account_events(self, tenant, account, snapshot, last_event):
    # example:
    # /data/t_demo/account/NOSTRO/events/0000000000

    last_event = -1 if last_event is None else last_event

    result = list()

    path = self.__root + '/t_' + tenant + '/account/' + account + '/events/' + "0000000000"[0:-len(str(snapshot))] + str(snapshot)

    if not os.path.isdir(path):
      return result

    events = os.listdir(path)

    # we can assume that we have all events in given snapshot if last event id
    # is equal to number of events without reading the files
    if len(events) == last_event:
      return result

    for event in events:
      kind, amount, transaction = event.split('_', 2)
      with open(path+'/'+event) as fd:
        event_id = int(fd.read())
        # skip events that are before what we already processed
        if event_id > last_event:
          result.append((kind, amount, transaction, event_id))

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
