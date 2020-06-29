import decimal
import datetime

from secondary_storage import SecondaryPersistence
from primary_storage import PrimaryPersistence


class Transaction():

  def __init__(self, tenant, transaction_id):
    self.__tenant = tenant
    self.__id = transaction_id
    self.__status = "new"
    self.__transfers = list()

  @property
  def transfers(self):
    return self.__transfers

  @property
  def tenant(self):
    return self.__tenant

  @property
  def id(self):
    return self.__id

  @property
  def status(self):
    return self.__status

  def hydrate(self, secondary_persistence):
    data = secondary_persistence.get_transaction(self.__tenant, self.__id)
    if not data:
      return
    self.__status = data["status"]
    self.__transfers = data["transfers"]

  def explore(self, primary_persistence):
    data = primary_persistence.get_transaction_data(self.__tenant, self.__id)
    if not data:
      return
    self.__transfers = data["transfers"]
    self.__status = data["status"]


class Account():

  def __init__(self, tenant, name):
    self.__tenant = tenant
    self.__name = name
    self.__format = "???"
    self.__currency = "???"
    self.__last_syn_snapshot = 0
    self.__last_syn_event = None
    self.__balance_changes = dict()

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
    self.__format = data["format"]
    self.__currency = data["currency"]
    self.__last_syn_event = data["last_syn_event"]
    self.__last_syn_snapshot = data["last_syn_snapshot"]
    self.__balance_changes = data["balance_changes"]

  def explore(self, primary_persistence, on_new_transaction_id):
    meta_data = primary_persistence.get_account_meta_data(self.__tenant, self.__name)
    if not meta_data:
      return
    self.__format = meta_data["format"]
    self.__currency = meta_data["currency"]

    events = self.get_new_events(primary_persistence)
    if events:
      self.__last_syn_snapshot = events[-1][0]
      self.__last_syn_event = events[-1][4]
      self.__balance_changes = self.get_account_balance_changes(primary_persistence, events)

      for transaction in self.get_new_transaction_ids(events, primary_persistence):
        on_new_transaction_id(transaction)

  def serialize(self):
    return {
      "format": self.__format,
      "currency": self.__currency,
      "balance_changes": self.__balance_changes,
      "last_syn_snapshot": self.__last_syn_snapshot,
      "last_syn_event": self.__last_syn_event
    }
    return dict()

  def get_new_transaction_ids(self, events, persistence):
    result = set()
    for event in events:
      result.add(event[3])
    return list(result)

  def get_new_events(self, persistence):
    result = list()
    snapshots = persistence.get_account_snapshots(self.__tenant, self.__name, self.__last_syn_snapshot)
    for snapshot in snapshots:
      events = persistence.get_account_events(self.__tenant, self.__name, snapshot, self.__last_syn_event)
      for event in events:
        result.append((snapshot, *event))
    return result

  def get_account_balance_changes(self, persistence, events):
    result = dict()

    balance_changes_set = dict()

    for event in events:
      if event[1] == '1':
        transfers = persistence.get_transaction_data(self.__tenant, event[3])["transfers"]
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

      key = change[1].strftime("%Y-%m-%dT%H:%M:%SZ")
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
    transactions = set()

    for name in primary_persistence.get_account_names(tenant):
      account = Account(tenant, name)
      account.hydrate(secondary_persistence)
      account.explore(primary_persistence, transactions.add)
      secondary_persistence.update_account(account)

    for trn in transactions:
      transaction = Transaction(tenant, trn)
      transaction.hydrate(secondary_persistence)
      transaction.explore(primary_persistence)
      secondary_persistence.update_transaction(transaction)

  secondary_persistence.persist()
