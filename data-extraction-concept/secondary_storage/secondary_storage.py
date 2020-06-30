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

  def update_transaction(self, transaction):
    if not "transfers" in self.__data:
      self.__data["transfers"] = dict()
    if not transaction.tenant in self.__data["transfers"]:
      self.__data["transfers"][transaction.tenant] = dict()
    if not transaction.id in self.__data["transfers"][transaction.tenant]:
      self.__data["transfers"][transaction.tenant][transaction.id] = dict()

    for transfer in transaction.transfers:
      if transfer["id"] in self.__data["transfers"][transaction.tenant][transaction.id]:
        continue
      self.__data["transfers"][transaction.tenant][transaction.id][transfer["id"]] = {
        "status": transaction.status,
        "credit": transfer["credit"],
        "debit": transfer["debit"],
        "amount": transfer["amount"],
        "valueDate": transfer["valueDate"],
        "currency": transfer["currency"]
      }

  def update_account(self, account):
    if not "accounts" in self.__data:
      self.__data["accounts"] = dict()
    if not account.tenant in self.__data["accounts"]:
      self.__data["accounts"][account.tenant] = dict()

    self.__data["accounts"][account.tenant][account.name] = account.serialize()

  def get_account(self, tenant, account):
    if not "accounts" in self.__data:
      return None
    if not tenant in self.__data["accounts"]:
      return None
    if not account in self.__data["accounts"][tenant]:
      return None
    return self.__data["accounts"][tenant][account]

  def get_transaction(self, tenant, transaction_id):
    if not "transfers" in self.__data:
      return None
    if not tenant in self.__data["transfers"]:
      return None
    if not transaction_id in self.__data["transfers"][tenant]:
      return None

    result = dict()
    result["id"] = transaction_id
    result["transfers"] = list()
    for transfer, data in self.__data["transfers"][tenant][transaction_id].items():
      result["status"] = data["status"]
      result["transfers"].append({
        "id": transfer,
        "credit": data["credit"],
        "debit": data["debit"],
        "amount": data["amount"],
        "currency": data["currency"],
        "valueDate": data["valueDate"]
      })
    return result

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
