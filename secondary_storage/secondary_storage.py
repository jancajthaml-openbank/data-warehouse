import json


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

    # fixme account.serialize

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

  @property
  def data(self):
    return self.__data
