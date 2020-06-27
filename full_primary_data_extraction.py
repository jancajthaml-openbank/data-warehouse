import decimal
import datetime

from secondary_storage import SecondaryPersistence
from primary_storage import PrimaryPersistence


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
