#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
from openbank_testkit import Shell, Platform, Package
from systemd import journal


class UnitHelper(object):

  @staticmethod
  def default_config():
    return {
      "LOG_LEVEL": "DEBUG",
      "HTTP_PORT": "80",
      "PRIMARY_STORAGE_PATH": "/data",
      "POSTGRES_URL": "jdbc:postgresql://127.0.0.1:5432/openbank",
      "STATSD_URL": "udp://127.0.0.1:8125",
    }

  def __init__(self, context):
    self.store = dict()
    self.units = list()
    self.context = context
    self.__bootstrap_postgres()

  def __bootstrap_postgres(self):
    package = Package('postgres')
    version = package.latest_version

    cwd = os.path.realpath('{}/../..'.format(os.path.dirname(__file__)))

    assert package.download(version, meta, '{}/packaging/bin'.format(cwd)), 'unable to download package data-warehouse'

    binary = '{}/packaging/bin/postgres_{}_{}.deb'.format(cwd, version, Platform.arch)

    (code, result, error) = Shell.run([
      "apt-get", "install", "-f", "-qq", "-o=Dpkg::Use-Pty=0", "-o=Dpkg::Options::=--force-confdef", "-o=Dpkg::Options::=--force-confnew", binary
    ])
    assert code == 'OK', str(code) + ' ' + result


  def download(self):
    version = os.environ.get('VERSION', '')
    meta = os.environ.get('META', '')

    if version.startswith('v'):
      version = version[1:]

    assert version, 'VERSION not provided'
    assert meta, 'META not provided'

    package = Package('data-warehouse')

    cwd = os.path.realpath('{}/../..'.format(os.path.dirname(__file__)))

    assert package.download(version, meta, '{}/packaging/bin'.format(cwd)), 'unable to download package data-warehouse'

    self.binary = '{}/packaging/bin/data-warehouse_{}_{}.deb'.format(cwd, version, Platform.arch)

  def configure(self, params = None):
    options = dict()
    options.update(UnitHelper.default_config())
    if params:
      options.update(params)

    os.makedirs('/etc/data-warehouse/conf.d', exist_ok=True)
    with open('/etc/data-warehouse/conf.d/init.conf', 'w') as fd:
      fd.write(str(os.linesep).join("DATA_WAREHOUSE_{!s}={!s}".format(k, v) for (k, v) in options.items()))

  def __fetch_logs(self, unit=None):
    reader = journal.Reader()
    reader.this_boot()
    reader.log_level(journal.LOG_DEBUG)
    if unit:
      reader.add_match(_SYSTEMD_UNIT=unit)
    for entry in reader:
      yield entry['MESSAGE']

  def collect_logs(self):
    cwd = os.path.realpath('{}/../..'.format(os.path.dirname(__file__)))

    logs_dir = '{}/reports/blackbox-tests/logs'.format(cwd)
    os.makedirs(logs_dir, exist_ok=True)

    with open('{}/journal.log'.format(logs_dir), 'w') as fd:
      for line in self.__fetch_logs():
        fd.write(line)
        fd.write(os.linesep)

    for unit in set(self.__get_systemd_units() + self.units):
      with open('{}/{}.log'.format(logs_dir, unit), 'w') as fd:
        for line in self.__fetch_logs(unit):
          fd.write(line)
          fd.write(os.linesep)

  def teardown(self):
    self.collect_logs()
    for unit in self.__get_systemd_units():
      Shell.run(['systemctl', 'stop', unit])
    self.collect_logs()

  def __get_systemd_units(self):
    (code, result, error) = Shell.run(['systemctl', 'list-units', '--all', '--no-legend'])
    result = [item.replace('*', '').strip().split(' ')[0].strip() for item in result.split(os.linesep)]
    result = [item for item in result if "data-warehouse" in item and not item.endswith('unit.slice')]
    return result
